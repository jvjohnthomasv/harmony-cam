package com.harmonycam.app.gesture

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns raw hand landmarks into harmony controls — instantly (no frame-counting delays);
 * stability comes from per-finger hysteresis instead.
 *
 * Voice hand = the hand you gesture with. It is *tracked*: once a hand has shown open fingers it
 * stays the voice hand (at any height) until it leaves the frame, so closing it into a fist mutes
 * immediately. A hand that has never opened (e.g. the one holding the mic) never steers anything.
 *   - raised fingers (index..pinky) = number of harmony voices (1–4; open palm = full chord)
 *   - hand height = voicing (low → below the melody, high → above)
 *   - closed fist = harmonies muted
 * FX hand (the other hand, if it has also opened):
 *   - thumb–index spread = reverb / effect intensity
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
        /** index of the voice hand in the input list (-1 = none). */
        val voiceIndex: Int = -1,
    )

    private class Track(var x: Float, var y: Float, var lastSeenMs: Long, var opened: Boolean, val ext: BooleanArray = BooleanArray(4))

    private var voices = 2
    private var mute = false
    private var height = 0.5f
    private var fx = 0.3f
    private var hasHeight = false
    private var voiceTrack: Track? = null
    private var fxTrack: Track? = null
    private val candidates = ArrayList<Track>()

    fun reset(defaultVoices: Int, defaultHeight: Float) {
        voices = defaultVoices; height = defaultHeight; hasHeight = false; mute = false
        voiceTrack = null; fxTrack = null; candidates.clear()
    }

    /**
     * @param hands landmark arrays (42 floats each), mirrored selfie coordinates
     * @param swapHands prefer the lower open hand as the voice hand
     * @param holdWhenLost keep the last harmony state when the voice hand leaves the frame
     */
    fun update(hands: List<FloatArray>, swapHands: Boolean, holdWhenLost: Boolean, nowMs: Long): State {
        val list = hands.take(2)
        val centers = list.map { palmCenter(it) }

        // ── match detections to known tracks (nearest palm centre)
        val used = BooleanArray(list.size)
        fun match(t: Track?, radius: Float): Int {
            if (t == null) return -1
            var best = -1; var bestD = radius
            for (i in list.indices) {
                if (used[i]) continue
                val d = hypot(centers[i].first - t.x, centers[i].second - t.y)
                if (d < bestD) { bestD = d; best = i }
            }
            if (best >= 0) {
                used[best] = true
                t.x = centers[best].first; t.y = centers[best].second; t.lastSeenMs = nowMs
            }
            return best
        }
        // allowed jump grows with the time since the hand was last seen (fast gestures ≈ 3–4 frame-widths/s)
        fun reach(t: Track?) = if (t == null) MATCH_RADIUS else min(0.45f, 0.10f + 2.5f * ((nowMs - t.lastSeenMs).coerceAtLeast(0) / 1000f))
        var vIdx = match(voiceTrack, reach(voiceTrack))
        if (vIdx < 0 && voiceTrack != null && nowMs - voiceTrack!!.lastSeenMs > LOST_MS) voiceTrack = null
        var fIdx = match(fxTrack, reach(fxTrack))
        if (fIdx < 0 && fxTrack != null && nowMs - fxTrack!!.lastSeenMs > LOST_MS) fxTrack = null

        // ── unmatched hands: track them; a hand becomes eligible once it shows an open finger
        val newOpen = ArrayList<Int>()
        for (i in list.indices) {
            if (used[i]) continue
            var cand = candidates.minByOrNull { hypot(it.x - centers[i].first, it.y - centers[i].second) }
            if (cand == null || hypot(cand.x - centers[i].first, cand.y - centers[i].second) > MATCH_RADIUS) {
                cand = Track(centers[i].first, centers[i].second, nowMs, false).also { candidates.add(it) }
            }
            cand.x = centers[i].first; cand.y = centers[i].second; cand.lastSeenMs = nowMs
            updateFingers(list[i], cand.ext)
            if (cand.ext.any { it }) cand.opened = true
            if (cand.opened) newOpen.add(i)
        }
        candidates.removeAll { nowMs - it.lastSeenMs > LOST_MS }

        // ── promote open hands to voice / fx roles
        // (a voice hand that wasn't found this frame is replaced by any newly opened hand —
        //  e.g. the hand left the frame and came back somewhere else)
        if ((voiceTrack == null || vIdx < 0) && newOpen.isNotEmpty()) {
            val pick = if (swapHands) newOpen.maxByOrNull { centers[it].second }!! else newOpen.minByOrNull { centers[it].second }!!
            voiceTrack = candidates.first { it.x == centers[pick].first && it.y == centers[pick].second }
            candidates.remove(voiceTrack)
            vIdx = pick
            newOpen.remove(pick)
        }
        if (fxTrack == null && newOpen.isNotEmpty()) {
            val pick = newOpen.first()
            fxTrack = candidates.first { it.x == centers[pick].first && it.y == centers[pick].second }
            candidates.remove(fxTrack)
            fIdx = pick
        }

        // ── voice hand → voices / height / mute (applied immediately)
        val vt = voiceTrack
        var voiceHand: FloatArray? = null
        if (vt != null && vIdx >= 0) {
            val h = list[vIdx]
            voiceHand = h
            updateFingers(h, vt.ext)
            val count = vt.ext.count { it }
            if (count == 0 && isFist(h)) mute = true
            else if (count > 0) { mute = false; voices = count }
            val target = ((0.85f - centers[vIdx].second) / 0.70f).coerceIn(0f, 1f)
            height = if (!hasHeight) target else height + (target - height) * 0.6f
            hasHeight = true
        } else if (vt == null && !holdWhenLost) {
            mute = true
        }

        // ── fx hand → pinch spread
        var fxHand: FloatArray? = null
        var pinchAmount = 0f
        if (fxTrack != null && fIdx >= 0) {
            val h = list[fIdx]
            fxHand = h
            val d = dist(h, THUMB_TIP, INDEX_TIP) / max(palmSize(h), 1e-4f)
            pinchAmount = d
            val target = ((d - 0.25f) / (1.25f - 0.25f)).coerceIn(0f, 1f)
            fx += (target - fx) * 0.5f
        }

        return State(
            voices = voices,
            height = height,
            mute = mute,
            fx = fx,
            voiceHand = voiceHand,
            fxHand = fxHand,
            extended = vt?.ext?.copyOf() ?: BooleanArray(4),
            pinch = pinchAmount,
            voiceIndex = if (voiceHand != null) vIdx else -1,
        )
    }

    /** Per-finger hysteresis: a finger must clearly straighten to count, and clearly curl to stop counting. */
    private fun updateFingers(h: FloatArray, ext: BooleanArray) {
        for (f in 0 until 4) {
            val (ratio, cos) = fingerShape(h, f)
            ext[f] = if (ext[f]) !(ratio < 1.04f || cos < 0.30f) else (ratio > 1.14f && cos > 0.60f)
        }
    }

    companion object {
        const val WRIST = 0
        const val THUMB_TIP = 4
        const val INDEX_TIP = 8
        private const val MATCH_RADIUS = 0.28f
        private const val LOST_MS = 700L
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

        /** (tip-to-wrist / pip-to-wrist ratio, straightness cosine) — rotation independent. */
        fun fingerShape(h: FloatArray, finger: Int): Pair<Float, Float> {
            val mcp = MCP[finger]; val pip = PIP[finger]; val tip = TIP[finger]
            val ratio = dist(h, WRIST, tip) / max(dist(h, WRIST, pip), 1e-5f)
            val ax = px(h, pip) - px(h, mcp); val ay = py(h, pip) - py(h, mcp)
            val bx = px(h, tip) - px(h, pip); val by = py(h, tip) - py(h, pip)
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            val cos = if (la < 1e-5f || lb < 1e-5f) 0f else (ax * bx + ay * by) / (la * lb)
            return ratio to cos
        }

        fun isFingerExtended(h: FloatArray, finger: Int): Boolean {
            val (ratio, cos) = fingerShape(h, finger)
            return ratio > 1.12f && cos > 0.55f
        }

        fun fingerCount(h: FloatArray): Int = (0 until 4).count { isFingerExtended(h, it) }

        fun isFist(h: FloatArray): Boolean {
            val (cx, cy) = palmCenter(h)
            val size = palmSize(h)
            var sum = 0f
            for (t in TIP) sum += hypot(px(h, t) - cx, py(h, t) - cy)
            return sum / TIP.size < size * 1.0f
        }

        fun clamp01(v: Float) = min(1f, max(0f, v))
    }
}
