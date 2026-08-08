package com.passpony.android.ui.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.pass_ffi.SyncOutcome

class SyncMessagesTest {

    @Test
    fun redacted_stripsUserinfoAfterScheme() {
        assertEquals(
            "https://•••@host/you/store.git",
            SyncMessages.redacted("https://user:token@host/you/store.git")
        )
    }

    @Test
    fun redacted_stopsAtTheFirstAtSign() {
        // A path segment containing its own '@' must not confuse the
        // redaction -- only the userinfo delimiter (the first '@' after
        // the scheme) is special.
        assertEquals(
            "https://•••@host/path@extra",
            SyncMessages.redacted("https://user:token@host/path@extra")
        )
    }

    @Test
    fun redacted_passesThroughWhenNoScheme() {
        assertEquals("user@host/path", SyncMessages.redacted("user@host/path"))
    }

    @Test
    fun redacted_passesThroughWhenNoUserinfo() {
        assertEquals("https://host/you/store.git", SyncMessages.redacted("https://host/you/store.git"))
    }

    @Test
    fun redacted_passesThroughEmptyString() {
        assertEquals("", SyncMessages.redacted(""))
    }

    @Test
    fun describe_upToDate() {
        assertEquals("Already up to date.", SyncMessages.describe(SyncOutcome.UpToDate))
    }

    @Test
    fun describe_fastForwarded() {
        assertEquals("Pulled changes from remote.", SyncMessages.describe(SyncOutcome.FastForwarded))
    }

    @Test
    fun describe_rebased() {
        assertEquals(
            "Merged cleanly (3 local changes replayed).",
            SyncMessages.describe(SyncOutcome.Rebased(3u))
        )
    }

    @Test
    fun describe_resolvedConflictsWithoutKeepBoth() {
        assertEquals(
            "Resolved 2 conflicts.",
            SyncMessages.describe(SyncOutcome.ResolvedConflicts(listOf("a.age", "b.age"), emptyList()))
        )
    }

    @Test
    fun describe_resolvedConflictsWithKeepBoth() {
        assertEquals(
            "Resolved 1 conflicts. Kept both for: shared.conflict.age.",
            SyncMessages.describe(
                SyncOutcome.ResolvedConflicts(listOf("shared.age"), listOf("shared.conflict.age"))
            )
        )
    }
}
