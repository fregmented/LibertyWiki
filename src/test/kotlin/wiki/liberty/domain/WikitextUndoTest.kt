// SPDX-License-Identifier: AGPL-3.0-only
package wiki.liberty.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WikitextUndoTest {
    private val undo = WikitextUndo()

    @Test
    fun `preserves later changes separated on either side`() {
        assertEquals(UndoResult.Applied("first later\nleft\noriginal\nright\nlast later"), undo.apply(
            "first\nleft\noriginal\nright\nlast", "first\nleft\nchanged\nright\nlast",
            "first later\nleft\nchanged\nright\nlast later",
        ))
    }

    @Test
    fun `restores a deletion and removes an insertion`() {
        assertEquals(UndoResult.Applied("a\nb\nc\nd\ne later"), undo.apply("a\nb\nc\nd\ne", "a\nc\nd\ne", "a\nc\nd\ne later"))
        assertEquals(UndoResult.Applied("a\nb\nc\nd later"), undo.apply("a\nb\nc\nd", "a\nnew\nb\nc\nd", "a\nnew\nb\nc\nd later"))
    }

    @Test
    fun `maps multiple inverse changes across later insertion and deletion`() {
        assertEquals(UndoResult.Applied("a\nleft\nold one\nb\nc\nold two\nd\ne\nadded"), undo.apply(
            "remove\na\nleft\nold one\nb\nc\nold two\nd\ne",
            "remove\na\nleft\nnew one\nb\nc\nnew two\nd\ne",
            "a\nleft\nnew one\nb\nc\nnew two\nd\ne\nadded",
        ))
    }

    @Test
    fun `rejects overlaps boundary insertions and adjacent changes`() {
        assertEquals(UndoResult.Conflict, undo.apply("a\nold\nz", "a\nnew\nz", "a\nlater\nz"))
        assertEquals(UndoResult.Conflict, undo.apply("a\nold\nz", "a\nnew\nz", "a\nnew\nnew\nz"))
        assertEquals(UndoResult.Conflict, undo.apply("a\nold\nz", "a\nnew\nz", "a\nextra\nnew\nz"))
        assertEquals(UndoResult.Conflict, undo.apply("old\nb", "new\nb", "new\nB"))
    }

    @Test
    fun `preserves trailing newline and unicode`() {
        assertEquals(UndoResult.Applied("alpha\n이전 🌿\nseparator\nomega changed\n"), undo.apply(
            "alpha\n이전 🌿\nseparator\nomega\n", "alpha\n이후 🌱\nseparator\nomega\n",
            "alpha\n이후 🌱\nseparator\nomega changed\n",
        ))
    }

    @Test
    fun `allows repeated blank paragraph separators with unique alignment`() {
        assertEquals(UndoResult.Applied("Intro\n\nOld para\n\nEnd more"), undo.apply(
            "Intro\n\nOld para\n\nEnd", "Intro\n\nNew para\n\nEnd", "Intro\n\nNew para\n\nEnd more",
        ))
    }

    @Test
    fun `rejects ambiguous matching of repeated deleted lines`() {
        assertEquals(UndoResult.Conflict, undo.apply("h\nx\ny\nx\nz", "h\nx\nz", "h\nNEW\nx\nz"))
    }

    @Test
    fun `rejects crossing optimal matches`() {
        assertEquals(UndoResult.Conflict, undo.apply("B\nA", "A\nB", "A\nB\nC"))
    }

    @Test
    fun `handles changes at beginning and end`() {
        assertEquals(UndoResult.Applied("old\nseparator\nmiddle changed\nend"), undo.apply(
            "old\nseparator\nmiddle\nend", "new\nseparator\nmiddle\nend", "new\nseparator\nmiddle changed\nend",
        ))
        assertEquals(UndoResult.Applied("start changed\nmiddle\nseparator\nold"), undo.apply(
            "start\nmiddle\nseparator\nold", "start\nmiddle\nseparator\nnew", "start changed\nmiddle\nseparator\nnew",
        ))
    }

    @Test
    fun `compares long lines without character diff`() {
        val long = "a".repeat(100_000)
        assertEquals(UndoResult.Applied("$long\nold\nseparator\nend changed"), undo.apply(
            "$long\nold\nseparator\nend", "$long\nnew\nseparator\nend", "$long\nnew\nseparator\nend changed",
        ))
    }

    @Test
    fun `rejects bounded comparison exhaustion`() {
        val lines = WikitextUndo(WikitextUndoLimits(maxInputLines = 2))
        assertEquals(UndoResult.TooLarge, lines.apply("a\nb\nc", "A\nb\nc", "A\nb\nC"))
        val limited = WikitextUndo(WikitextUndoLimits(maxWork = 1))
        assertEquals(UndoResult.TooLarge, limited.apply("a\nb", "a\nc", "d\nc"))
    }
}
