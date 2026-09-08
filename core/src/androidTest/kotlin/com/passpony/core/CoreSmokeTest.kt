package com.passpony.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.GenerateException
import uniffi.pass_ffi.GeneratorSpec
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.SymbolSet
import uniffi.pass_ffi.TotpAlgorithm
import uniffi.pass_ffi.TotpException
import uniffi.pass_ffi.coreVersion
import uniffi.pass_ffi.entryFields
import uniffi.pass_ffi.entryIsOtpOnly
import uniffi.pass_ffi.entryPassword
import uniffi.pass_ffi.entryRemoveOtpauth
import uniffi.pass_ffi.entrySetOtpauth
import uniffi.pass_ffi.entryTotp
import uniffi.pass_ffi.entryTotpSummary
import uniffi.pass_ffi.generatePassword
import uniffi.pass_ffi.generatorCharset
import uniffi.pass_ffi.generatorPassDefault
import uniffi.pass_ffi.generatorSymbols
import uniffi.pass_ffi.totpBuildUri
import uniffi.pass_ffi.totpDescribe
import uniffi.pass_ffi.totpValidateSecret
import uniffi.pass_ffi.verifyBackend

/**
 * Walking-skeleton proof that the FFI seam works end to end on-device:
 * the cross-compiled .so loads, the UniFFI Kotlin bindings call into it,
 * and a real PassStore round-trips through them. Mirrors pass-ffi's own
 * store_object_round_trips_through_ffi_surface test (see
 * PassPonyCore/crates/pass-ffi/src/store_api.rs) so the two stay in sync.
 *
 * The 1.1 helpers (otpauth line operations, TOTP URI building, the
 * generator) get one call each here too, mirroring the core's
 * otp_helpers_over_the_ffi_surface and generator_over_the_ffi_surface
 * tests: a stale binding then fails on the emulator, not on a phone.
 */
@RunWith(AndroidJUnit4::class)
class CoreSmokeTest {

    /** Same stand-in backend the core's own tests use: not real crypto. */
    private object FlipBackend : CryptoBackend {
        // Kotlin's Byte has no inv(); only Int/Long do. Round-trip through
        // Int to get the same bitwise NOT Rust's FlipBackend does on u8.
        override fun encrypt(plaintext: ByteArray, recipients: List<String>): ByteArray =
            ByteArray(plaintext.size) { i -> plaintext[i].toInt().inv().toByte() }

        override fun decrypt(ciphertext: ByteArray): ByteArray =
            ByteArray(ciphertext.size) { i -> ciphertext[i].toInt().inv().toByte() }
    }

    @Test
    fun coreVersionIsNonEmpty() {
        assertTrue(coreVersion().isNotEmpty())
    }

    @Test
    fun verifyBackendRoundTripsThroughFlipBackend() {
        verifyBackend(FlipBackend)
    }

    @Test
    fun passStoreRoundTripsThroughFfiSurface() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "passpony-core-smoke-store")
        dir.deleteRecursively()
        dir.mkdirs()

        val store = PassStore.open(dir.absolutePath, StoreFormat.PASSAGE)
        val content = "pw\nusername: kevin\notpauth://totp/X?secret=JBSWY3DPEHPK3PXP\n"
            .toByteArray(Charsets.UTF_8)

        store.writeEntry("web/example", content, FlipBackend)
        val read = store.readEntry("web/example", FlipBackend)
        assertArrayEquals(content, read)

        assertArrayEquals("pw".toByteArray(Charsets.UTF_8), entryPassword(read))
        assertEquals(1, entryFields(read).size)

        val totp = entryTotp(read, 59uL)
        assertNotNull(totp)
        assertEquals(6, totp!!.code.length)
        assertEquals(1uL, totp.secondsRemaining)

        val names = store.entries().map { it.name }
        assertEquals(listOf("web/example"), names)
    }

    @Test
    fun otpHelpersWorkThroughFfiSurface() {
        val uri = totpBuildUri(
            secret = "jbsw y3dp ehpk 3pxp",
            account = "kevin",
            issuer = "Example",
            algorithm = TotpAlgorithm.SHA1,
            digits = 6u,
            period = 30uL,
        )
        assertEquals("otpauth://totp/Example:kevin?secret=JBSWY3DPEHPK3PXP&issuer=Example", uri)

        val summary = totpDescribe("  $uri\n")
        assertEquals("Example:kevin", summary.label)
        assertEquals("Example", summary.issuer)
        assertEquals(TotpAlgorithm.SHA1, summary.algorithm)
        assertEquals(6u, summary.digits)
        assertEquals(30uL, summary.period)
        assertEquals(10u, totpValidateSecret("JBSWY3DPEHPK3PXP"))

        try {
            totpDescribe("otpauth://hotp/X?secret=JBSWY3DP")
            fail("HOTP must be rejected")
        } catch (e: TotpException.NotTotpUri) {
            // expected
        }
        try {
            totpBuildUri("", "k", null, TotpAlgorithm.SHA1, 6u, 30uL)
            fail("an empty secret must be rejected")
        } catch (e: TotpException.MissingSecret) {
            // expected
        }

        // Append to a plain entry, read it back, replace, remove.
        val plain = "pw\nusername: kevin\n".toByteArray(Charsets.UTF_8)
        assertNull(entryTotpSummary(plain))
        assertFalse(entryIsOtpOnly(plain))
        val withOtp = entrySetOtpauth(plain, uri)
        assertArrayEquals("pw\nusername: kevin\n$uri\n".toByteArray(Charsets.UTF_8), withOtp)
        assertEquals(summary, entryTotpSummary(withOtp))
        assertNotNull(entryTotp(withOtp, 59uL))
        val uri2 = "otpauth://totp/Other?secret=MZXW6YTBOI"
        val replaced = entrySetOtpauth(withOtp, uri2)
        assertArrayEquals("pw\nusername: kevin\n$uri2\n".toByteArray(Charsets.UTF_8), replaced)
        assertArrayEquals(plain, entryRemoveOtpauth(replaced))

        // An OTP-only entry (line 1 is the URI, what `pass otp insert`
        // writes) reads as a code and refuses removal.
        val otpOnly = "$uri\n".toByteArray(Charsets.UTF_8)
        assertTrue(entryIsOtpOnly(otpOnly))
        assertNotNull(entryTotp(otpOnly, 59uL))
        assertArrayEquals(otpOnly, entryRemoveOtpauth(otpOnly))
    }

    @Test
    fun generatorWorksThroughFfiSurface() {
        val spec = generatorPassDefault()
        assertEquals(25u, spec.length)
        assertEquals(
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" +
                "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
            generatorCharset(spec),
        )
        assertEquals(25, generatePassword(spec).length)

        val portal = GeneratorSpec(
            length = 19u,
            lowercase = true,
            uppercase = true,
            digits = true,
            symbols = SymbolSet.BASIC,
        )
        val basic = generatorSymbols(SymbolSet.BASIC)
        assertEquals("!#\$%&()*+,-.=?@_", basic)
        assertEquals("", generatorSymbols(SymbolSet.NO_SYMBOLS))
        repeat(20) {
            val pw = generatePassword(portal)
            assertEquals(19, pw.length)
            assertTrue(pw, pw.all { it.isLetterOrDigit() || basic.contains(it) })
        }

        try {
            generatePassword(spec.copy(length = 4u))
            fail("a length below 8 must be rejected")
        } catch (e: GenerateException.BadLength) {
            assertEquals(8u, e.min)
            assertEquals(128u, e.max)
        }
        try {
            generatePassword(
                GeneratorSpec(
                    length = 20u,
                    lowercase = false,
                    uppercase = false,
                    digits = false,
                    symbols = SymbolSet.NO_SYMBOLS,
                ),
            )
            fail("no classes must be rejected")
        } catch (e: GenerateException.NoClasses) {
            // expected
        }
    }
}
