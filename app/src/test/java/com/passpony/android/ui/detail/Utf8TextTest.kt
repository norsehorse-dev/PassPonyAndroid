package com.passpony.android.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Utf8TextTest {
    @Test
    fun decodeStrict_decodesValidUtf8() {
        assertEquals("hunter2", Utf8Text.decodeStrict("hunter2".toByteArray(Charsets.UTF_8)))
        assertEquals("caf\u00e9", Utf8Text.decodeStrict("caf\u00e9".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun decodeStrict_returnsNullForInvalidUtf8() {
        val invalid = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01, 0x02)
        assertNull(Utf8Text.decodeStrict(invalid))
    }

    @Test
    fun decodeStrict_emptyBytesDecodeToEmptyString() {
        assertEquals("", Utf8Text.decodeStrict(ByteArray(0)))
    }
}
