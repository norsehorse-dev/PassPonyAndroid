package com.passpony.android.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGeneratorTest {
    @Test
    fun generate_defaultLengthIsTwentyFive() {
        assertEquals(25, PasswordGenerator.generate().length)
    }

    @Test
    fun generate_respectsExplicitLength() {
        assertEquals(10, PasswordGenerator.generate(length = 10).length)
        assertEquals(0, PasswordGenerator.generate(length = 0).length)
    }

    @Test
    fun generate_onlyUsesCharactersFromTheDefinedCharset() {
        val charsetSet = PasswordGenerator.CHARSET.toSet()
        repeat(20) {
            val generated = PasswordGenerator.generate(length = 200)
            assertTrue(generated.all { it in charsetSet })
        }
    }

    @Test
    fun charset_hasNinetyFourUniquePrintableAsciiCharacters() {
        assertEquals(94, PasswordGenerator.CHARSET.length)
        assertEquals(94, PasswordGenerator.CHARSET.toSet().size)
        assertTrue(PasswordGenerator.CHARSET.none { it == ' ' })
        assertTrue(PasswordGenerator.CHARSET.all { it.code in 0x21..0x7E })
    }
}
