package com.passpony.android.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class TotpFormatTest {
    @Test
    fun group_splitsSixDigitCodesInHalf() {
        assertEquals("123 456", TotpFormat.group("123456"))
    }

    @Test
    fun group_splitsEightDigitCodesInHalf() {
        assertEquals("1234 5678", TotpFormat.group("12345678"))
    }

    @Test
    fun group_leavesOtherLengthsUngrouped() {
        assertEquals("1234", TotpFormat.group("1234"))
        assertEquals("1234567", TotpFormat.group("1234567"))
        assertEquals("", TotpFormat.group(""))
    }
}
