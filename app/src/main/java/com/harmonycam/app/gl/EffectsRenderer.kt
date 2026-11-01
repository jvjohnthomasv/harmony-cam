package com.harmonycam.app.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import com.harmonycam.app.record.VideoEncoder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Renders the camera with the effect stack into a 1080×1920 frame, shows it on screen and,
 * while recording, draws the same frame into the video encoder's surface.
 * Anything drawn by Compose on top of the GLSurfaceView (lyrics, HUD) is never recorded.
 */
class EffectsRenderer(private val view: GLSurfaceView) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "EffectsRenderer"
        const val FRAME_W = 1080
        const val FRAME_H = 1920
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /** EGL config that the video encoder can also use (EGL_RECORDABLE_ANDROID). */
        fun configChooser() = GLSurfaceView.EGLConfigChooser { egl, display -> chooseConfig(egl, display) }

        private fun chooseConfig(egl: EGL10, display: EGLDisplay): javax.microedition.khronos.egl.EGLConfig {
            val withRecordable = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 0, EGL10.EGL_RENDERABLE_TYPE, 4 /* EGL_OPENGL_ES2_BIT */,
                EGL_RECORDABLE_ANDROID, 1, EGL10.EGL_NONE
            )
            val plain = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE
            )
            for (attrs in listOf(withRecordable, plain)) {
                val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
                val num = IntArray(1)
                if (egl.eglChooseConfig(display, attrs, configs, 1, num) && num[0] > 0 && configs[0] != null) return configs[0]!!
            }
            throw IllegalStateException("No suitable EGL config")
        }
    }

    // ── camera
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    @Volatile private var frameAvailable = false
    private val surfaceRequests = ConcurrentLinkedQueue<(SurfaceTexture) -> Unit>()
    @Volatile var cameraRotation = 270
    @Volatile var bufferWidth = 1440
    @Volatile var bufferHeight = 1080
    @Volatile var extraRotation = 0
    @Volatile var mirror = true
    private var timestampOffsetNs: Long? = null
    private var lastCameraNs = 0L

    // ── look
    @Volatile var style: VisualStyle = VisualStyle.NEON
    @Volatile var inputProvider: ((VisualInput) -> Unit)? = null
    @Volatile var onSurfaceTextureRecreated: (() -> Unit)? = null
    private val input = VisualInput()
    private val particles = ParticleSystem()
    private val startMs = SystemClock.uptimeMillis()

    // ── GL objects
    private var quad: FloatBuffer = GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private var pointBuf: FloatBuffer = GlUtil.floatBuffer(particles.data.size)
    private var progCamera = 0
    private var progPoints = 0
    private var progBlur = 0
    private var progComposite = 0
    private var progBlit = 0
    private var scene: Fbo? = null
    private var glowA: Fbo? = null
    private var glowB: Fbo? = null
    private var output: Fbo? = null
    private var screenW = 1
    private var screenH = 1

    // ── recording
    private var encoder: VideoEncoder? = null
    private var encSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var recordStartNs = 0L
    private var lastPtsNs = -1L

    /** Runs [cb] on the GL thread with the camera SurfaceTexture once it exists. */
    fun withSurfaceTexture(cb: (SurfaceTexture) -> Unit) {
        surfaceRequests.add(cb)
        view.queueEvent { drainSurfaceRequests() }
    }

    private fun drainSurfaceRequests() {
        val st = surfaceTexture ?: return
        while (true) {
            val r = surfaceRequests.poll() ?: break
            r(st)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        progCamera = GlUtil.program(Shaders.QUAD_VS, Shaders.CAMERA_FS)
        progPoints = GlUtil.program(Shaders.POINT_VS, Shaders.POINT_FS)
        progBlur = GlUtil.program(Shaders.QUAD_VS, Shaders.BLUR_FS)
        progComposite = GlUtil.program(Shaders.QUAD_VS, Shaders.COMPOSITE_FS)
        progBlit = GlUtil.program(Shaders.QUAD_VS, Shaders.BLIT_FS)
        oesTex = GlUtil.oesTexture()
        val recreated = surfaceTexture != null
        surfaceTexture?.release()
        surfaceTexture = SurfaceTexture(oesTex).apply {
            setOnFrameAvailableListener { frameAvailable = true }
        }
        scene = Fbo(FRAME_W, FRAME_H)
        glowA = Fbo(FRAME_W / 4, FRAME_H / 4)
        glowB = Fbo(FRAME_W / 4, FRAME_H / 4)
        output = Fbo(FRAME_W, FRAME_H)
        drainSurfaceRequests()
        // GL context was lost (e.g. app backgrounded): the camera must be re-attached to the new texture
        if (recreated) view.post { onSurfaceTextureRecreated?.invoke() }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenW = width
        screenH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        drainSurfaceRequests()
        val st = surfaceTexture ?: return
        var newFrame = false
        if (frameAvailable) {
            frameAvailable = false
            try {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                lastCameraNs = toMonotonic(st.timestamp)
                newFrame = true
            } catch (e: Exception) {
                Log.w(TAG, "updateTexImage", e)
            }
        }

        val time = (SystemClock.uptimeMillis() - startMs) / 1000f
        input.time = time
        inputProvider?.invoke(input)
        val s = style
        particles.update(input, s, FRAME_H)

        val sceneFbo = scene ?: return
        val glow1 = glowA ?: return
        val glow2 = glowB ?: return
        val finalFbo = output ?: return

        // 1) camera → graded scene
        sceneFbo.bind()
        drawCamera(time, s)

        // 2) particles: sharp into the scene, and into the low-res glow buffer
        if (particles.count > 0) {
            uploadPoints()
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            drawPoints(1f)
            glow1.bind()
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawPoints(0.25f)
            GLES20.glDisable(GLES20.GL_BLEND)
        } else {
            glow1.bind()
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        // 3) bloom blur
        blur(glow1, glow2, 1f / glow1.width, 0f)
        blur(glow2, glow1, 0f, 1f / glow1.height)
        // 4) composite
        finalFbo.bind()
        composite(sceneFbo, glow1, time, s)

        // 5) screen (centre-crop to the display aspect)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, screenW, screenH)
        val frameAspect = FRAME_W.toFloat() / FRAME_H
        val screenAspect = screenW.toFloat() / screenH
        if (screenAspect < frameAspect) {
            val sx = screenAspect / frameAspect
            blit(finalFbo.texture, sx, 1f, (1f - sx) / 2f, 0f)
        } else {
            val sy = frameAspect / screenAspect
            blit(finalFbo.texture, 1f, sy, 0f, (1f - sy) / 2f)
        }

        // 6) recording
        if (newFrame) encodeFrame(finalFbo)
    }

    private fun toMonotonic(ts: Long): Long {
        val off = timestampOffsetNs ?: run {
            val mono = System.nanoTime()
            val boot = SystemClock.elapsedRealtimeNanos()
            val o = if (abs(ts - boot) < abs(ts - mono)) mono - boot else 0L
            timestampOffsetNs = o
            o
        }
        return if (ts == 0L) System.nanoTime() else ts + off
    }

    // ───────────── passes

    private fun bindQuad(program: Int) {
        val loc = GLES20.glGetAttribLocation(program, "aPos")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
    }

    private fun drawCamera(time: Float, s: VisualStyle) {
        GLES20.glUseProgram(progCamera)
        bindQuad(progCamera)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progCamera, "uTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progCamera, "uStMatrix"), 1, false, stMatrix, 0)
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(progCamera, "uUvMap"), 1, false, uvMap(), 0)
        u1("uTime", progCamera, time % 1000f)
        u1("uLevel", progCamera, input.level)
        u1("uFx", progCamera, input.fx)
        u1("uChroma", progCamera, s.chroma)
        u1("uSat", progCamera, s.saturation)
        u1("uContrast", progCamera, s.contrast)
        u1("uVignette", progCamera, s.vignette)
        u1("uGrade", progCamera, s.grade)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(progCamera, "uShadow"), 1, s.shadow, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(progCamera, "uHighlight"), 1, s.highlight, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun uploadPoints() {
        pointBuf.position(0)
        pointBuf.put(particles.data, 0, particles.count * ParticleSystem.STRIDE)
        pointBuf.position(0)
    }

    private fun drawPoints(sizeScale: Float) {
        GLES20.glUseProgram(progPoints)
        val stride = ParticleSystem.STRIDE * 4
        val aPos = GLES20.glGetAttribLocation(progPoints, "aPos")
        val aSize = GLES20.glGetAttribLocation(progPoints, "aSize")
        val aColor = GLES20.glGetAttribLocation(progPoints, "aColor")
        // gl_PointSize must be scaled for the smaller glow target; scale on the CPU-side copy
        if (sizeScale != 1f) {
            for (i in 0 until particles.count) {
                val o = i * ParticleSystem.STRIDE + 2
                pointBuf.put(o, particles.data[o] * sizeScale * 1.6f)
            }
        }
        pointBuf.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, pointBuf)
        pointBuf.position(2)
        GLES20.glEnableVertexAttribArray(aSize)
        GLES20.glVertexAttribPointer(aSize, 1, GLES20.GL_FLOAT, false, stride, pointBuf)
        pointBuf.position(3)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, stride, pointBuf)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, particles.count)
        GLES20.glDisableVertexAttribArray(aSize)
        GLES20.glDisableVertexAttribArray(aColor)
        pointBuf.position(0)
    }

    private fun blur(src: Fbo, dst: Fbo, dx: Float, dy: Float) {
        dst.bind()
        GLES20.glUseProgram(progBlur)
        bindQuad(progBlur)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progBlur, "uTex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progBlur, "uDir"), dx * 1.5f, dy * 1.5f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun composite(sceneFbo: Fbo, glow: Fbo, time: Float, s: VisualStyle) {
        GLES20.glUseProgram(progComposite)
        bindQuad(progComposite)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneFbo.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progComposite, "uScene"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glow.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progComposite, "uGlow"), 1)
        u1("uGlowAmt", progComposite, s.glow * (0.7f + 0.6f * input.fx))
        u1("uTime", progComposite, time % 1000f)
        u1("uPulse", progComposite, input.pulse)
        u1("uLeak", progComposite, if (s == VisualStyle.CLEAN) 0f else 0.35f + 0.65f * input.fx)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(progComposite, "uLeakColor"), 1, s.leak, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun blit(tex: Int, sx: Float, sy: Float, ox: Float, oy: Float) {
        GLES20.glUseProgram(progBlit)
        bindQuad(progBlit)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progBlit, "uTex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progBlit, "uScale"), sx, sy)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progBlit, "uOffset"), ox, oy)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun u1(name: String, program: Int, v: Float) =
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, name), v)

    // ───────────── geometry

    private fun totalRotation() = ((cameraRotation + extraRotation) % 360 + 360) % 360

    /** Aspect (w/h) of the upright camera image. */
    private fun uprightAspect(): Float {
        val r = totalRotation()
        return if (r % 180 == 0) bufferWidth.toFloat() / bufferHeight else bufferHeight.toFloat() / bufferWidth
    }

    /**
     * Column-major 3×3 matrix mapping frame UV (GL, origin bottom-left) to camera-buffer UV
     * (before the SurfaceTexture transform): centre-crop → mirror → undo sensor rotation.
     */
    private fun uvMap(): FloatArray {
        val imgA = uprightAspect()
        val frameA = FRAME_W.toFloat() / FRAME_H
        // crop
        var sx = 1f; var sy = 1f
        if (imgA > frameA) sx = frameA / imgA else sy = imgA / frameA
        val crop = mat(sx, 0f, 0.5f - 0.5f * sx, 0f, sy, 0.5f - 0.5f * sy)
        val mir = if (mirror) mat(-1f, 0f, 1f, 0f, 1f, 0f) else mat(1f, 0f, 0f, 0f, 1f, 0f)
        val rot = when (totalRotation()) {
            90 -> mat(0f, -1f, 1f, 1f, 0f, 0f)    // (u,v) → (1-v, u)
            180 -> mat(-1f, 0f, 1f, 0f, -1f, 1f)  // (u,v) → (1-u, 1-v)
            270 -> mat(0f, 1f, 0f, -1f, 0f, 1f)   // (u,v) → (v, 1-u)
            else -> mat(1f, 0f, 0f, 0f, 1f, 0f)
        }
        return toColumnMajor(mul(rot, mul(mir, crop)))
    }

    /** Maps a mirrored, upright-image landmark (y down) to frame space (y down). */
    fun mapToFrame(x: Float, y: Float): Pair<Float, Float> {
        val imgA = uprightAspect()
        val frameA = FRAME_W.toFloat() / FRAME_H
        var fx = x; var fy = y
        if (imgA > frameA) fx = 0.5f + (x - 0.5f) * (imgA / frameA) else fy = 0.5f + (y - 0.5f) * (frameA / imgA)
        if (!mirror) fx = 1f - fx
        return fx to fy
    }

    // row-major affine 2D: [a b c; d e f; 0 0 1]
    private fun mat(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) = floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f)
    private fun mul(m: FloatArray, n: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0f
            for (k in 0 until 3) s += m[i * 3 + k] * n[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }
    private fun toColumnMajor(m: FloatArray) = floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

    // ───────────── recording

    fun startRecording(enc: VideoEncoder, startNs: Long) {
        view.queueEvent {
            try {
                val display = EGL14.eglGetCurrentDisplay()
                val ctx = EGL14.eglGetCurrentContext()
                val cfgId = IntArray(1)
                EGL14.eglQueryContext(display, ctx, EGL14.EGL_CONFIG_ID, cfgId, 0)
                val configs = arrayOfNulls<EGLConfig>(1)
                val num = IntArray(1)
                EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, cfgId[0], EGL14.EGL_NONE), 0, configs, 0, 1, num, 0)
                encSurface = EGL14.eglCreateWindowSurface(display, configs[0], enc.inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
                if (encSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface failed 0x${Integer.toHexString(EGL14.eglGetError())}")
                encoder = enc
                recordStartNs = startNs
                lastPtsNs = -1L
            } catch (e: Exception) {
                Log.e(TAG, "cannot attach encoder", e)
                encoder = null
                encSurface = EGL14.EGL_NO_SURFACE
            }
        }
    }

    /** Detaches the encoder on the GL thread, then calls [done] (on the GL thread). */
    fun stopRecording(done: () -> Unit) {
        view.queueEvent {
            if (encSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), encSurface)
            }
            encSurface = EGL14.EGL_NO_SURFACE
            encoder = null
            done()
        }
    }

    val isRecordingAttached get() = encoder != null

    private fun encodeFrame(src: Fbo) {
        val enc = encoder ?: return
        if (encSurface == EGL14.EGL_NO_SURFACE) return
        val pts = lastCameraNs - recordStartNs
        if (pts < 0 || pts <= lastPtsNs) return
        lastPtsNs = pts
        val display = EGL14.eglGetCurrentDisplay()
        val draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val ctx = EGL14.eglGetCurrentContext()
        if (!EGL14.eglMakeCurrent(display, encSurface, encSurface, ctx)) return
        GLES20.glViewport(0, 0, enc.width, enc.height)
        blit(src.texture, 1f, 1f, 0f, 0f)
        EGLExt.eglPresentationTimeANDROID(display, encSurface, pts)
        EGL14.eglSwapBuffers(display, encSurface)
        EGL14.eglMakeCurrent(display, draw, read, ctx)
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
    }
}
