package com.passpony.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.coreVersion
import uniffi.pass_ffi.entryFields
import uniffi.pass_ffi.entryPassword
import uniffi.pass_ffi.entryTotp
import uniffi.pass_ffi.verifyBackend

/**
 * Walking-skeleton proof that the FFI seam works end to end on-device:
 * the cross-compiled .so loads, the UniFFI Kotlin bindings call into it,
 * and a real PassStore round-trips through them. Mirrors pass-ffi's own
 * store_object_round_trips_through_ffi_surface test (see
 * PassPonyCore/crates/pass-ffi/src/store_api.rs) so the two stay in sync.
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
}
