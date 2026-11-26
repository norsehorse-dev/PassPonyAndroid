package com.passpony.android.ui

import com.passpony.android.ui.util.KeyDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyDisplayTest {
    private val classic = "age1r5lvvt8c5hkmrmurkk53gwq6vmu03cyu720l37nvsp0vtdwzqalsmak33z"
    private val pq = "age1pq1" + "q".repeat(1944) + "abcdefgh"

    @Test
    fun classicKeysAreShownWhole() {
        assertFalse(KeyDisplay.isLong(classic))
        assertEquals(classic, KeyDisplay.short("  $classic\n"))
    }

    @Test
    fun postQuantumKeysGetPrefixAndTail() {
        assertTrue(KeyDisplay.isLong(pq))
        assertEquals("age1pq1\u2026abcdefgh", KeyDisplay.short(pq))
        assertEquals("age1tagpq1\u2026abcdefgh", KeyDisplay.short("age1tagpq1" + "q".repeat(1990) + "abcdefgh"))
    }

    @Test
    fun fingerprintIsSha256OfTheKeyAsWritten() {
        // printf %s abc | sha256sum starts ba7816bf 8f01cfea 414140de 5dae2223
        assertEquals("ba78 16bf 8f01 cfea 4141 40de 5dae 2223", KeyDisplay.fingerprint("abc"))
        assertEquals(KeyDisplay.fingerprint("abc"), KeyDisplay.fingerprint("  ABC\n"))
    }
}
