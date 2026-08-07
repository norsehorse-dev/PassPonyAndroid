package com.passpony.android.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordMaskTest {
    @Test
    fun maskLength_floorsAtEightForShortPasswords() {
        assertEquals(8, PasswordMask.maskLength(0))
        assertEquals(8, PasswordMask.maskLength(3))
        assertEquals(8, PasswordMask.maskLength(8))
    }

    @Test
    fun maskLength_matchesByteCountAboveTheFloor() {
        assertEquals(12, PasswordMask.maskLength(12))
        assertEquals(40, PasswordMask.maskLength(40))
    }

    @Test
    fun mask_producesThatManyBulletCharacters() {
        assertEquals("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022", PasswordMask.mask(3))
        assertEquals(12, PasswordMask.mask(12).length)
        assertTrue(PasswordMask.mask(12).all { it == '\u2022' })
    }
}
