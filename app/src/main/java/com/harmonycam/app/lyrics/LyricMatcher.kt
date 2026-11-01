package com.harmonycam.app.lyrics

import kotlin.math.max
import kotlin.math.min

/**
 * Follows the singer through the lyrics using recognised words.
 *
 * Singing is hard for speech recognition, so the matcher is forgiving: fuzzy word equality,
 * in-order matching over a short window of recent words, and it only ever looks a few lines
 * ahead (choruses repeat, so jumping far would be wrong).
 */
class LyricMatcher(rawLines: List<String>) {

    val lines: List<String> = rawLines.map { it.trim() }.filter { it.isNotEmpty() }
    private val lineWords: List<List<String>> = lines.map { tokenize(it) }

    /** All words that can be sung — used to build the recogniser's grammar. */
    val vocabulary: Set<String> = lineWords.flatten().toSet()

    var currentLine = 0
        private set
    /** Number of words of the current line already sung (for highlighting). */
    var wordProgress = 0
        private set

    private val committed = ArrayList<String>()
    private var partial: List<String> = emptyList()
    private var consumed = 0
    private var lastAdvanceHeard = 0

    fun reset(line: Int = 0) {
        currentLine = line.coerceIn(0, max(0, lines.size - 1))
        wordProgress = 0
        committed.clear(); partial = emptyList(); consumed = 0; lastAdvanceHeard = 0
    }

    fun jumpTo(line: Int) {
        currentLine = line.coerceIn(0, max(0, lines.size - 1))
        wordProgress = 0
        consumed = heard().size
    }

    /** Partial hypothesis of the current utterance (replaces the previous partial). */
    fun onPartial(text: String): Boolean { partial = tokenize(text); return evaluate() }

    /** Final result of an utterance. */
    fun onFinal(text: String): Boolean {
        committed.addAll(tokenize(text))
        partial = emptyList()
        if (committed.size > 300) {
            val drop = committed.size - 150
            committed.subList(0, drop).clear()
            consumed = max(0, consumed - drop)
            lastAdvanceHeard = max(0, lastAdvanceHeard - drop)
        }
        return evaluate()
    }

    private fun heard(): List<String> = committed + partial

    private fun evaluate(): Boolean {
        if (lines.isEmpty()) return false
        val heard = heard()
        consumed = min(consumed, heard.size)
        val windowStart = max(consumed, heard.size - WINDOW)
        val window = heard.subList(windowStart, heard.size)
        if (window.isEmpty()) return false
        val before = currentLine to wordProgress

        // progress inside the current line
        val cur = align(window, lineWords[currentLine], 0)
        if (cur.matches > 0) wordProgress = max(wordProgress, cur.lastIndex + 1)

        // look ahead for the next line(s)
        val recent = window.takeLast(RECENT)
        val recentCur = align(recent, lineWords[currentLine], 0).matches
        for (ahead in 1..LOOKAHEAD) {
            val idx = currentLine + ahead
            if (idx >= lines.size) break
            val target = lineWords[idx]
            if (target.isEmpty()) continue
            val res = align(recent, target, 0)
            val needed = when {
                target.size <= 1 -> 1
                ahead == 1 -> 2
                else -> 3   // skipping a line needs stronger evidence
            }
            val startsNearBeginning = res.firstIndex <= 2
            // the current line should be mostly done, or the evidence for the new line clearly stronger
            val currentDone = wordProgress >= lineWords[currentLine].size - 2
            if (res.matches >= needed && startsNearBeginning && (res.matches > recentCur || currentDone)) {
                currentLine = idx
                wordProgress = res.lastIndex + 1
                // keep the words that matched the new line; forget older ones
                consumed = heard.size - recent.size + res.firstHeard
                lastAdvanceHeard = heard.size
                break
            }
        }
        return before != (currentLine to wordProgress)
    }

    private data class Alignment(val matches: Int, val firstIndex: Int, val lastIndex: Int, val firstHeard: Int)

    /**
     * In-order alignment of heard words against a line. Tries every starting word so an early
     * stray word (e.g. "the" from the previous line) can't hijack the alignment.
     */
    private fun align(heard: List<String>, line: List<String>, start: Int): Alignment {
        var best = Alignment(0, Int.MAX_VALUE, -1, 0)
        for (from in heard.indices) {
            val a = alignFrom(heard, from, line, start)
            if (a.matches > best.matches || (a.matches == best.matches && a.matches > 0 && a.firstIndex < best.firstIndex)) best = a
        }
        return best
    }

    private fun alignFrom(heard: List<String>, from: Int, line: List<String>, start: Int): Alignment {
        var pos = start
        var matches = 0
        var first = Int.MAX_VALUE
        var last = -1
        var firstHeard = 0
        for (h in from until heard.size) {
            val w = heard[h]
            var j = pos
            while (j < line.size && !similar(w, line[j])) j++
            if (j < line.size && j - pos <= 3) {
                if (matches == 0) { first = j; firstHeard = h }
                matches++
                last = j
                pos = j + 1
            }
        }
        return Alignment(matches, if (matches == 0) Int.MAX_VALUE else first, last, firstHeard)
    }

    companion object {
        private const val WINDOW = 10
        private const val RECENT = 5
        private const val LOOKAHEAD = 3

        fun tokenize(text: String): List<String> =
            text.lowercase()
                .replace('’', '\'')
                .split(Regex("[^a-z0-9']+"))
                .map { it.trim('\'') }
                .filter { it.isNotEmpty() }

        fun similar(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.length >= 4 && b.length >= 4) {
                if (a.take(4) == b.take(4)) return true
                return editDistance(a, b) <= if (max(a.length, b.length) >= 7) 2 else 1
            }
            // common sung contractions: "gonna" / "goin" etc. are handled by the 4-char prefix rule
            return false
        }

        fun editDistance(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = if (a[i - 1] == b[j - 1]) prev else 1 + min(prev, min(dp[j], dp[j - 1]))
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}
