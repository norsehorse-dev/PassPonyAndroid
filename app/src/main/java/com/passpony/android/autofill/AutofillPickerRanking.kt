package com.passpony.android.autofill

import uniffi.pass_ffi.EntryRef

/**
 * The picker's entry ordering, port of iOS's AutofillPickerView.filtered
 * exactly: with no search text, every visible entry is shown, just
 * reordered so entries matching the service hint (a needle) come first
 * -- with search text, it's a real filter down to only the matches
 * (typing narrows the list rather than merely reordering it).
 */
object AutofillPickerRanking {
    fun filtered(entries: List<EntryRef>, serviceHint: String, search: String): List<EntryRef> {
        val visible = entries.filter { !it.hidden }
        val needle = search.ifEmpty { serviceHint }
        if (needle.isEmpty()) return visible
        val lowered = needle.lowercase()
        val hits = visible.filter { it.name.lowercase().contains(lowered) }
        return if (search.isEmpty()) hits + visible.filterNot { hits.contains(it) } else hits
    }
}
