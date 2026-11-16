// SPDX-License-Identifier: AGPL-3.0-only
package wiki.liberty.domain

const val MAX_COMPARISON_OUTPUT_BYTES = 4_000_000

/** Limits apply to both inputs together and to the generated representation. */
data class WikitextDiffLimits(
    val maxInputBytes: Int = 1_000_000,
    val maxInputLines: Int = 4_000,
    val maxOutputRows: Int = 4_000,
    val maxOutputBytes: Int = MAX_COMPARISON_OUTPUT_BYTES,
    val maxWork: Long = 2_000_000,
)

enum class DiffLineType { CONTEXT, ADDED, DELETED, CHANGED }
data class DiffFragment(val text: String, val changed: Boolean)
data class DiffLine(
    val type: DiffLineType,
    val fromLine: Int?,
    val toLine: Int?,
    val from: List<DiffFragment>,
    val to: List<DiffFragment>,
)
sealed interface DiffResult {
    data class Success(val lines: List<DiffLine>) : DiffResult
    data object TooLarge : DiffResult
}

/** A bounded, source-preserving line and Unicode code point comparison. */
class WikitextDiff(private val limits: WikitextDiffLimits = WikitextDiffLimits()) {
    private class Budget(private val limit: Long) {
        private var used = 0L
        private val startedAt = System.nanoTime()
        private var checks = 0

        fun spend(amount: Long) {
            if (amount < 0 || amount > limit - used) throw WikitextLimitExceeded()
            used += amount
            if (++checks % 1024 == 0) checkTime()
        }

        fun checkTime() {
            if (System.nanoTime() - startedAt > 500_000_000L) throw WikitextLimitExceeded()
        }
    }

    fun compare(from: String, to: String): DiffResult {
        return try {
            // UTF-8 encoding allocates at most three bytes per UTF-16 code unit.
            if (from.length.toLong() + to.length > limits.maxInputBytes) throw WikitextLimitExceeded()
            if (from.toByteArray(Charsets.UTF_8).size.toLong() + to.toByteArray(Charsets.UTF_8).size > limits.maxInputBytes) throw WikitextLimitExceeded()

            val budget = Budget(limits.maxWork)
            val old = splitLines(from)
            val new = splitLines(to)
            if (old.size.toLong() + new.size > limits.maxInputLines) throw WikitextLimitExceeded()
            if (from == to) {
                if (limits.maxOutputBytes < 2) throw WikitextLimitExceeded()
                return DiffResult.Success(emptyList())
            }
            val steps = sequence(old, new, budget) { a, b ->
                budget.spend(minOf(a.length, b.length).toLong())
                a == b
            }
            val rows = mutableListOf<DiffLine>()
            var i = 0
            var j = 0
            var p = 0
            while (p < steps.size) {
                if (steps[p] == Step.SAME) {
                    rows.add(DiffLine(DiffLineType.CONTEXT, i + 1, j + 1, plain(old[i]), plain(new[j])))
                    i++
                    j++
                    p++
                } else {
                    val deletes = mutableListOf<Int>()
                    val adds = mutableListOf<Int>()
                    while (p < steps.size && steps[p] != Step.SAME) {
                        if (steps[p++] == Step.DELETE) deletes.add(i++) else adds.add(j++)
                    }
                    val paired = minOf(deletes.size, adds.size)
                    for (k in 0 until paired) {
                        val a = deletes[k]
                        val b = adds[k]
                        val (left, right) = inline(old[a], new[b], budget)
                        rows.add(DiffLine(DiffLineType.CHANGED, a + 1, b + 1, left, right))
                    }
                    for (k in paired until deletes.size) {
                        val a = deletes[k]
                        rows.add(DiffLine(DiffLineType.DELETED, a + 1, null, marked(old[a]), emptyList()))
                    }
                    for (k in paired until adds.size) {
                        val b = adds[k]
                        rows.add(DiffLine(DiffLineType.ADDED, null, b + 1, emptyList(), marked(new[b])))
                    }
                }
                if (rows.size > limits.maxOutputRows) throw WikitextLimitExceeded()
            }
            // Conservative bound for JSON escaping plus per-row and per-fragment metadata.
            var size = 2L
            for (row in rows) {
                size += 128
                for (part in row.from + row.to) size += 40L + 6L * part.text.length
                if (size > limits.maxOutputBytes) throw WikitextLimitExceeded()
            }
            budget.checkTime()
            DiffResult.Success(rows)
        } catch (_: WikitextLimitExceeded) {
            DiffResult.TooLarge
        }
    }

    private fun splitLines(source: String): List<String> {
        if (source.count { it == '\n' }.toLong() + 1 > limits.maxInputLines) throw WikitextLimitExceeded()
        return source.split('\n')
    }
    private fun plain(s: String) = listOf(DiffFragment(s, false))
    private fun marked(s: String) = listOf(DiffFragment(s, true))
    private enum class Step { SAME, DELETE, ADD }

    private fun <T> sequence(a: List<T>, b: List<T>, budget: Budget, equal: (T, T) -> Boolean): List<Step> {
        val cells = (a.size.toLong() + 1) * (b.size.toLong() + 1)
        budget.spend(cells)
        if (cells > Int.MAX_VALUE) throw WikitextLimitExceeded()
        val width = b.size + 1
        val table = IntArray(cells.toInt())
        for (i in a.indices.reversed()) {
            budget.spend(0)
            for (j in b.indices.reversed()) {
                table[i * width + j] = if (equal(a[i], b[j])) 1 + table[(i + 1) * width + j + 1]
                else maxOf(table[(i + 1) * width + j], table[i * width + j + 1])
            }
        }
        val result = ArrayList<Step>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && equal(a[i], b[j]) -> {
                    result.add(Step.SAME)
                    i++
                    j++
                }
                i < a.size && (j == b.size || table[(i + 1) * width + j] >= table[i * width + j + 1]) -> {
                    result.add(Step.DELETE)
                    i++
                }
                else -> {
                    result.add(Step.ADD)
                    j++
                }
            }
        }
        return result
    }

    private fun inline(a: String, b: String, budget: Budget): Pair<List<DiffFragment>, List<DiffFragment>> {
        val ac = a.codePoints().toArray().toList()
        val bc = b.codePoints().toArray().toList()
        val steps = sequence(ac, bc, budget) { x, y -> x == y }
        class Parts {
            private val parts = mutableListOf<DiffFragment>()
            private var changed = false
            private var text = StringBuilder()
            fun append(point: Int, isChanged: Boolean) {
                if (text.isNotEmpty() && changed != isChanged) flush()
                changed = isChanged
                text.appendCodePoint(point)
            }
            private fun flush() {
                if (text.isNotEmpty()) parts.add(DiffFragment(text.toString(), changed))
                text = StringBuilder()
            }
            fun result(): List<DiffFragment> {
                flush()
                return parts
            }
        }
        val left = Parts()
        val right = Parts()
        var i = 0
        var j = 0
        for (step in steps) when (step) {
            Step.SAME -> {
                left.append(ac[i++], false)
                right.append(bc[j++], false)
            }
            Step.DELETE -> left.append(ac[i++], true)
            Step.ADD -> right.append(bc[j++], true)
        }
        return left.result() to right.result()
    }
}
