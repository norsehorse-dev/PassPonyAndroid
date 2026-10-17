package com.passpony.android.ui.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pass_ffi.MigrationAccount
import uniffi.pass_ffi.MigrationBatch
import uniffi.pass_ffi.OtpKind

class MigrationCollectorTest {

    private fun batch(id: Long, index: Int, size: Int, vararg names: String) = MigrationBatch(
        accounts = names.map {
            MigrationAccount(account = it, issuer = null, kind = OtpKind.TOTP, uri = "otpauth://totp/$it?secret=A", suggestedName = it)
        },
        skipped = emptyList(),
        batchSize = size.toUInt(),
        batchIndex = index.toUInt(),
        batchId = id,
    )

    @Test
    fun collectsAMultiCodeExportInAnyOrder() {
        val c = MigrationCollector()
        assertEquals(MigrationCollector.Outcome.Added(1, 3), c.add(batch(7, 2, 3, "c")))
        assertFalse(c.complete)
        assertEquals(MigrationCollector.Outcome.Duplicate(1, 3), c.add(batch(7, 2, 3, "c")))
        assertEquals(MigrationCollector.Outcome.Added(2, 3), c.add(batch(7, 0, 3, "a")))
        assertEquals(MigrationCollector.Outcome.OtherExport, c.add(batch(8, 1, 3, "x")))
        assertEquals(MigrationCollector.Outcome.Added(3, 3), c.add(batch(7, 1, 3, "b")))
        assertTrue(c.complete)
        assertEquals(listOf("a", "b", "c"), c.accounts().map { it.account })
    }

    @Test
    fun singleCodeExportIsCompleteAtOnce() {
        val c = MigrationCollector()
        c.add(batch(1, 0, 1, "a", "b"))
        assertTrue(c.complete)
        assertEquals(2, c.accounts().size)
    }

    @Test
    fun nameProblems() {
        val existing = setOf("Bank/me")
        val problems = ImportNames.problems(listOf("Bank/me", "a", " a ", "", "b")) { it in existing }
        assertEquals(
            listOf(ImportNames.Problem.EXISTS, null, ImportNames.Problem.DUPLICATE, ImportNames.Problem.BLANK, null),
            problems
        )
    }
}
