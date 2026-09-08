package com.passpony.android.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.passpony.android.ui.edit.PasswordGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.SymbolSet
import uniffi.pass_ffi.generatorPassDefault
import uniffi.pass_ffi.generatorSymbols

/**
 * The Kotlin-side defaults and the core's are the same thing, and the
 * one-tap and sheet paths both go through the core: a fresh install
 * generates exactly as 1.0.5 did, a saved setting survives a reload, and
 * the portal case from issue #5 (19 characters, basic punctuation) holds
 * through the app's own wrapper.
 */
@RunWith(AndroidJUnit4::class)
class GeneratorDefaultsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun kotlinDefaultIsTheCoreDefault() {
        assertEquals(generatorPassDefault(), GeneratorSettings.DEFAULT.toSpec())
    }

    @Test
    fun freshInstallGeneratesTwentyFiveFromTheFullSet() {
        runBlocking { GeneratorPrefs.save(context, GeneratorSettings.DEFAULT) }
        val full = generatorSymbols(SymbolSet.FULL)
        repeat(20) {
            val pw = PasswordGenerator.generate(context)
            assertEquals(25, pw.length)
            assertTrue(pw, pw.all { it.isLetterOrDigit() || full.contains(it) })
        }
    }

    @Test
    fun savedSettingsRoundTripAndDriveOneTapGenerate() {
        val portal = GeneratorSettings(
            length = 19,
            lowercase = true,
            uppercase = true,
            digits = true,
            symbols = GeneratorSettings.Symbols.BASIC,
        )
        runBlocking { GeneratorPrefs.save(context, portal) }
        assertEquals(portal, GeneratorPrefs.snapshot(context))
        val basic = generatorSymbols(SymbolSet.BASIC)
        repeat(200) {
            val pw = PasswordGenerator.generate(context)
            assertEquals(19, pw.length)
            assertTrue(pw, pw.all { it.isLetterOrDigit() || basic.contains(it) })
            assertTrue(pw, pw.any { it.isLowerCase() })
            assertTrue(pw, pw.any { it.isUpperCase() })
            assertTrue(pw, pw.any { it.isDigit() })
            assertTrue(pw, pw.any { basic.contains(it) })
        }
        // Leave the device as a fresh install would be.
        runBlocking { GeneratorPrefs.save(context, GeneratorSettings.DEFAULT) }
    }
}
