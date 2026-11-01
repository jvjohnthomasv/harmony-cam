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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs MediaPipe hand landmark detection on camera frames (live-stream mode) and publishes
 * landmarks in upright, mirrored (selfie) image coordinates.
 */
class HandTracker(context: Context, private val onHands: (List<FloatArray>, Long) -> Unit) : ImageAnalysis.Analyzer {

    private var landmarker: HandLandmarker? = null
    private val busy = AtomicBoolean(false)
    @Volatile var enabled = true

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
        val hands = ArrayList<FloatArray>(2)
        for (hand in result.landmarks()) {
            val arr = FloatArray(42)
            for (i in 0 until minOf(21, hand.size)) {
                arr[2 * i] = 1f - hand[i].x()   // mirror to match the selfie preview
                arr[2 * i + 1] = hand[i].y()
            }
            hands.add(arr)
        }
        onHands(hands, SystemClock.uptimeMillis())
    }

    override fun analyze(image: ImageProxy) {
        val lm = landmarker
        if (lm == null || !enabled || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val rotation = image.imageInfo.rotationDegrees
            val raw = image.toBitmap()
            // rotate to upright so landmark coordinates match the upright preview
            val upright = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } else raw
            val mpImage = BitmapImageBuilder(upright).build()
            lm.detectAsync(mpImage, SystemClock.uptimeMillis())
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
