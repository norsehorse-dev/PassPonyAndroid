package com.passpony.android.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratorSettingsTest {

    @Test
    fun default_isPassGenerate() {
        val d = GeneratorSettings.DEFAULT
        assertEquals(25, d.length)
        assertTrue(d.lowercase && d.uppercase && d.digits)
        assertEquals(GeneratorSettings.Symbols.FULL, d.symbols)
        assertTrue(d.isValid)
    }

    @Test
    fun fromStored_missingValuesTakeTheDefaults() {
        assertEquals(GeneratorSettings.DEFAULT, GeneratorSettings.fromStored(null, null, null, null, null))
        val partial = GeneratorSettings.fromStored(19, null, null, null, "basic")
        assertEquals(19, partial.length)
        assertTrue(partial.lowercase && partial.uppercase && partial.digits)
        assertEquals(GeneratorSettings.Symbols.BASIC, partial.symbols)
    }

    @Test
    fun fromStored_clampsLengthIntoRange() {
        assertEquals(8, GeneratorSettings.fromStored(3, true, true, true, "full").length)
        assertEquals(128, GeneratorSettings.fromStored(500, true, true, true, "full").length)
        assertEquals(64, GeneratorSettings.fromStored(64, true, true, true, "full").length)
    }

    @Test
    fun fromStored_unknownSymbolsSpellingFallsBackToDefault() {
        assertEquals(GeneratorSettings.Symbols.FULL, GeneratorSettings.fromStored(20, true, true, true, "weird").symbols)
        assertEquals(GeneratorSettings.Symbols.NONE, GeneratorSettings.fromStored(20, true, true, true, "none").symbols)
    }

    @Test
    fun fromStored_noClassesAtAllFallsBackWhole() {
        // Only a corrupted store can produce this; generation must never
        // be left with an empty alphabet.
        assertEquals(GeneratorSettings.DEFAULT, GeneratorSettings.fromStored(20, false, false, false, "none"))
        // Symbols alone is a class.
        val symbolsOnly = GeneratorSettings.fromStored(20, false, false, false, "basic")
        assertTrue(symbolsOnly.hasAnyClass)
        assertEquals(20, symbolsOnly.length)
    }

    @Test
    fun isValid_needsAClassAndALengthInRange() {
        val none = GeneratorSettings(20, false, false, false, GeneratorSettings.Symbols.NONE)
        assertFalse(none.hasAnyClass)
        assertFalse(none.isValid)
        assertFalse(GeneratorSettings.DEFAULT.copy(length = 7).isValid)
        assertFalse(GeneratorSettings.DEFAULT.copy(length = 129).isValid)
        assertTrue(GeneratorSettings.DEFAULT.copy(length = 128).isValid)
        assertTrue(GeneratorSettings(8, false, false, true, GeneratorSettings.Symbols.NONE).isValid)
    }

    @Test
    fun symbols_storedSpellingsRoundTrip() {
        for (s in GeneratorSettings.Symbols.entries) {
            assertEquals(s, GeneratorSettings.Symbols.fromStored(s.stored))
        }
        assertNull(GeneratorSettings.Symbols.fromStored(null))
        assertNull(GeneratorSettings.Symbols.fromStored("Basic"))
    }

    @Test
    fun clampLength_bounds() {
        assertEquals(8, GeneratorSettings.clampLength(-5))
        assertEquals(128, GeneratorSettings.clampLength(1000))
        assertEquals(39, GeneratorSettings.clampLength(39))
    }
}
