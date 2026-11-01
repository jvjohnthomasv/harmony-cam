package com.harmonycam.app

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.harmonycam.app.audio.AudioRouting
import com.harmonycam.app.audio.NativeEngine
import com.harmonycam.app.camera.CameraController
import com.harmonycam.app.gesture.GestureInterpreter
import com.harmonycam.app.gesture.HandTracker
import com.harmonycam.app.gl.EffectsRenderer
import com.harmonycam.app.gl.VisualInput
import com.harmonycam.app.lyrics.LyricsStore
import com.harmonycam.app.lyrics.LyricsTracker
import com.harmonycam.app.lyrics.Song
import com.harmonycam.app.record.ExportPipeline
import com.harmonycam.app.record.VideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

data class Meters(
    val level: Float = 0f,
    val voiced: Boolean = false,
    val midi: Float = 0f,
    val note: Int = 0,
    val keyRoot: Int = -1,
    val minor: Boolean = false,
    val voiceMidi: IntArray = IntArray(4),
    val voiceEnv: FloatArray = FloatArray(4),
    val inputPeak: Float = 0f,
    val recSeconds: Float = 0f,
)

class StudioViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext

    var settings by mutableStateOf(StudioSettings.load(ctx)); private set
    var route by mutableStateOf<AudioRouting.Route?>(null); private set
    var engineError by mutableStateOf<String?>(null); private set
    var gesture by mutableStateOf(GestureInterpreter.State()); private set
    var meters by mutableStateOf(Meters()); private set

    var songs by mutableStateOf<List<Song>>(emptyList()); private set
    var activeSong by mutableStateOf<Song?>(null); private set
    var lyricLine by mutableIntStateOf(0); private set
    var lyricWord by mutableIntStateOf(0); private set
    var lyricsStatus by mutableStateOf(LyricsTracker.Status.IDLE); private set
    var prompterVisible by mutableStateOf(true)

    var recording by mutableStateOf(false); private set
    var exporting by mutableStateOf(false); private set
    var exportProgress by mutableFloatStateOf(0f); private set
    var exportLabel by mutableStateOf(""); private set
    var lastVideo by mutableStateOf<Uri?>(null); private set
    var message by mutableStateOf<String?>(null)

    private val store = LyricsStore(ctx)
    private val interpreter = GestureInterpreter()
    private val camera = CameraController(ctx)
    private val exportPipeline = ExportPipeline(ctx)
    private val lyrics = LyricsTracker(ctx, viewModelScope)
    private var handTracker: HandTracker? = null
    private var renderer: EffectsRenderer? = null
    private val routing = AudioRouting(ctx) { r -> onRouteChanged(r) }
    private var meterJob: Job? = null
    private var started = false

    // snapshot for the GL thread
    @Volatile private var visualHands: Pair<FloatArray?, FloatArray?> = null to null
    @Volatile private var visualMeters = Meters()
    @Volatile private var visualGesture = GestureInterpreter.State()
    private var smoothLevel = 0f
    private var pulse = 0f

    // recording state
    private var encoder: VideoEncoder? = null
    private var takeDir: File? = null
    private var recordStartNs = 0L

    init {
        songs = store.load()
        lyrics.onUpdate = { line, word -> lyricLine = line; lyricWord = word }
        lyrics.onStatus = { s, err ->
            viewModelScope.launch(Dispatchers.Main) {
                lyricsStatus = s
                if (s == LyricsTracker.Status.ERROR) message = "Lyric tracking unavailable: ${err ?: "unknown error"}"
            }
        }
        interpreter.reset(settings.manualVoices, settings.manualHeight)
    }

    // ───────────────────────── lifecycle

    fun attachRenderer(r: EffectsRenderer) {
        renderer = r
        r.style = settings.style
        r.extraRotation = settings.camExtraRotation
        r.mirror = settings.camMirror
        r.inputProvider = { input -> fillVisuals(input) }
        r.onSurfaceTextureRecreated = { rebindCamera() }
    }

    private var owner: LifecycleOwner? = null

    private fun rebindCamera() {
        val o = owner ?: return
        val r = renderer ?: return
        val t = handTracker ?: return
        if (started) camera.bind(o, r, t)
    }

    /** Called when permissions are granted and the activity is started. */
    fun onStart(owner: LifecycleOwner) {
        if (started) return
        started = true
        this.owner = owner
        routing.register()
        startEngine(routing.current)
        val r = renderer
        if (r != null) {
            val tracker = handTracker ?: HandTracker(ctx) { hands, t -> onHands(hands, t) }.also { handTracker = it }
            tracker.enabled = settings.gestures
            camera.bind(owner, r, tracker)
        }
        meterJob = viewModelScope.launch { pollMeters() }
        activeSong?.let { startLyrics(it, lyricLine) }
    }

    fun onStop() {
        if (!started) return
        started = false
        if (recording) stopRecording()
        meterJob?.cancel()
        lyrics.stop()
        camera.unbind()
        routing.unregister()
        NativeEngine.nStop()
    }

    override fun onCleared() {
        onStop()
        lyrics.release()
        handTracker?.close()
        camera.shutdown()
    }

    // ───────────────────────── audio

    private fun startEngine(r: AudioRouting.Route) {
        route = r
        NativeEngine.nSetRouting(r.inputId, r.outputId, settings.inputChannel)
        NativeEngine.nSetInputGainDb(settings.inputGainDb)
        pushControls()
        applyMonitor()
        if (!NativeEngine.nStart()) {
            engineError = "Couldn't open the microphone/audio output. Check the mic permission and that no other app is using it."
        } else {
            engineError = null
        }
    }

    private fun onRouteChanged(r: AudioRouting.Route) {
        if (!started) { route = r; return }
        if (recording) stopRecording()
        NativeEngine.nStop()
        startEngine(r)
        message = when {
            r.inputIsUsb -> "Using ${r.inputName} for mic and monitoring"
            r.output == AudioRouting.OutputKind.SPEAKER -> "Headphones disconnected — live monitoring muted"
            else -> "Monitoring on ${r.outputName}"
        }
    }

    private fun applyMonitor() {
        val r = route
        val canMonitor = (r?.canMonitor == true) || settings.speakerMonitor
        NativeEngine.nSetMonitorGain(if (canMonitor) settings.monitorLevel else 0f)
    }

    private fun pushControls() {
        val s = settings
        NativeEngine.nSetControl(NativeEngine.C_KEY_ROOT, s.keyRoot.toFloat())
        NativeEngine.nSetControl(NativeEngine.C_SCALE, if (s.minor) 1f else 0f)
        NativeEngine.nSetControl(NativeEngine.C_HARMONY_LEVEL, s.harmonyLevel)
        NativeEngine.nSetControl(NativeEngine.C_DRY_LEVEL, s.leadLevel)
        NativeEngine.nSetControl(NativeEngine.C_HUMANIZE, s.humanize)
        NativeEngine.nSetControl(NativeEngine.C_TIGHTNESS, s.tightness)
        NativeEngine.nSetControl(NativeEngine.C_REVERB, s.reverb)
        NativeEngine.nSetControl(NativeEngine.C_WIDTH, s.width)
        if (!s.gestures) {
            NativeEngine.nSetControl(NativeEngine.C_VOICES, s.manualVoices.toFloat())
            NativeEngine.nSetControl(NativeEngine.C_HEIGHT, s.manualHeight)
            NativeEngine.nSetControl(NativeEngine.C_MUTE, 0f)
            NativeEngine.nSetControl(NativeEngine.C_FX, s.manualFx)
        } else {
            val g = gesture
            NativeEngine.nSetControl(NativeEngine.C_VOICES, g.voices.toFloat())
            NativeEngine.nSetControl(NativeEngine.C_HEIGHT, g.height)
            NativeEngine.nSetControl(NativeEngine.C_MUTE, if (g.mute) 1f else 0f)
            NativeEngine.nSetControl(NativeEngine.C_FX, g.fx)
        }
    }

    fun updateSettings(transform: (StudioSettings) -> StudioSettings) {
        val old = settings
        val s = transform(old)
        settings = s
        StudioSettings.save(ctx, s)
        renderer?.let { it.style = s.style; it.extraRotation = s.camExtraRotation; it.mirror = s.camMirror }
        handTracker?.enabled = s.gestures
        if (s.gestures != old.gestures) interpreter.reset(s.manualVoices, s.manualHeight)
        pushControls()
        applyMonitor()
        if (s.inputGainDb != old.inputGainDb) NativeEngine.nSetInputGainDb(s.inputGainDb)
        if (s.inputChannel != old.inputChannel && started && !recording) {
            NativeEngine.nStop()
            startEngine(routing.current)
        }
    }

    private suspend fun pollMeters() {
        val buf = FloatArray(NativeEngine.NUM_METERS)
        while (viewModelScope.isActive) {
            NativeEngine.nGetMeters(buf)
            val rms = buf[NativeEngine.M_RMS_DB]
            val level = ((rms + 60f) / 45f).coerceIn(0f, 1f)
            val m = Meters(
                level = level,
                voiced = buf[NativeEngine.M_VOICED] > 0.5f,
                midi = buf[NativeEngine.M_MIDI],
                note = buf[NativeEngine.M_NOTE].toInt(),
                keyRoot = buf[NativeEngine.M_KEY_ROOT].toInt(),
                minor = buf[NativeEngine.M_SCALE].toInt() == 1,
                voiceMidi = IntArray(4) { buf[NativeEngine.M_VOICE_MIDI0 + it].toInt() },
                voiceEnv = FloatArray(4) { buf[NativeEngine.M_ENV0 + it] },
                inputPeak = buf[NativeEngine.M_INPUT_PEAK],
                recSeconds = buf[NativeEngine.M_REC_SECONDS],
            )
            meters = m
            visualMeters = m
            delay(33)
        }
    }

    // ───────────────────────── gestures & visuals

    private fun onHands(hands: List<FloatArray>, timeMs: Long) {
        val s = settings
        val g = interpreter.update(hands, s.swapHands, s.holdWhenLost, timeMs)
        if (s.gestures) {
            NativeEngine.nSetControl(NativeEngine.C_VOICES, g.voices.toFloat())
            NativeEngine.nSetControl(NativeEngine.C_HEIGHT, g.height)
            NativeEngine.nSetControl(NativeEngine.C_MUTE, if (g.mute) 1f else 0f)
            NativeEngine.nSetControl(NativeEngine.C_FX, g.fx)
        }
        val r = renderer
        fun map(h: FloatArray?): FloatArray? {
            if (h == null || r == null) return h
            val out = FloatArray(h.size)
            for (i in 0 until h.size / 2) {
                val (x, y) = r.mapToFrame(h[2 * i], h[2 * i + 1])
                out[2 * i] = x; out[2 * i + 1] = y
            }
            return out
        }
        visualHands = map(g.voiceHand) to map(g.fxHand)
        visualGesture = g
        viewModelScope.launch(Dispatchers.Main) { gesture = g }
    }

    /** GL thread: fill the per-frame visual input. */
    private fun fillVisuals(input: VisualInput) {
        val m = visualMeters
        val g = visualGesture
        val s = settings
        val target = if (m.voiced) m.level else m.level * 0.5f
        val prev = smoothLevel
        smoothLevel += (target - smoothLevel) * 0.2f
        pulse = max(pulse * 0.9f, (smoothLevel - prev) * 8f).coerceIn(0f, 1f)
        input.level = smoothLevel
        input.pulse = pulse
        input.fx = if (s.gestures) g.fx else s.manualFx
        input.mute = s.gestures && g.mute
        val (vh, fh) = visualHands
        input.voiceHand = if (s.gestures) vh else null
        input.fxHand = if (s.gestures) fh else null
        input.extended = g.extended
        input.showSkeleton = s.showSkeleton
        for (i in 0 until 4) { input.voiceMidi[i] = m.voiceMidi[i]; input.voiceEnv[i] = m.voiceEnv[i] }
    }

    // ───────────────────────── recording

    fun toggleRecording() { if (recording) stopRecording() else startRecording() }

    private fun startRecording() {
        if (exporting) return
        if (!NativeEngine.nIsRunning()) { message = "Audio engine isn't running"; return }
        val dir = File(ctx.cacheDir, "take_${System.currentTimeMillis()}").apply { mkdirs() }
        takeDir = dir
        val enc = VideoEncoder.create(File(dir, "video.mp4"))
        if (enc == null) message = "Video encoder unavailable — recording audio only"
        recordStartNs = System.nanoTime()
        if (!NativeEngine.nStartRecording(File(dir, "vocal.wav").absolutePath, File(dir, "automation.bin").absolutePath)) {
            enc?.finish()
            message = "Couldn't start recording"
            return
        }
        encoder = enc
        if (enc != null) renderer?.startRecording(enc, recordStartNs)
        recording = true
        // restart lyrics from the top line the singer is on
        activeSong?.let { lyrics.jumpTo(lyricLine) }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        val res = NativeEngine.nStopRecording()
        val audioStartNs = res[1]
        val dir = takeDir ?: return
        val enc = encoder
        encoder = null
        val controls = currentControlsArray()
        val finishVideo: () -> Unit = {
            viewModelScope.launch(Dispatchers.Default) {
                val video = enc?.finish()
                export(ExportPipeline.Take(File(dir, "vocal.wav"), File(dir, "automation.bin"), video, audioStartNs, recordStartNs, controls), dir)
            }
        }
        val r = renderer
        if (enc != null && r != null) r.stopRecording { finishVideo() } else finishVideo()
    }

    private fun currentControlsArray(): FloatArray {
        val s = settings; val g = gesture
        return floatArrayOf(
            if (s.gestures) g.voices.toFloat() else s.manualVoices.toFloat(),
            if (s.gestures) g.height else s.manualHeight,
            if (s.gestures && g.mute) 1f else 0f,
            if (s.gestures) g.fx else s.manualFx,
            s.keyRoot.toFloat(), if (s.minor) 1f else 0f, s.harmonyLevel, s.leadLevel,
            s.humanize, s.tightness, s.reverb, s.width
        )
    }

    private suspend fun export(take: ExportPipeline.Take, dir: File) {
        withContext(Dispatchers.Main) { exporting = true; exportProgress = 0f; exportLabel = "Rendering harmonies" }
        val result = try {
            exportPipeline.export(take) { p, label ->
                viewModelScope.launch(Dispatchers.Main) { exportProgress = p; exportLabel = label }
            }
        } catch (e: Exception) {
            Log.e("Studio", "export", e)
            ExportPipeline.Result(null, null, e.message)
        }
        dir.deleteRecursively()
        withContext(Dispatchers.Main) {
            exporting = false
            if (result.error != null) message = result.error
            else { lastVideo = result.uri; message = "Saved to Gallery › Movies/HarmonyCam" }
        }
    }

    // ───────────────────────── lyrics

    fun saveSong(song: Song) {
        songs = store.upsert(songs, song)
        if (activeSong?.id == song.id) selectSong(song)
    }

    fun deleteSong(id: String) {
        songs = store.delete(songs, id)
        if (activeSong?.id == id) selectSong(null)
    }

    fun selectSong(song: Song?) {
        activeSong = song
        lyricLine = 0; lyricWord = 0
        if (song == null) { lyrics.stop(); return }
        prompterVisible = true
        if (started) startLyrics(song, 0)
    }

    private fun startLyrics(song: Song, line: Int) = lyrics.start(song.lines, line)

    fun nextLine() {
        val n = activeSong?.lines?.size ?: return
        lyrics.jumpTo((lyricLine + 1).coerceAtMost(n - 1))
    }

    fun previousLine() = lyrics.jumpTo((lyricLine - 1).coerceAtLeast(0))

    fun restartLyrics() = lyrics.jumpTo(0)

    @Suppress("unused")
    private val bootTime = SystemClock.uptimeMillis()
}
