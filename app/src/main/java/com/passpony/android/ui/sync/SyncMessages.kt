package com.passpony.android.ui.sync

import uniffi.pass_ffi.SyncOutcome

/**
 * Two small pieces of pure text-shaping logic ported verbatim from
 * PassPony iOS's SyncView: redacting embedded remote-URL credentials
 * for display, and describing a completed sync's outcome. Kept
 * framework-free (no Context, no string resources) so both are plain
 * JVM unit tests, like the rest of this app's pure logic (EntryEditState,
 * PasswordGenerator, ...). Like AddEntryScreen's on-disk field labels,
 * this is git/URL mechanics rather than app chrome, so it's deliberately
 * not run through the localization layer yet -- P13 is where that
 * happens for the whole app.
 */
object SyncMessages {

    /**
     * Strip userinfo (user:token) from a URL for display, e.g.
     * `https://user:token@host/x.git` -> `https://•••@host/x.git`.
     * A URL with no scheme separator, or no userinfo, passes through
     * unchanged. Port of SyncView.redacted(_:).
     */
    fun redacted(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val afterScheme = schemeEnd + 3
        val at = url.indexOf('@', afterScheme)
        if (at < 0) return url
        return url.substring(0, afterScheme) + "•••@" + url.substring(at + 1)
    }

    /** Port of SyncView.describe(_:). */
    fun describe(outcome: SyncOutcome): String = when (outcome) {
        is SyncOutcome.UpToDate -> "Already up to date."
        is SyncOutcome.FastForwarded -> "Pulled changes from remote."
        is SyncOutcome.Rebased ->
            "Merged cleanly (${outcome.replayed} local changes replayed)."
        is SyncOutcome.ResolvedConflicts -> {
            var text = "Resolved ${outcome.resolved.size} conflicts."
            if (outcome.keptBoth.isNotEmpty()) {
                text += " Kept both for: ${outcome.keptBoth.joinToString(", ")}."
            }
            text
        }
    }
}
