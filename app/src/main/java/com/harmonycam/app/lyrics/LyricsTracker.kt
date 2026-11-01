package com.harmonycam.app.lyrics

import android.content.Context
import android.util.Log
import com.harmonycam.app.audio.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Offline lyric following. Uses the engine's own mic stream (decimated to 16 kHz), so it never
 * fights the recorder for the microphone, and runs Vosk with a grammar limited to the song's
 * words, which makes recognition of *sung* words far more reliable.
 */
class LyricsTracker(private val context: Context, private val scope: CoroutineScope) {

    enum class Status { IDLE, LOADING_MODEL, LISTENING, ERROR }

    var onUpdate: ((line: Int, wordProgress: Int) -> Unit)? = null
    var onStatus: ((Status, String?) -> Unit)? = null

    private var model: Model? = null
    private var job: Job? = null
    @Volatile private var matcher: LyricMatcher? = null

    val currentMatcher get() = matcher

    fun start(lines: List<String>, startLine: Int = 0) {
        stop()
        val m = LyricMatcher(lines).also { it.reset(startLine) }
        matcher = m
        if (m.lines.isEmpty()) return
        job = scope.launch(Dispatchers.Default) {
            try {
                val mdl = model ?: run {
                    onStatus?.invoke(Status.LOADING_MODEL, null)
                    val path = withContext(Dispatchers.IO) { unpackModel() }
                    Model(path).also { model = it }
                }
                val grammar = JSONArray().apply {
                    m.vocabulary.forEach { put(it) }
                    put("[unk]")
                }.toString()
                val rec = try { Recognizer(mdl, 16000f, grammar) } catch (e: Exception) {
                    Log.w(TAG, "grammar recogniser failed, using open vocabulary", e)
                    Recognizer(mdl, 16000f)
                }
                NativeEngine.nSetAsrEnabled(true)
                onStatus?.invoke(Status.LISTENING, null)
                val buf = ShortArray(1600)
                rec.use { r ->
                    while (isActive) {
                        val n = NativeEngine.nReadAsr(buf)
                        if (n <= 0) { delay(30); continue }
                        val isFinal = r.acceptWaveForm(buf, n)
                        val json = if (isFinal) r.result else r.partialResult
                        val text = JSONObject(json).optString(if (isFinal) "text" else "partial")
                        val (changed, line, words) = synchronized(m) {
                            val c = if (isFinal) m.onFinal(text) else m.onPartial(text)
                            Triple(c, m.currentLine, m.wordProgress)
                        }
                        if (changed) withContext(Dispatchers.Main) { onUpdate?.invoke(line, words) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "lyrics tracking failed", e)
                onStatus?.invoke(Status.ERROR, e.message)
            } finally {
                NativeEngine.nSetAsrEnabled(false)
            }
        }
    }

    /** Manual override (tap next / previous line). */
    fun jumpTo(line: Int) {
        val m = matcher ?: return
        val (l, w) = synchronized(m) { m.jumpTo(line); m.currentLine to m.wordProgress }
        onUpdate?.invoke(l, w)
    }

    fun stop() {
        job?.cancel()
        job = null
        onStatus?.invoke(Status.IDLE, null)
    }

    fun release() {
        stop()
        try { model?.close() } catch (_: Throwable) {}
        model = null
    }

    /** Copies the model from assets to app storage once (Vosk needs real files). */
    private fun unpackModel(): String {
        val target = File(context.filesDir, "vosk-model-en-us")
        val assetUuid = context.assets.open("$ASSET_DIR/uuid").bufferedReader().readLine()
        val stamp = File(target, "uuid")
        if (stamp.exists() && stamp.readText().trim() == assetUuid.trim()) return target.absolutePath
        target.deleteRecursively()
        copyAssetDir(ASSET_DIR, target)
        return target.absolutePath
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssetDir("$assetPath/$child", File(dest, child))
    }

    private companion object {
        const val TAG = "LyricsTracker"
        const val ASSET_DIR = "model-en-us"
    }
}
