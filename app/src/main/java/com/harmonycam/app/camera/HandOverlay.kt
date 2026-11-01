package com.harmonycam.app.camera

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import androidx.camera.effects.Frame
import com.harmonycam.app.gesture.HandTracker
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the reel-style hand overlay into the camera frames (preview AND recorded video):
 * MediaPipe-style green bones + red joints on every hand, and a glowing swirl orb with a sparkle
 * stream at the thumb tip of the raised (voice) hand.
 *
 * Landmarks are smoothed with a One-Euro filter and extrapolated to each frame's timestamp, so the
 * skeleton stays glued to the hand despite detection running a frame or two behind the camera.
 */
class HandOverlay {

    @Volatile var enabled = true
    @Volatile var orbEnabled = true
    /** 0..1 audio level → orb pulse. */
    @Volatile var level = 0f
    /** 0..1 effect intensity → sparkle density. */
    @Volatile var intensity = 0.4f

    /** Called once with the camera timestamp (ns, converted to System.nanoTime base) of the first frame after [armFirstFrame]. */
    @Volatile private var firstFrameCallback: ((Long) -> Unit)? = null
    fun armFirstFrame(cb: (Long) -> Unit) { firstFrameCallback = cb }

    // ── latest detection (written by the analysis thread)
    @Volatile private var detection: HandTracker.Detection? = null
    fun update(d: HandTracker.Detection) { detection = d; filterHands(d) }

    private val lock = Any()
    private val filters = Array(2) { Array(42) { OneEuro(1.6, 0.4, 1.0) } }
    private var filteredHands: List<FloatArray> = emptyList()   // upright normalised, filtered
    private var velocities: List<FloatArray> = emptyList()
    private var filteredTs = 0L
    private var voiceIndex = -1

    private fun filterHands(d: HandTracker.Detection) {
        synchronized(lock) {
            val t = d.cameraTimestampNs / 1e9
            // keep hand identity stable by ordering hands left→right
            val hands = d.upright.sortedBy { centerX(it) }
            val outs = ArrayList<FloatArray>(hands.size)
            val vels = ArrayList<FloatArray>(hands.size)
            for ((h, src) in hands.withIndex().take(2)) {
                val o = FloatArray(42); val v = FloatArray(42)
                for (i in 0 until 42) {
                    o[i] = filters[h][i].filter(src[i].toDouble(), t).toFloat()
                    v[i] = filters[h][i].velocity.toFloat()
                }
                outs.add(o); vels.add(v)
            }
            if (hands.size < (filteredHands.size)) for (h in hands.size until 2) filters[h].forEach { it.reset() }
            filteredHands = outs
            velocities = vels
            filteredTs = d.cameraTimestampNs
            // the raised hand (highest palm) is the voice hand → gets the orb
            voiceIndex = outs.indices.minByOrNull { palmY(outs[it]) } ?: -1
        }
    }

    // ── paints
    private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 230, 0); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val joint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(235, 0, 0); style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var age: Float, val life: Float, val size: Float)
    private val particles = ArrayList<Particle>()
    private val rnd = Random(3)
    private var lastFrameNs = 0L
    private var emitCarry = 0f
    private val swirl = FloatArray(48) { rnd.nextFloat() * 6.283f }
    private val swirlR = FloatArray(48) { 0.85f + 0.8f * rnd.nextFloat() }
    private val swirlS = FloatArray(48) { 1.5f + 3.5f * rnd.nextFloat() }
    private var clockOffset: Long? = null

    private val pts = FloatArray(42)
    private val streamDir = floatArrayOf(-1f, 0f)
    private val upDir = floatArrayOf(0f, -1f)
    private fun normalize(v: FloatArray) {
        val l = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1])
        if (l > 1e-6f) { v[0] /= l; v[1] /= l }
    }

    /** OverlayEffect draw callback (overlay handler thread). */
    fun draw(frame: Frame): Boolean {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val frameNs = frame.timestampNanos
        firstFrameCallback?.let { cb -> firstFrameCallback = null; cb(toNanoTime(frameNs)) }
        if (!enabled) return true

        val dt = if (lastFrameNs == 0L) 1f / 30f else ((frameNs - lastFrameNs) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNs = frameNs
        val det = detection ?: return true
        val m: Matrix = frame.sensorToBufferTransform
        val size = min(frame.size.width, frame.size.height).toFloat()

        val hands: List<FloatArray>; val vels: List<FloatArray>; val ts: Long; val vIdx: Int
        synchronized(lock) { hands = filteredHands; vels = velocities; ts = filteredTs; vIdx = voiceIndex }
        // hide stale hands (> 250 ms without detection)
        if (abs(frameNs - ts) > 250_000_000L) { stepParticles(dt, null, size); drawParticles(canvas, size); return true }
        val lead = ((frameNs - ts) / 1e9f).coerceIn(0f, 0.08f)

        bone.strokeWidth = size * 0.0042f
        val jointR = size * 0.0062f
        var thumb: FloatArray? = null
        for ((h, hand) in hands.withIndex()) {
            val v = vels.getOrNull(h)
            for (i in 0 until 21) {
                val x = hand[2 * i] + (v?.get(2 * i) ?: 0f) * lead
                val y = hand[2 * i + 1] + (v?.get(2 * i + 1) ?: 0f) * lead
                det.mapping.toSensor(x, y, pts, 2 * i)
            }
            m.mapPoints(pts)
            for ((a, b) in CONNECTIONS) canvas.drawLine(pts[2 * a], pts[2 * a + 1], pts[2 * b], pts[2 * b + 1], bone)
            for (i in 0 until 21) canvas.drawCircle(pts[2 * i], pts[2 * i + 1], jointR, joint)
            if (h == vIdx) {
                thumb = floatArrayOf(pts[8], pts[9])
                // on-screen directions at the thumb, expressed in buffer pixels:
                // "towards the middle of the frame" (where the reel's sparkle stream flows) and "up"
                val tx = hand[8] + (v?.get(8) ?: 0f) * lead
                val ty = hand[9] + (v?.get(9) ?: 0f) * lead
                val toward = if (tx > 0.5f) -0.1f else 0.1f
                val d = FloatArray(4)
                det.mapping.toSensor(tx + toward, ty, d, 0)
                det.mapping.toSensor(tx, ty - 0.1f, d, 2)
                m.mapPoints(d)
                streamDir[0] = d[0] - pts[8]; streamDir[1] = d[1] - pts[9]
                upDir[0] = d[2] - pts[8]; upDir[1] = d[3] - pts[9]
                normalize(streamDir); normalize(upDir)
            }
        }

        stepParticles(dt, if (orbEnabled) thumb else null, size)
        if (orbEnabled && thumb != null) drawOrb(canvas, thumb[0], thumb[1], size, frameNs / 1e9f)
        drawParticles(canvas, size)
        return true
    }

    private fun drawOrb(canvas: android.graphics.Canvas, x: Float, y: Float, size: Float, t: Float) {
        val lv = level.coerceIn(0f, 1f)
        val r = size * (0.030f + 0.012f * lv)
        glow.shader = RadialGradient(
            x, y, r * 2.2f,
            intArrayOf(Color.argb(220, 255, 255, 255), Color.argb(150, 190, 245, 255), Color.argb(60, 140, 220, 255), Color.argb(0, 120, 200, 255)),
            floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, r * 2.2f, glow)
        ring.color = Color.argb(235, 255, 255, 255); ring.strokeWidth = r * 0.22f
        canvas.drawCircle(x, y, r, ring)
        ring.color = Color.argb(140, 170, 240, 255); ring.strokeWidth = r * 0.10f
        canvas.drawCircle(x, y, r * 1.28f, ring)
        // swirling specks around the ring
        for (i in swirl.indices) {
            val a = swirl[i] + t * swirlS[i]
            val rr = r * swirlR[i]
            spark.color = Color.argb((140 + 100 * ((i % 3) / 2f)).toInt(), 255, 255, 255)
            canvas.drawCircle(x + cos(a) * rr, y + sin(a) * rr * 0.92f, size * 0.0016f, spark)
        }
    }

    private fun stepParticles(dt: Float, emitter: FloatArray?, size: Float) {
        if (emitter != null) {
            val rate = 140f + 260f * intensity.coerceIn(0f, 1f) + 120f * level
            emitCarry += rate * dt
            while (emitCarry >= 1f) {
                emitCarry -= 1f
                val ang = rnd.nextFloat() * 6.283f
                val sp = size * (0.10f + 0.55f * rnd.nextFloat())
                // stream: mostly sideways across the frame (like the reel), some swirl outwards
                val sideways = rnd.nextFloat() < 0.65f
                val vx: Float; val vy: Float
                if (sideways) {
                    val along = sp * (0.8f + 0.6f * rnd.nextFloat())
                    val lift = (rnd.nextFloat() - 0.35f) * sp * 0.25f
                    vx = streamDir[0] * along + upDir[0] * lift
                    vy = streamDir[1] * along + upDir[1] * lift
                } else {
                    vx = cos(ang) * sp * 0.5f; vy = sin(ang) * sp * 0.5f
                }
                particles.add(Particle(emitter[0] + cos(ang) * size * 0.03f, emitter[1] + sin(ang) * size * 0.03f, vx, vy, 0f,
                    0.5f + 1.0f * rnd.nextFloat(), size * (0.0012f + 0.0022f * rnd.nextFloat())))
            }
        }
        val it = particles.iterator()
        val drag = exp(-dt * 1.6f)
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age >= p.life) { it.remove(); continue }
            p.vx = p.vx * drag + upDir[0] * size * 0.02f * dt
            p.vy = p.vy * drag + upDir[1] * size * 0.02f * dt
            p.x += p.vx * dt; p.y += p.vy * dt
        }
        if (particles.size > 1600) particles.subList(0, particles.size - 1600).clear()
    }

    private fun drawParticles(canvas: android.graphics.Canvas, size: Float) {
        for (p in particles) {
            val k = 1f - p.age / p.life
            spark.color = Color.argb((235 * k).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawCircle(p.x, p.y, p.size, spark)
        }
    }

    /** Camera timestamps are CLOCK_MONOTONIC or BOOTTIME depending on the device; convert to System.nanoTime(). */
    private fun toNanoTime(ts: Long): Long {
        val off = clockOffset ?: run {
            val mono = System.nanoTime()
            val boot = SystemClock.elapsedRealtimeNanos()
            val o = if (abs(ts - boot) < abs(ts - mono)) mono - boot else 0L
            clockOffset = o
            o
        }
        return ts + off
    }

    private fun centerX(h: FloatArray) = (h[0] + h[18] + h[34]) / 3f
    private fun palmY(h: FloatArray) = (h[1] + h[11] + h[19] + h[35]) / 4f

    /** One-Euro filter: smooth when still, responsive when moving. */
    private class OneEuro(val minCutoff: Double, val beta: Double, val dCutoff: Double) {
        private var x: Double? = null
        private var dx = 0.0
        private var t = 0.0
        var velocity = 0.0; private set
        fun reset() { x = null; dx = 0.0; velocity = 0.0 }
        private fun alpha(cutoff: Double, dt: Double): Double { val tau = 1.0 / (2 * PI * cutoff); return 1.0 / (1.0 + tau / dt) }
        fun filter(v: Double, time: Double): Double {
            val prev = x
            if (prev == null || time <= t) { x = v; t = time; return v }
            val dt = max(1e-3, time - t)
            val rawD = (v - prev) / dt
            dx += alpha(dCutoff, dt) * (rawD - dx)
            val cutoff = minCutoff + beta * abs(dx)
            val nx = prev + alpha(cutoff, dt) * (v - prev)
            x = nx; t = time; velocity = dx
            return nx
        }
    }

    companion object {
        val CONNECTIONS = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20,
            0 to 17,
        )
    }
}
