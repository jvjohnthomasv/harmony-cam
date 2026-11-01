package com.harmonycam.app.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Everything the visuals react to, sampled once per rendered frame. */
class VisualInput {
    var time = 0f
    var level = 0f            // 0..1 vocal loudness
    var pulse = 0f            // fast transient envelope
    var fx = 0.3f             // pinch amount
    var mute = false
    var voiceHand: FloatArray? = null   // 21 × (x,y) in frame space (0..1, y down)
    var fxHand: FloatArray? = null
    var extended = BooleanArray(4)
    val voiceMidi = IntArray(4)
    val voiceEnv = FloatArray(4)
    var showSkeleton = true
}

/**
 * CPU particle system producing glowing point sprites: finger light-trails, harmony orbs that
 * orbit the voice hand (one per sung harmony), sparks on harmony note changes and floating bokeh dust.
 * Output: interleaved [x, y, size, r, g, b, a] in NDC for the point shader.
 */
class ParticleSystem(private val maxPoints: Int = 1400) {
    val data = FloatArray(maxPoints * STRIDE)
    var count = 0
        private set

    private val rnd = Random(7)
    private val trailLen = 16
    // trails[hand][finger] → ring of (x,y)
    private val trails = Array(2) { Array(5) { FloatArray(trailLen * 2) { -1f } } }
    private val trailPos = Array(2) { IntArray(5) }
    private val lastMidi = IntArray(4)
    private val orbPos = Array(4) { floatArrayOf(0.5f, 0.4f) }
    private var orbCenter = floatArrayOf(0.5f, 0.42f)

    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var max: Float, val color: FloatArray, val size: Float)
    private val sparks = ArrayList<Spark>()

    private class Dust(var x: Float, var y: Float, var vy: Float, var phase: Float, val size: Float)
    private val dust = ArrayList<Dust>()

    private var lastTime = 0f
    private var heightPx = 1920f

    fun update(input: VisualInput, style: VisualStyle, frameHeightPx: Int) {
        heightPx = frameHeightPx.toFloat()
        val dt = if (lastTime == 0f) 1f / 60f else min(0.1f, max(0f, input.time - lastTime))
        lastTime = input.time
        count = 0
        val scale = heightPx / 1920f
        val intensity = 0.55f + 0.9f * input.fx

        // ── bokeh dust
        val wantDust = (style.dust * (0.5f + input.fx)).toInt()
        while (dust.size < wantDust) dust.add(Dust(rnd.nextFloat(), rnd.nextFloat(), 0.01f + 0.03f * rnd.nextFloat(), rnd.nextFloat() * 6.28f, 14f + 34f * rnd.nextFloat()))
        while (dust.size > wantDust) dust.removeAt(dust.size - 1)
        for ((i, d) in dust.withIndex()) {
            d.y -= d.vy * dt * (1f + 2f * input.level)
            d.x += 0.006f * sin(input.time * 0.7f + d.phase) * dt * 10f
            if (d.y < -0.05f) { d.y = 1.05f; d.x = rnd.nextFloat() }
            val c = style.voiceColors[i % 4]
            val a = (0.05f + 0.20f * input.level) * (0.6f + 0.4f * sin(input.time * 1.3f + d.phase))
            add(d.x, d.y, d.size * scale * (1f + input.pulse * 0.5f), c, max(0f, a))
        }

        // ── finger trails
        val hands = arrayOf(input.voiceHand, input.fxHand)
        for (h in 0 until 2) {
            val hand = hands[h]
            for (f in 0 until 5) {
                val ring = trails[h][f]
                if (hand == null) {
                    // fade out trails when the hand disappears
                    val p = trailPos[h][f]
                    ring[p * 2] = -1f; ring[p * 2 + 1] = -1f
                    trailPos[h][f] = (p + 1) % trailLen
                    continue
                }
                val tip = TIPS[f]
                val p = trailPos[h][f]
                ring[p * 2] = hand[tip * 2]; ring[p * 2 + 1] = hand[tip * 2 + 1]
                trailPos[h][f] = (p + 1) % trailLen
            }
            if (!style.trails) continue
            for (f in 0 until 5) {
                val ring = trails[h][f]
                val color: FloatArray = when {
                    h == 1 -> style.voiceColors[(f + 1) % 4]
                    f == 0 -> WHITE
                    input.extended.getOrElse(f - 1) { false } && !input.mute -> style.voiceColors[f - 1]
                    else -> DIM
                }
                for (k in 0 until trailLen) {
                    val idx = (trailPos[h][f] + k) % trailLen // oldest → newest
                    val x = ring[idx * 2]; val y = ring[idx * 2 + 1]
                    if (x < 0f) continue
                    val t = (k + 1f) / trailLen
                    add(x, y, (10f + 26f * t) * scale * intensity, color, 0.12f + 0.55f * t * t)
                }
            }
        }

        // ── hand skeleton (subtle)
        val vh = input.voiceHand
        if (input.showSkeleton && vh != null) {
            for ((a, b) in BONES) {
                for (s in 0..4) {
                    val t = s / 4f
                    add(vh[a * 2] + (vh[b * 2] - vh[a * 2]) * t, vh[a * 2 + 1] + (vh[b * 2 + 1] - vh[a * 2 + 1]) * t, 7f * scale, WHITE, 0.18f)
                }
            }
        }

        // ── harmony orbs orbiting the voice hand (or a resting point when no hand is visible)
        val target = if (vh != null) floatArrayOf((vh[0] + vh[18]) / 2f, (vh[1] + vh[19]) / 2f - 0.05f) else floatArrayOf(0.5f, 0.38f)
        orbCenter[0] += (target[0] - orbCenter[0]) * min(1f, dt * 8f)
        orbCenter[1] += (target[1] - orbCenter[1]) * min(1f, dt * 8f)
        for (v in 0 until 4) {
            val env = input.voiceEnv[v]
            val color = style.voiceColors[v]
            val radius = 0.09f + 0.045f * v + 0.03f * input.level
            val speed = 0.6f + 0.25f * v
            val ang = input.time * speed * (if (v % 2 == 0) 1f else -1f) + v * (PI.toFloat() / 2f)
            val ox = orbCenter[0] + cos(ang) * radius
            val oy = orbCenter[1] + sin(ang) * radius * 0.75f
            orbPos[v][0] = ox; orbPos[v][1] = oy
            if (env > 0.01f) {
                val size = (60f + 90f * input.level + 50f * input.pulse) * scale * env * (0.7f + 0.5f * input.fx)
                add(ox, oy, size, color, 0.85f * env)
                // satellite ring
                for (s in 0 until 10) {
                    val a2 = input.time * 2.2f + s * (2f * PI.toFloat() / 10f)
                    val r2 = 0.025f + 0.02f * input.level
                    add(ox + cos(a2) * r2, oy + sin(a2) * r2 * 0.8f, 9f * scale, color, 0.5f * env)
                }
            }
            // sparks when a harmony voice moves to a new note
            val midi = input.voiceMidi[v]
            if (env > 0.3f && midi != 0 && lastMidi[v] != 0 && midi != lastMidi[v] && style.trails) {
                repeat(14) {
                    val a = rnd.nextFloat() * 6.283f
                    val sp = 0.15f + 0.35f * rnd.nextFloat()
                    sparks.add(Spark(ox, oy, cos(a) * sp, sin(a) * sp, 0f, 0.6f + 0.5f * rnd.nextFloat(), color, 12f + 14f * rnd.nextFloat()))
                }
            }
            lastMidi[v] = midi
        }

        // ── sparks
        val iter = sparks.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            s.life += dt
            if (s.life >= s.max) { iter.remove(); continue }
            s.vx *= 0.96f; s.vy = s.vy * 0.96f + 0.12f * dt
            s.x += s.vx * dt; s.y += s.vy * dt
            val k = 1f - s.life / s.max
            add(s.x, s.y, s.size * scale * k, s.color, 0.9f * k)
        }
        if (sparks.size > 300) sparks.subList(0, sparks.size - 300).clear()
    }

    private fun add(x: Float, y: Float, size: Float, c: FloatArray, a: Float) {
        if (count >= maxPoints) return
        val o = count * STRIDE
        data[o] = x * 2f - 1f
        data[o + 1] = 1f - y * 2f
        data[o + 2] = size
        data[o + 3] = c[0]; data[o + 4] = c[1]; data[o + 5] = c[2]
        data[o + 6] = min(1f, a)
        count++
    }

    companion object {
        const val STRIDE = 7
        private val TIPS = intArrayOf(4, 8, 12, 16, 20)
        private val WHITE = floatArrayOf(1f, 1f, 1f)
        private val DIM = floatArrayOf(0.55f, 0.6f, 0.7f)
        private val BONES = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12, 9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17
        )
    }
}
