package com.dfalling.tareas.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Share-target parsing (PLAN.md §7 Phase 5): subject→title/text→notes,
 * first-line fallback, server-limit cuts by rune. The intent→string step lives
 * in MainActivity; everything here is the pure core.
 */
class ShareDraftTest {
    @Test
    fun subjectBecomesTitleAndTextBecomesNotes() {
        assertEquals(ShareDraft("Buy milk", "2 liters"), ShareDraft.from("Buy milk", "2 liters"))
    }

    @Test
    fun subjectOnlyYieldsNullNotes() {
        assertEquals(ShareDraft("Buy milk", null), ShareDraft.from("Buy milk", null))
    }

    @Test
    fun whitespaceOnlyTextYieldsNullNotes() {
        assertEquals(ShareDraft("Title", null), ShareDraft.from("Title", "   \n "))
    }

    @Test
    fun blankSubjectFallsThroughToFirstLine() {
        assertEquals(ShareDraft("Buy milk", null), ShareDraft.from("   ", "Buy milk"))
    }

    @Test
    fun firstLineBecomesTitleAndRemainderNotes() {
        assertEquals(
            ShareDraft("Buy milk", "2 liters\nand eggs"),
            ShareDraft.from(null, "Buy milk\n2 liters\nand eggs"),
        )
    }

    @Test
    fun singleLineTextHasNoNotes() {
        assertEquals(ShareDraft("Buy milk", null), ShareDraft.from(null, "Buy milk"))
    }

    @Test
    fun leadingBlankLinesAreSkipped() {
        // trim() first, so the first line after it is never blank.
        assertEquals(
            ShareDraft("Buy milk", "2 liters"),
            ShareDraft.from(null, "\n\n  Buy milk\n2 liters"),
        )
    }

    @Test
    fun nothingToShareYieldsNull() {
        assertNull(ShareDraft.from(null, null))
        assertNull(ShareDraft.from("", ""))
        assertNull(ShareDraft.from("  \n ", " \t "))
    }

    @Test
    fun multilineSubjectIsFlattenedToSingleLine() {
        assertEquals(ShareDraft("Buy milk 2 liters", null), ShareDraft.from("Buy milk\n2 liters", null))
        assertEquals(ShareDraft("A B", null), ShareDraft.from("A\r\n\r\nB", null))
    }

    @Test
    fun subjectCutToFiveHundredRunes() {
        val subject = "😀".repeat(520) // 520 code points = 1040 UTF-16 units
        val draft = ShareDraft.from(subject, null)
        assertEquals(500, draft!!.title.codePointCount(0, draft.title.length))
        assertEquals("😀", draft.title.takeLast(2)) // cut on a whole rune, not mid-surrogate
    }

    @Test
    fun firstLineBeyondLimitIsCut() {
        val draft = ShareDraft.from(null, "${"a".repeat(600)}\nrest")
        assertEquals("a".repeat(500), draft!!.title)
        assertEquals("rest", draft.notes)
    }

    @Test
    fun notesCutToTenThousandRunes() {
        val draft = ShareDraft.from("Title", "x".repeat(10_001))
        assertEquals(10_000, draft!!.notes!!.length)
    }
}
