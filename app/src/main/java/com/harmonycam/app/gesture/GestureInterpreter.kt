package com.harmonycam.app.gesture

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns raw hand landmarks into harmony controls.
 *
 * Voice hand (right side of the screen, or the only hand):
 *   - raised fingers (index..pinky) = number of harmony voices (1–4)
 *   - hand height = harmony layout (low → below the melody, high → above)
 *   - closed fist = harmonies muted
 * FX hand (left side of the screen):
 *   - thumb–index spread = reverb / visual intensity (pinched = dry, spread = huge)
 *
 * Landmarks are 21 points × (x, y), normalised 0..1 in the upright, mirrored (selfie) image.
 */
class GestureInterpreter {

    data class State(
        val voices: Int = 2,
        val height: Float = 0.5f,
        val mute: Boolean = false,
        val fx: Float = 0.3f,
        val voiceHand: FloatArray? = null,
        val fxHand: FloatArray? = null,
        val extended: BooleanArray = BooleanArray(4),
        val pinch: Float = 0f,
    )

    private var voices = 2
    private var candidateVoices = 2
    private var candidateFrames = 0
    private var mute = false
    private var muteFrames = 0
    private var height = 0.5f
    private var fx = 0.3f
    private var hasHeight = false
    private var lastVoiceSeenMs = 0L

    fun reset(defaultVoices: Int, defaultHeight: Float) {
        voices = defaultVoices; candidateVoices = defaultVoices; candidateFrames = 0
        height = defaultHeight; hasHeight = false; mute = false; muteFrames = 0
    }

    /**
     * @param hands landmark arrays (42 floats each)
     * @param swapHands treat the left-side hand as the voice hand
     * @param holdWhenLost keep the last harmony state when the voice hand leaves the frame
     */
    fun update(hands: List<FloatArray>, swapHands: Boolean, holdWhenLost: Boolean, nowMs: Long): State {
        var voiceHand: FloatArray? = null
        var fxHand: FloatArray? = null
        when (hands.size) {
            0 -> {}
            1 -> voiceHand = hands[0]
            else -> {
                val sorted = hands.take(2).sortedBy { palmCenter(it).first }
                val left = sorted[0]
                val right = sorted[1]
                if (swapHands) { voiceHand = left; fxHand = right } else { voiceHand = right; fxHand = left }
            }
        }

        val ext = BooleanArray(4)
        var pinchAmount = 0f
        if (voiceHand != null) {
            lastVoiceSeenMs = nowMs
            for (f in 0 until 4) ext[f] = isFingerExtended(voiceHand, f)
            val count = ext.count { it }
            val fist = count == 0 && isFist(voiceHand)

            // debounce mute so a quick finger change doesn't cut the harmonies
            if (fist) muteFrames++ else muteFrames = 0
            if (muteFrames >= 3) mute = true
            if (!fist && count > 0) mute = false

            if (count > 0) {
                if (count == candidateVoices) candidateFrames++ else { candidateVoices = count; candidateFrames = 1 }
                if (candidateFrames >= 3) voices = candidateVoices
            }

            // height: palm centre, active band 0.15..0.85 of the frame
            val (_, cy) = palmCenter(voiceHand)
            val target = ((0.85f - cy) / 0.70f).coerceIn(0f, 1f)
            height = if (!hasHeight) target else height + (target - height) * 0.35f
            hasHeight = true
        } else if (!holdWhenLost && nowMs - lastVoiceSeenMs > 600) {
            mute = true
        }

        if (fxHand != null) {
            val size = palmSize(fxHand)
            val d = dist(fxHand, THUMB_TIP, INDEX_TIP) / max(size, 1e-4f)
            pinchAmount = d
            val target = ((d - 0.25f) / (1.25f - 0.25f)).coerceIn(0f, 1f)
            fx += (target - fx) * 0.3f
        }

        return State(
            voices = voices,
            height = height,
            mute = mute,
            fx = fx,
            voiceHand = voiceHand,
            fxHand = fxHand,
            extended = ext,
            pinch = pinchAmount,
        )
    }

    companion object {
        const val WRIST = 0
        const val THUMB_TIP = 4
        const val INDEX_TIP = 8
        private val MCP = intArrayOf(5, 9, 13, 17)
        private val PIP = intArrayOf(6, 10, 14, 18)
        private val TIP = intArrayOf(8, 12, 16, 20)

        fun px(h: FloatArray, i: Int) = h[2 * i]
        fun py(h: FloatArray, i: Int) = h[2 * i + 1]
        fun dist(h: FloatArray, a: Int, b: Int) = hypot(px(h, a) - px(h, b), py(h, a) - py(h, b))

        fun palmSize(h: FloatArray) = dist(h, WRIST, 9)

        fun palmCenter(h: FloatArray): Pair<Float, Float> {
            val idx = intArrayOf(0, 5, 9, 13, 17)
            var x = 0f; var y = 0f
            for (i in idx) { x += px(h, i); y += py(h, i) }
            return x / idx.size to y / idx.size
        }

        /** Rotation-independent: tip is far from the wrist and the finger is roughly straight. */
        fun isFingerExtended(h: FloatArray, finger: Int): Boolean {
            val mcp = MCP[finger]; val pip = PIP[finger]; val tip = TIP[finger]
            val farther = dist(h, WRIST, tip) > dist(h, WRIST, pip) * 1.12f
            val ax = px(h, pip) - px(h, mcp); val ay = py(h, pip) - py(h, mcp)
            val bx = px(h, tip) - px(h, pip); val by = py(h, tip) - py(h, pip)
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            if (la < 1e-5f || lb < 1e-5f) return false
            val cos = (ax * bx + ay * by) / (la * lb)
            return farther && cos > 0.55f
        }

        fun isFist(h: FloatArray): Boolean {
            val (cx, cy) = palmCenter(h)
            val size = palmSize(h)
            var sum = 0f
            for (t in TIP) sum += hypot(px(h, t) - cx, py(h, t) - cy)
            return sum / TIP.size < size * 0.95f
        }

        fun clamp01(v: Float) = min(1f, max(0f, v))
    }
}
