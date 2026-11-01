package com.harmonycam.app

import android.app.Application
import android.net.Uri
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
import com.harmonycam.app.audio.TrackDecoder
import com.harmonycam.app.camera.CameraController
import com.harmonycam.app.gesture.GestureInterpreter
import com.harmonycam.app.gesture.HandTracker
import androidx.camera.view.PreviewView
import com.harmonycam.app.camera.HandOverlay
import kotlinx.coroutines.CompletableDeferred
import com.harmonycam.app.lyrics.LyricsStore
import com.harmonycam.app.lyrics.LyricsTracker
import com.harmonycam.app.lyrics.Song
import com.harmonycam.app.record.ExportPipeline
import com.harmonycam.app.record.Take
import com.harmonycam.app.record.TakesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    val trackPos: Float = 0f,
    val trackPlaying: Boolean = false,
    val trackLength: Float = 0f,
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
    var message by mutableStateOf<String?>(null)

    var takes by mutableStateOf<List<Take>>(emptyList()); private set
    /** The take just finished — shown in the review card (Play / Save / Retake). */
    var reviewTake by mutableStateOf<Take?>(null)

    var trackLoaded by mutableStateOf(false); private set
    var trackLoading by mutableStateOf(false); private set
    var trackLoadProgress by mutableFloatStateOf(0f); private set
    var trackKey by mutableStateOf<Pair<Int, Boolean>?>(null); private set
    var zoom by mutableStateOf<CameraController.ZoomInfo?>(null); private set

    private val store = LyricsStore(ctx)
    private val takesStore = TakesStore(ctx)
    private val interpreter = GestureInterpreter()
    private val camera = CameraController(ctx)
    private val exportPipeline = ExportPipeline(ctx)
    private val lyrics = LyricsTracker(ctx, viewModelScope)
    private var handTracker: HandTracker? = null
    private var previewView: PreviewView? = null
    val overlay = HandOverlay()
    private val routing = AudioRouting(ctx) { r -> onRouteChanged(r) }
    private var meterJob: Job? = null
    private var started = false
    private var owner: LifecycleOwner? = null
    private val trackFile = File(ctx.filesDir, "track/current.wav")

    private var smoothLevel = 0f

    // recording state
    private var takeDir: File? = null
    private var recordStartNs = 0L
    @Volatile private var videoStartNs = 0L
    private var videoResult: CompletableDeferred<File?>? = null

    init {
        songs = store.load()
        takes = takesStore.list()
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

    fun attachPreview(view: PreviewView) {
        previewView = view
        camera.onZoomChanged = { z -> viewModelScope.launch(Dispatchers.Main) { zoom = z } }
        applyOverlaySettings()
    }

    private fun applyOverlaySettings() {
        overlay.enabled = settings.handOverlay
        overlay.orbEnabled = settings.orb
    }

    fun setZoom(ratio: Float) = camera.setZoom(ratio)
    fun zoomBy(factor: Float) = camera.zoomBy(factor)

    /** Called when permissions are granted and the activity is started. */
    fun onStart(owner: LifecycleOwner) {
        if (started) return
        started = true
        this.owner = owner
        routing.register()
        startEngine(routing.current)
        val view = previewView
        if (view != null) {
            val tracker = handTracker ?: HandTracker(ctx) { d -> onHands(d) }.also { handTracker = it }
            tracker.enabled = settings.gestures || settings.handOverlay
            camera.maxQuality = settings.maxVideoQuality
            camera.bind(owner, view, tracker, overlay)
        }
        meterJob = viewModelScope.launch { pollMeters() }
        activeSong?.let { startLyrics(it, lyricLine) }
        if (!trackLoaded && trackFile.exists()) reloadTrack()
    }

    fun onStop() {
        if (!started) return
        started = false
        if (recording) stopRecording()
        NativeEngine.nSetTrackPlaying(false)
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
        engineError = if (!NativeEngine.nStart())
            "Couldn't open the microphone/audio output. Check the mic permission and that no other app is using it."
        else null
        // the engine's sample rate may have changed with the device: reload the track at the new rate
        if (trackLoaded && trackFile.exists()) reloadTrack(quiet = true)
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

    private val canMonitor get() = (route?.canMonitor == true) || settings.speakerMonitor

    private fun applyMonitor() {
        NativeEngine.nSetMonitorGain(if (canMonitor) settings.monitorLevel else 0f)
        NativeEngine.nSetTrackGain(settings.trackLevel)
    }

    private fun effectiveVoices(gestureVoices: Int): Int = when {
        !settings.harmoniesOn || settings.effectsOff -> 0
        settings.gestures -> gestureVoices
        else -> settings.manualVoices
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
        NativeEngine.nSetControl(NativeEngine.C_CORRECTION, s.correction)
        NativeEngine.nSetControl(NativeEngine.C_RETUNE, s.retune)
        NativeEngine.nSetControl(NativeEngine.C_DOUBLER, s.doubler)
        NativeEngine.nSetControl(NativeEngine.C_ECHO, s.echo)
        NativeEngine.nSetControl(NativeEngine.C_BYPASS, if (s.effectsOff) 1f else 0f)
        NativeEngine.nSetControl(NativeEngine.C_STYLE, if (s.chordStyle) NativeEngine.STYLE_CHORD else NativeEngine.STYLE_INTERVALS)
        NativeEngine.nSetMonitorTuning(s.monitorTuning)
        val g = gesture
        NativeEngine.nSetControl(NativeEngine.C_VOICES, effectiveVoices(g.voices).toFloat())
        if (!s.gestures) {
            NativeEngine.nSetControl(NativeEngine.C_HEIGHT, s.manualHeight)
            NativeEngine.nSetControl(NativeEngine.C_MUTE, 0f)
            NativeEngine.nSetControl(NativeEngine.C_FX, s.manualFx)
        } else {
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
        applyOverlaySettings()
        handTracker?.enabled = s.gestures || s.handOverlay
        if (s.gestures != old.gestures) interpreter.reset(s.manualVoices, s.manualHeight)
        pushControls()
        applyMonitor()
        if (s.inputGainDb != old.inputGainDb) NativeEngine.nSetInputGainDb(s.inputGainDb)
        if (s.maxVideoQuality != old.maxVideoQuality && started && !recording) {
            val o = owner; val v = previewView; val t = handTracker
            if (o != null && v != null && t != null) { camera.maxQuality = s.maxVideoQuality; camera.bind(o, v, t, overlay) }
        }
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
                trackPos = buf[NativeEngine.M_TRACK_POS],
                trackPlaying = buf[NativeEngine.M_TRACK_PLAYING] > 0.5f,
                trackLength = buf[NativeEngine.M_TRACK_LENGTH],
            )
            meters = m
            smoothLevel += ((if (m.voiced) level else level * 0.4f) - smoothLevel) * 0.3f
            overlay.level = smoothLevel
            overlay.intensity = if (settings.gestures) gesture.fx else settings.manualFx
            delay(33)
        }
    }

    // ───────────────────────── backing track (karaoke)

    fun pickTrack(uri: Uri) {
        if (recording) { message = "Stop recording first"; return }
        trackLoading = true
        trackLoadProgress = 0f
        viewModelScope.launch(Dispatchers.Default) {
            val name = TrackDecoder.displayName(ctx, uri)
            val tmp = File(ctx.cacheDir, "track_decode.wav")
            val ok = try {
                TrackDecoder.decode(ctx, uri, tmp) { p -> viewModelScope.launch(Dispatchers.Main) { trackLoadProgress = p * 0.8f } }
            } catch (e: Exception) {
                Log.e("Studio", "decode", e); false
            }
            if (!ok) {
                withContext(Dispatchers.Main) { trackLoading = false; message = "Couldn't read that audio file" }
                return@launch
            }
            trackFile.parentFile?.mkdirs()
            tmp.copyTo(trackFile, overwrite = true)
            tmp.delete()
            val info = NativeEngine.nLoadTrack(trackFile.absolutePath)
            withContext(Dispatchers.Main) {
                trackLoading = false
                if (info == null) { message = "Couldn't load the track"; return@withContext }
                trackLoaded = true
                val key = info[1].toInt() to (info[2].toInt() == 1)
                trackKey = key
                updateSettings { it.copy(trackName = name) }
                if (settings.trackSetsKey) {
                    updateSettings { it.copy(keyRoot = key.first, minor = key.second) }
                    message = "“$name” loaded · key set to ${Notes.key(key.first, key.second)}"
                } else {
                    message = "“$name” loaded · sounds like ${Notes.key(key.first, key.second)}"
                }
                if (!canMonitor) message = "Track loaded — plug in headphones or your interface to hear it"
            }
        }
    }

    private fun reloadTrack(quiet: Boolean = false) {
        viewModelScope.launch(Dispatchers.Default) {
            val info = NativeEngine.nLoadTrack(trackFile.absolutePath)
            withContext(Dispatchers.Main) {
                trackLoaded = info != null
                if (info != null) trackKey = info[1].toInt() to (info[2].toInt() == 1)
                else if (!quiet) message = "Couldn't reload the backing track"
            }
        }
    }

    fun toggleTrackPlayback() {
        if (!trackLoaded || recording) return
        if (!canMonitor) { message = "Plug in headphones or your interface to play the track (it would leak into the mic)"; return }
        val m = meters
        if (!m.trackPlaying && m.trackLength > 0 && m.trackPos >= m.trackLength - 0.1f) NativeEngine.nSeekTrack(0f)
        NativeEngine.nSetTrackPlaying(!m.trackPlaying)
    }

    fun restartTrack() { NativeEngine.nSeekTrack(0f) }

    fun removeTrack() {
        if (recording) return
        NativeEngine.nUnloadTrack()
        trackFile.delete()
        trackLoaded = false
        trackKey = null
        updateSettings { it.copy(trackName = null) }
    }

    // ───────────────────────── gestures & visuals

    private fun onHands(d: HandTracker.Detection) {
        val s = settings
        val g = interpreter.update(d.mirrored, s.swapHands, s.holdWhenLost, d.uptimeMs)
        if (s.handOverlay) overlay.update(d, g.voiceIndex)
        if (s.gestures) {
            NativeEngine.nSetControl(NativeEngine.C_VOICES, effectiveVoices(g.voices).toFloat())
            NativeEngine.nSetControl(NativeEngine.C_HEIGHT, g.height)
            NativeEngine.nSetControl(NativeEngine.C_MUTE, if (g.mute) 1f else 0f)
            NativeEngine.nSetControl(NativeEngine.C_FX, g.fx)
        }
        viewModelScope.launch(Dispatchers.Main) { gesture = g }
    }

    // ───────────────────────── recording & takes

    fun toggleRecording() { if (recording) stopRecording() else startRecording() }

    private fun startRecording() {
        if (exporting) { message = "Still finishing the last take…"; return }
        if (!NativeEngine.nIsRunning()) { message = "Audio engine isn't running"; return }
        if (trackLoaded && !canMonitor) message = "No headphones — the backing track won't be heard live"
        reviewTake = null
        val dir = File(ctx.cacheDir, "take_${System.currentTimeMillis()}").apply { mkdirs() }
        takeDir = dir
        recordStartNs = System.nanoTime()
        videoStartNs = 0L
        // audio first: it must cover the first video frame (the export trims the excess)
        if (!NativeEngine.nStartRecording(File(dir, "vocal.wav").absolutePath, File(dir, "automation.bin").absolutePath)) {
            message = "Couldn't start recording"
            return
        }
        val done = CompletableDeferred<File?>()
        videoResult = done
        val started = camera.startRecording(
            File(dir, "video.mp4"),
            onStarted = { startNs ->
                // the first recorded frame is the first camera frame at/after the encoder start
                videoStartNs = startNs
                viewModelScope.launch(Dispatchers.Default) {
                    delay(300)
                    val f = overlay.firstFrameAtOrAfter(startNs - 2_000_000L)
                    if (f != 0L && f - startNs < 200_000_000L) videoStartNs = f
                }
            },
            onFinished = { file, err ->
                if (err != null) Log.w("Studio", err)
                done.complete(file)
            },
        )
        if (!started) { message = "Camera not ready — recording audio only"; done.complete(null) }
        recording = true
        if (activeSong != null) lyrics.jumpTo(0)
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        val res = NativeEngine.nStopRecording()
        val audioStartNs = res[1]
        val trackStartNs = if (res.size > 3) res[3] else 0L
        camera.stopRecording()
        val dir = takeDir ?: return
        val s = settings
        val controls = currentControlsArray()
        val output = takesStore.newFile()
        val pendingVideo = videoResult
        viewModelScope.launch(Dispatchers.Default) {
            val video = try { kotlinx.coroutines.withTimeoutOrNull(8000) { pendingVideo?.await() } } catch (_: Exception) { null }
            val vStart = if (videoStartNs != 0L) videoStartNs else recordStartNs
            val rec = ExportPipeline.Recording(
                dryWav = File(dir, "vocal.wav"),
                automation = File(dir, "automation.bin"),
                videoOnly = video,
                audioStartNs = audioStartNs,
                videoStartNs = vStart,
                fallbackControls = controls,
                trackWav = if (trackLoaded) trackFile else null,
                trackStartNs = trackStartNs,
                trackGain = s.trackLevel,
                trackNudgeMs = s.trackNudgeMs,
                output = output,
            )
            export(rec, dir)
        }
    }

    private fun currentControlsArray(): FloatArray {
        val s = settings; val g = gesture
        return floatArrayOf(
            effectiveVoices(g.voices).toFloat(),
            if (s.gestures) g.height else s.manualHeight,
            if (s.gestures && g.mute) 1f else 0f,
            if (s.gestures) g.fx else s.manualFx,
            s.keyRoot.toFloat(), if (s.minor) 1f else 0f, s.harmonyLevel, s.leadLevel,
            s.humanize, s.tightness, s.reverb, s.width,
            s.correction, s.retune, s.doubler, s.echo, if (s.effectsOff) 1f else 0f,
            if (s.chordStyle) NativeEngine.STYLE_CHORD else NativeEngine.STYLE_INTERVALS,
        )
    }

    private suspend fun export(rec: ExportPipeline.Recording, dir: File) {
        withContext(Dispatchers.Main) { exporting = true; exportProgress = 0f; exportLabel = "Rendering your take" }
        val result = try {
            exportPipeline.export(rec) { p, label ->
                viewModelScope.launch(Dispatchers.Main) { exportProgress = p; exportLabel = label }
            }
        } catch (e: Exception) {
            Log.e("Studio", "export", e)
            ExportPipeline.Result(null, e.message)
        }
        dir.deleteRecursively()
        withContext(Dispatchers.Main) {
            exporting = false
            takes = takesStore.list()
            if (result.error != null) message = result.error
            else reviewTake = takes.firstOrNull { it.file == result.file }
        }
    }

    fun saveTake(take: Take) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = takesStore.saveToGallery(take)
            withContext(Dispatchers.Main) {
                takes = takesStore.list()
                if (reviewTake?.file == take.file) reviewTake = takes.firstOrNull { it.file == take.file }
                message = if (uri != null) "Saved to Gallery › Movies/HarmonyCam" else "Couldn't save to Gallery"
            }
        }
    }

    fun deleteTake(take: Take) {
        takesStore.delete(take)
        takes = takesStore.list()
        if (reviewTake?.file == take.file) reviewTake = null
    }

    /** Discard the take under review and get ready to record again. */
    fun retake() {
        reviewTake?.let { deleteTake(it) }
        reviewTake = null
        if (trackLoaded) restartTrack()
        if (activeSong != null) lyrics.jumpTo(0)
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
}
