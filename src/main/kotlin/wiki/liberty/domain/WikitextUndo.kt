// SPDX-License-Identifier: AGPL-3.0-only
package wiki.liberty.domain

sealed interface UndoResult {
    data class Applied(val source: String) : UndoResult
    data object Conflict : UndoResult
    data object TooLarge : UndoResult
}

data class WikitextUndoLimits(
    val maxInputBytes: Int = 3_145_728,
    val maxInputLines: Int = 4_000,
    val maxWork: Long = 2_000_000,
    val maxNanos: Long = 500_000_000,
)

/** Bounded line-only inverse merge. Multiple optimal line alignments are rejected. */
class WikitextUndo(private val limits: WikitextUndoLimits = WikitextUndoLimits()) {
    private data class Change(val start: Int, val end: Int, val replacement: List<String>)
    private sealed interface Changes {
        data class Found(val value: List<Change>) : Changes
        data object Ambiguous : Changes
        data object TooLarge : Changes
    }
    private class LimitExceeded : RuntimeException()

    fun apply(parent: String, target: String, head: String): UndoResult {
        if (target == head) return UndoResult.Applied(parent)
        if (parent == target) return UndoResult.Applied(head)
        return try {
            if (parent.length.toLong() + target.length + head.length > limits.maxInputBytes ||
                parent.toByteArray(Charsets.UTF_8).size.toLong() + target.toByteArray(Charsets.UTF_8).size +
                head.toByteArray(Charsets.UTF_8).size > limits.maxInputBytes
            ) throw LimitExceeded()
            val deadline = System.nanoTime() + limits.maxNanos
            if (listOf(parent, target, head).any { it.count { char -> char == '\n' } >= limits.maxInputLines }) {
                throw LimitExceeded()
            }
            val targetLines = target.split('\n')
            val parentLines = parent.split('\n')
            val headLines = head.split('\n')
            val inverse = when (val result = changes(targetLines, parentLines, deadline)) {
                is Changes.Found -> result.value
                Changes.Ambiguous -> return UndoResult.Conflict
                Changes.TooLarge -> return UndoResult.TooLarge
            }
            val subsequent = when (val result = changes(targetLines, headLines, deadline)) {
                is Changes.Found -> result.value
                Changes.Ambiguous -> return UndoResult.Conflict
                Changes.TooLarge -> return UndoResult.TooLarge
            }
            if (inverse.any { a -> subsequent.any { b -> overlaps(a, b) } }) return UndoResult.Conflict
            val result = headLines.toMutableList()
            for (change in inverse.asReversed()) {
                val shift = subsequent.filter { it.end <= change.start }
                    .sumOf { it.replacement.size - (it.end - it.start) }
                val start = change.start + shift
                val end = change.end + shift
                if (start < 0 || end > result.size) return UndoResult.Conflict
                result.subList(start, end).clear()
                result.addAll(start, change.replacement)
            }
            UndoResult.Applied(result.joinToString("\n"))
        } catch (_: LimitExceeded) {
            UndoResult.TooLarge
        }
    }

    private fun overlaps(a: Change, b: Change): Boolean {
        if (a.start == a.end) return a.start in b.start..b.end
        if (b.start == b.end) return b.start in a.start..a.end
        return a.start <= b.end && b.start <= a.end
    }

    private fun changes(from: List<String>, to: List<String>, deadline: Long): Changes {
        val cells = (from.size.toLong() + 1) * (to.size.toLong() + 1)
        if (cells > limits.maxWork || cells > Int.MAX_VALUE) return Changes.TooLarge
        // Intern lines once so the quadratic comparison operates on integers, even for long lines.
        val tokens = HashMap<String, Int>()
        fun ids(lines: List<String>) = lines.map { tokens.getOrPut(it) { tokens.size } }.toIntArray()
        val a = ids(from)
        val b = ids(to)
        val width = b.size + 1
        val suffix = IntArray(cells.toInt())
        val prefix = IntArray(cells.toInt())
        fun checkTime() { if (System.nanoTime() > deadline) throw LimitExceeded() }
        for (i in a.indices.reversed()) {
            checkTime()
            for (j in b.indices.reversed()) {
                suffix[i * width + j] = if (a[i] == b[j]) 1 + suffix[(i + 1) * width + j + 1]
                else maxOf(suffix[(i + 1) * width + j], suffix[i * width + j + 1])
            }
        }
        for (i in a.indices) {
            checkTime()
            for (j in b.indices) {
                prefix[(i + 1) * width + j + 1] = if (a[i] == b[j]) 1 + prefix[i * width + j]
                else maxOf(prefix[i * width + j + 1], prefix[(i + 1) * width + j])
            }
        }
        val length = suffix[0]
        val aMatch = IntArray(a.size) { -1 }
        val bMatch = IntArray(b.size) { -1 }
        for (i in a.indices) {
            checkTime()
            for (j in b.indices) {
                if (a[i] == b[j] && prefix[i * width + j] + 1 + suffix[(i + 1) * width + j + 1] == length) {
                    if ((aMatch[i] >= 0 && aMatch[i] != j) || (bMatch[j] >= 0 && bMatch[j] != i)) {
                        return Changes.Ambiguous
                    }
                    aMatch[i] = j
                    bMatch[j] = i
                }
            }
        }
        if (aMatch.count { it >= 0 } != length) return Changes.Ambiguous
        var previous = -1
        for (match in aMatch) {
            if (match < 0) continue
            if (match <= previous) return Changes.Ambiguous
            previous = match
        }
        val changes = mutableListOf<Change>()
        var ai = 0
        var bi = 0
        for (i in a.indices) {
            val j = aMatch[i]
            if (j < 0) continue
            if (ai != i || bi != j) changes.add(Change(ai, i, to.subList(bi, j).toList()))
            ai = i + 1
            bi = j + 1
        }
        if (ai != a.size || bi != b.size) changes.add(Change(ai, a.size, to.subList(bi, b.size).toList()))
        return Changes.Found(changes)
    }
}
