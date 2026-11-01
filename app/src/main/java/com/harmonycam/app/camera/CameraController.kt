package com.harmonycam.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors

/**
 * Front camera through CameraX's own pipeline (no custom scaling): Preview, VideoCapture at the
 * highest quality the camera supports, ImageAnalysis for hand tracking, and an OverlayEffect that
 * draws the hand overlay into both the preview and the recorded video.
 */
class CameraController(private val context: Context) {

    data class ZoomInfo(val min: Float, val max: Float, val current: Float)

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val overlayThread = HandlerThread("HandOverlay").apply { start() }
    private val overlayHandler = Handler(overlayThread.looper)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var overlayEffect: OverlayEffect? = null
    private var recording: Recording? = null

    var onZoomChanged: ((ZoomInfo) -> Unit)? = null
    /** true: best quality the camera offers (4K if available); false: 1080p (lighter, smoother). */
    @Volatile var maxQuality = true
    var videoQualityLabel: String = ""
        private set

    fun bind(owner: LifecycleOwner, previewView: PreviewView, analyzer: ImageAnalysis.Analyzer, overlay: HandOverlay) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = try { future.get() } catch (e: Exception) { Log.e(TAG, "provider", e); return@addListener }
            provider = p
            // try the best quality first; fall back if the device can't combine it with the overlay
            val attempts = (if (maxQuality) listOf(listOf(Quality.UHD, Quality.FHD, Quality.HD)) else emptyList()) + listOf(
                listOf(Quality.FHD, Quality.HD),
                listOf(Quality.HD, Quality.SD),
            )
            for (qualities in attempts) {
                try {
                    bindWith(p, owner, previewView, analyzer, overlay, qualities)
                    return@addListener
                } catch (e: Exception) {
                    Log.w(TAG, "bind with $qualities failed", e)
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindWith(
        p: ProcessCameraProvider, owner: LifecycleOwner, previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer, overlay: HandOverlay, qualities: List<Quality>,
    ) {
        p.unbindAll()
        val selector = CameraSelector.DEFAULT_FRONT_CAMERA
        val cameraInfo = p.getCameraInfo(selector)
        val supported = Recorder.getVideoCapabilities(cameraInfo).getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
        val wanted = qualities.filter { it in supported }.ifEmpty { qualities }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.fromOrderedList(wanted, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        val video = VideoCapture.Builder(recorder)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .build()

        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
        val preview = Preview.Builder().setResolutionSelector(previewSelector).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(analysisExecutor, analyzer)

        overlayEffect?.close()
        val effect = OverlayEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, 0, overlayHandler) { t ->
            Log.e(TAG, "overlay effect error", t)
        }
        effect.setOnDrawListener { frame -> overlay.draw(frame) }
        overlayEffect = effect

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(video)
            .addUseCase(analysis)
            .addEffect(effect)
            .build()
        val cam = p.bindToLifecycle(owner, selector, group)
        camera = cam
        videoCapture = video
        videoQualityLabel = wanted.firstOrNull()?.let { qualityName(it) } ?: ""
        cam.cameraInfo.zoomState.observe(owner) { z ->
            onZoomChanged?.invoke(ZoomInfo(z.minZoomRatio, z.maxZoomRatio, z.zoomRatio))
        }
    }

    private fun qualityName(q: Quality) = when (q) {
        Quality.UHD -> "4K"; Quality.FHD -> "1080p"; Quality.HD -> "720p"; else -> "SD"
    }

    fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val z = cam.cameraInfo.zoomState.value
        val r = if (z != null) ratio.coerceIn(z.minZoomRatio, z.maxZoomRatio) else ratio
        cam.cameraControl.setZoomRatio(r)
    }

    fun zoomBy(factor: Float) {
        val z = camera?.cameraInfo?.zoomState?.value ?: return
        setZoom(z.zoomRatio * factor)
    }

    /** Starts a video-only recording (audio is produced by the harmony engine). */
    @SuppressLint("MissingPermission")
    fun startRecording(file: File, onStarted: (startNs: Long) -> Unit, onFinished: (File?, String?) -> Unit): Boolean {
        val vc = videoCapture ?: return false
        recording?.stop()
        val options = FileOutputOptions.Builder(file).build()
        // Events are delivered directly on CameraX's recorder thread: the Start event fires right
        // after the encoder's start time is taken, so System.nanoTime() here is within ~1 ms of it.
        recording = vc.output.prepareRecording(context, options)
            .start({ r -> r.run() }) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> onStarted(System.nanoTime())
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE && event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                            onFinished(if (file.exists() && file.length() > 0) file else null, "video error ${event.error}")
                        } else onFinished(file, null)
                    }
                }
            }
        return true
    }

    fun stopRecording() {
        recording?.stop()
    }

    fun unbind() {
        recording?.stop()
        provider?.unbindAll()
        camera = null
    }

    fun shutdown() {
        unbind()
        overlayEffect?.close()
        analysisExecutor.shutdown()
        overlayThread.quitSafely()
    }

    private companion object { const val TAG = "CameraController" }
}
