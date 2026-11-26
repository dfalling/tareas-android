package com.dfalling.tareas.ui

/**
 * What a share intent contributes to a new todo (PLAN.md §7, Phase 5): a
 * [title] that is always non-blank, plus optional [notes] — both already cut
 * to the server limits from Appendix A, so the prefilled sheet can only be
 * saved successfully.
 */
data class ShareDraft(val title: String, val notes: String?) {
    companion object {
        /**
         * Maps a share payload (EXTRA_SUBJECT / EXTRA_TEXT as plain strings)
         * to a prefill draft, or null when there is nothing to share:
         *
         * - subject present → title = subject, notes = text;
         * - no subject → the text's first line becomes the title, the
         *   remainder becomes the notes;
         * - both blank → null (nothing worth a sheet).
         *
         * A subject is flattened to single-line text (the title is single-line
         * everywhere in this app), and both fields are cut to the server
         * limits by rune — Appendix A counts runes, not UTF-16 units.
         */
        fun from(subject: String?, text: String?): ShareDraft? {
            val cleanSubject = subject?.trim().orEmpty()
            val cleanText = text?.trim().orEmpty()
            if (cleanSubject.isEmpty() && cleanText.isEmpty()) return null

            if (cleanSubject.isNotEmpty()) {
                return ShareDraft(
                    title =
                        takeRunes(
                            cleanSubject.replace(Regex("[\\r\\n]+"), " "),
                            MAX_TITLE_RUNES,
                        ),
                    notes = takeRunes(cleanText, MAX_NOTES_RUNES).ifEmpty { null },
                )
            }

            // cleanText is trimmed, so its first line is never blank: a
            // leading-newline share ("\\n\\nfoo") lands on "foo" as the title.
            val newline = cleanText.indexOf('\n')
            val firstLine = if (newline < 0) cleanText else cleanText.substring(0, newline).trim()
            val remainder = if (newline < 0) "" else cleanText.substring(newline + 1).trim()
            return ShareDraft(
                title = takeRunes(firstLine, MAX_TITLE_RUNES),
                notes = takeRunes(remainder, MAX_NOTES_RUNES).ifEmpty { null },
            )
        }
    }
}
