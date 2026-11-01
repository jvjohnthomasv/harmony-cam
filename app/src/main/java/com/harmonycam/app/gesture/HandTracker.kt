package com.harmonycam.app.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaPipe hand landmarks on the analysis stream.
 *
 * Publishes, per detection:
 *  - `upright`: landmarks normalised in the upright, NON-mirrored image (for drawing)
 *  - `mirrored`: the same, mirrored like a selfie (for left/right gesture logic)
 *  - a [FrameMapping] to convert upright-normalised points into camera sensor coordinates
 *  - the camera timestamp of the analysed frame (same clock as the preview/video frames)
 */
class HandTracker(context: Context, private val onHands: (Detection) -> Unit) : ImageAnalysis.Analyzer {

    /** Maps upright-normalised (x, y) → camera sensor coordinates. */
    class FrameMapping(private val rotation: Int, private val width: Int, private val height: Int, sensorToBuffer: Matrix) {
        private val bufferToSensor = Matrix().also { sensorToBuffer.invert(it) }
        val uprightAspect: Float = if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width

        fun toSensor(x: Float, y: Float, out: FloatArray, offset: Int) {
            val uw = if (rotation % 180 == 0) width else height
            val uh = if (rotation % 180 == 0) height else width
            val X = x * uw; val Y = y * uh
            val (bx, by) = when (rotation) {
                90 -> Y to (height - X)
                180 -> (width - X) to (height - Y)
                270 -> (width - Y) to X
                else -> X to Y
            }
            out[offset] = bx; out[offset + 1] = by
            bufferToSensor.mapPoints(out, offset, out, offset, 1)
        }
    }

    class Detection(
        val upright: List<FloatArray>,
        val mirrored: List<FloatArray>,
        val mapping: FrameMapping,
        val cameraTimestampNs: Long,
        val uptimeMs: Long,
    )

    private var landmarker: HandLandmarker? = null
    private val busy = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<Long, Pair<FrameMapping, Long>>()
    @Volatile var enabled = true
    private var lastMpTs = 0L

    init {
        landmarker = create(context, Delegate.GPU) ?: create(context, Delegate.CPU)
        if (landmarker == null) Log.e(TAG, "Hand tracking unavailable")
    }

    private fun create(context: Context, delegate: Delegate): HandLandmarker? = try {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result: HandLandmarkerResult, _ -> deliver(result) }
            .setErrorListener { e: RuntimeException -> Log.w(TAG, "landmarker error", e); busy.set(false) }
            .build()
        HandLandmarker.createFromOptions(context, options)
    } catch (t: Throwable) {
        Log.w(TAG, "HandLandmarker ($delegate) failed", t)
        null
    }

    private fun deliver(result: HandLandmarkerResult) {
        busy.set(false)
        val info = pending.remove(result.timestampMs()) ?: return
        val upright = ArrayList<FloatArray>(2)
        val mirrored = ArrayList<FloatArray>(2)
        for (hand in result.landmarks()) {
            val u = FloatArray(42)
            val m = FloatArray(42)
            for (i in 0 until minOf(21, hand.size)) {
                u[2 * i] = hand[i].x(); u[2 * i + 1] = hand[i].y()
                m[2 * i] = 1f - hand[i].x(); m[2 * i + 1] = hand[i].y()
            }
            upright.add(u); mirrored.add(m)
        }
        onHands(Detection(upright, mirrored, info.first, info.second, SystemClock.uptimeMillis()))
    }

    override fun analyze(image: ImageProxy) {
        val lm = landmarker
        if (lm == null || !enabled || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val info = image.imageInfo
            val rotation = info.rotationDegrees
            val mapping = FrameMapping(rotation, image.width, image.height, info.sensorToBufferTransformMatrix)
            val raw = image.toBitmap()
            val upright = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } else raw
            var ts = SystemClock.uptimeMillis()
            if (ts <= lastMpTs) ts = lastMpTs + 1
            lastMpTs = ts
            pending[ts] = mapping to info.timestamp
            if (pending.size > 8) pending.keys.sorted().take(pending.size - 8).forEach { pending.remove(it) }
            lm.detectAsync(BitmapImageBuilder(upright).build(), ts)
        } catch (t: Throwable) {
            Log.w(TAG, "analyze failed", t)
            busy.set(false)
        } finally {
            image.close()
        }
    }

    fun close() {
        try { landmarker?.close() } catch (_: Throwable) {}
        landmarker = null
    }

    private companion object { const val TAG = "HandTracker" }
}
