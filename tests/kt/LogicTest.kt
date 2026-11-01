import com.harmonycam.app.lyrics.LyricMatcher
import com.harmonycam.app.gesture.GestureInterpreter

var fails = 0
fun check(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails++ }

fun main() {
    val lyrics = """
        Here comes the morning light
        Shining on the open road
        I will carry you tonight
        Every mile we go

        Here comes the morning light
        Shining on the open road
    """.trimIndent().lines()
    val m = LyricMatcher(lyrics)
    check("6 non-empty lines", m.lines.size == 6)
    m.reset()
    // singer sings line 0 with recognition errors, as partials growing
    m.onPartial("here"); m.onPartial("here comes"); m.onPartial("here comes the morning")
    check("progress within line 0", m.currentLine == 0 && m.wordProgress >= 4)
    m.onFinal("here comes the morning light")
    // line 1 partially recognised (shinin' misheard)
    m.onPartial("shinin on")
    check("advance to line 1 after two words", m.currentLine == 1)
    m.onFinal("shinin on the open road")
    check("line 1 progress", m.currentLine == 1 && m.wordProgress >= 4)
    m.onPartial("i will"); m.onPartial("i will carry")
    check("advance to line 2", m.currentLine == 2)
    m.onFinal("i will carry you tonight")
    m.onPartial("every mile")
    check("advance to line 3", m.currentLine == 3)
    m.onFinal("every mile we go")
    // repeated chorus: must go to line 4 (next), not jump back to 0
    m.onPartial("here comes the")
    check("chorus repeat goes forward to line 4", m.currentLine == 4)
    // noise words should not advance
    val before = m.currentLine
    m.onFinal("here comes the morning light"); m.onPartial("the")
    check("single common word doesn't advance", m.currentLine == before)
    m.jumpTo(1)
    check("manual jump", m.currentLine == 1 && m.wordProgress == 0)
    check("fuzzy: carry~carrie", LyricMatcher.similar("carry", "carrie"))
    check("fuzzy: no false match go~so", !LyricMatcher.similar("go", "so"))

    // ── gestures: synthetic hand (upright, y down). Build open palm then fist.
    fun hand(cx: Float, cy: Float, extended: BooleanArray): FloatArray {
        val h = FloatArray(42)
        fun set(i: Int, x: Float, y: Float) { h[2*i] = cx + x; h[2*i+1] = cy + y }
        set(0, 0f, 0.10f)                // wrist
        set(1, -0.04f, 0.08f); set(2, -0.06f, 0.05f); set(3, -0.07f, 0.02f); set(4, -0.08f, 0.0f) // thumb
        val xs = floatArrayOf(-0.03f, -0.01f, 0.01f, 0.03f)
        for (f in 0 until 4) {
            val base = 5 + f * 4
            set(base, xs[f], 0.0f)           // MCP
            if (extended[f]) { set(base+1, xs[f], -0.035f); set(base+2, xs[f], -0.06f); set(base+3, xs[f], -0.08f) }
            else { set(base+1, xs[f], -0.02f); set(base+2, xs[f]+0.003f, 0.0f); set(base+3, xs[f]+0.002f, 0.015f) }
        }
        return h
    }
    val g = GestureInterpreter()
    var st = GestureInterpreter.State()
    repeat(5) { st = g.update(listOf(hand(0.7f, 0.3f, booleanArrayOf(true, true, true, false))), false, true, it * 33L) }
    check("three fingers -> 3 voices (got ${st.voices})", st.voices == 3 && !st.mute)
    check("high hand -> height > 0.6 (got ${st.height})", st.height > 0.6f)
    repeat(5) { st = g.update(listOf(hand(0.7f, 0.3f, booleanArrayOf(true, false, false, false))), false, true, 200 + it * 33L) }
    check("one finger -> 1 voice (got ${st.voices})", st.voices == 1)
    repeat(5) { st = g.update(listOf(hand(0.7f, 0.3f, booleanArrayOf(false, false, false, false))), false, true, 400 + it * 33L) }
    check("fist -> mute", st.mute)
    repeat(10) { st = g.update(listOf(hand(0.7f, 0.75f, booleanArrayOf(true, true, false, false))), false, true, 600 + it * 33L) }
    check("open again unmutes, 2 voices, low hand (v=${st.voices} h=${st.height})", !st.mute && st.voices == 2 && st.height < 0.3f)
    // two hands: the raised one is the voice hand, the lower one is FX; spread thumb/index wide
    val fxHand = hand(0.25f, 0.55f, booleanArrayOf(true, true, true, true)).also { it[8] = 0.25f - 0.2f; it[9] = 0.55f }
    repeat(15) { st = g.update(listOf(fxHand, hand(0.7f, 0.3f, booleanArrayOf(true, true, true, true))), false, true, 1000 + it * 33L) }
    check("two hands: raised is voice (4 voices), fx rises (fx=${st.fx})", st.voices == 4 && st.fx > 0.6f)
    // mic hand: a low fist alone must NOT mute
    repeat(6) { st = g.update(listOf(hand(0.4f, 0.8f, booleanArrayOf(false, false, false, false))), false, true, 1600 + it * 33L) }
    check("low fist (holding mic) doesn't mute", !st.mute && st.voices == 4)
    // raised hand + mic hand: still 4 voices from the raised hand
    repeat(6) { st = g.update(listOf(hand(0.4f, 0.8f, booleanArrayOf(false, false, false, false)), hand(0.7f, 0.3f, booleanArrayOf(true, true, true, true))), false, true, 1900 + it * 33L) }
    check("raised hand + mic hand -> 4 voices, not muted", !st.mute && st.voices == 4)
    st = g.update(emptyList(), false, true, 2500)
    check("hand lost holds state", st.voices == 4 && !st.mute)
    // instant response: every change applies on the very next detection frame
    val g2 = GestureInterpreter()
    var s2 = g2.update(listOf(hand(0.6f, 0.4f, booleanArrayOf(true, true, true, true))), false, true, 0)
    check("open palm -> 4 voices on first frame", s2.voices == 4 && !s2.mute)
    s2 = g2.update(listOf(hand(0.6f, 0.4f, booleanArrayOf(true, true, false, false))), false, true, 33)
    check("2 fingers applied on the next frame (got ${s2.voices})", s2.voices == 2)
    var tt = 66L
    for (k in 1..5) { s2 = g2.update(listOf(hand(0.6f, 0.4f + 0.076f * k, booleanArrayOf(true, true, false, false))), false, true, tt); tt += 33 }
    s2 = g2.update(listOf(hand(0.6f, 0.78f, booleanArrayOf(false, false, false, false))), false, true, tt); tt += 33
    check("voice hand lowered into a fist mutes immediately (even low)", s2.mute)
    for (k in 1..4) { s2 = g2.update(listOf(hand(0.62f, 0.78f - 0.07f * k, booleanArrayOf(false, false, false, false))), false, true, tt); tt += 33 }
    s2 = g2.update(listOf(hand(0.62f, 0.5f, booleanArrayOf(true, false, false, false))), false, true, tt); tt += 33
    check("opening one finger unmutes immediately", !s2.mute && s2.voices == 1)
    s2 = g2.update(listOf(hand(0.3f, 0.85f, booleanArrayOf(false, false, false, false)), hand(0.62f, 0.5f, booleanArrayOf(true, true, true, false))), false, true, tt)
    check("mic fist appearing beside voice hand changes nothing but finger count (v=${s2.voices}, idx=${s2.voiceIndex})", !s2.mute && s2.voices == 3 && s2.voiceIndex == 1)
    println(if (fails == 0) "ALL LOGIC TESTS PASSED" else "FAILURES: $fails")
}
