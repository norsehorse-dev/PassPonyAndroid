package com.passpony.android.ui.settings

import com.passpony.android.crypto.PgpKeyStore
import org.junit.Assert.assertEquals
import org.junit.Test

class KeySummaryTest {

    @Test
    fun shortFingerprint_longerThan16_takesLast16WithEllipsis() {
        assertEquals("…0123456789ABCDEF", KeySummary.shortFingerprint("FEDCBA98760123456789ABCDEF"))
    }

    @Test
    fun shortFingerprint_exactly16_passesThroughUnchanged() {
        assertEquals("0123456789ABCDEF", KeySummary.shortFingerprint("0123456789ABCDEF"))
    }

    @Test
    fun shortFingerprint_shorterThan16_passesThroughUnchanged() {
        assertEquals("ABCDEF", KeySummary.shortFingerprint("ABCDEF"))
    }

    @Test
    fun summaryLine_plainKey_omitsV6Suffix() {
        val key = PgpKeyStore.KeyFileInfo(
            file = "alice.asc",
            primaryFingerprint = "FEDCBA98760123456789ABCDEF",
            algorithm = "RSA-4096",
            isV6 = false,
        )
        assertEquals("RSA-4096 · …0123456789ABCDEF", KeySummary.summaryLine(key))
    }

    @Test
    fun summaryLine_v6Key_appendsV6Suffix() {
        val key = PgpKeyStore.KeyFileInfo(
            file = "bob.asc",
            primaryFingerprint = "FEDCBA98760123456789ABCDEF",
            algorithm = "Ed25519",
            isV6 = true,
        )
        assertEquals("Ed25519 (v6) · …0123456789ABCDEF", KeySummary.summaryLine(key))
    }

    @Test
    fun formatGpgId_joinsWithNewlinesAndTrailingNewline() {
        assertEquals("AAAA\nBBBB\n", KeySummary.formatGpgId(listOf("AAAA", "BBBB")))
    }

    @Test
    fun formatGpgId_singleFingerprint() {
        assertEquals("AAAA\n", KeySummary.formatGpgId(listOf("AAAA")))
    }

    @Test
    fun formatGpgId_emptyList_isJustTheTrailingNewline() {
        // joinToString always appends postfix, even with nothing to join.
        assertEquals("\n", KeySummary.formatGpgId(emptyList()))
    }

    @Test
    fun parseGpgId_dropsBlankLinesAndTrims() {
        assertEquals(listOf("AAAA", "BBBB"), KeySummary.parseGpgId("  AAAA  \n\nBBBB\n\n"))
    }

    @Test
    fun parseGpgId_emptyText_isEmptyList() {
        assertEquals(emptyList<String>(), KeySummary.parseGpgId(""))
    }
}
