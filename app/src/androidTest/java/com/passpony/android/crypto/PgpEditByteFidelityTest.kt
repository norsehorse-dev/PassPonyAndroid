package com.passpony.android.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.entrySetField

/**
 * P08's byte-faithful edit guarantee (plan section 1's P1 round-trip
 * rule), exercised end to end through the real engine rather than
 * PassPonyCore's own Rust-level entry.rs tests: edit one field of a
 * deliberately odd-formatted entry (no-space-after-colon field, a
 * non-field plain line, a blank line, a trailing-spaces line, a
 * non-field otpauth:// line, and no trailing newline on the last line
 * -- see make-edit-fixture.sh) and confirm every byte outside the
 * edited value's own slice survives untouched.
 */
@RunWith(AndroidJUnit4::class)
class PgpEditByteFidelityTest {

    private fun asset(path: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("pass-edit-minimal/$path").use { it.readBytes() }

    @Test
    fun editingOneFieldLeavesEveryOtherByteUntouched() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()

        val keyDir = PgpKeyStore.keyDirectory(appContext)
        keyDir.listFiles()?.forEach { it.delete() }
        File(keyDir, "identity.asc").writeBytes(asset("identity.asc"))

        val storeDir = File(appContext.cacheDir, "pgp-edit-fixture-store")
        storeDir.deleteRecursively()
        storeDir.mkdirs()
        File(storeDir, "gamma.gpg").writeBytes(asset("store/gamma.gpg"))
        // aapt drops dotfiles from packaged test assets (see
        // PgpEngineFixtureTest), so the fixture is committed as
        // store/gpg-id and written to disk under the real .gpg-id name.
        File(storeDir, ".gpg-id").writeBytes(asset("store/gpg-id"))

        val engine = PgpEngine.load(appContext, passphrase = null)
        val store = PassStore.open(storeDir.absolutePath, StoreFormat.PASS)

        val original = store.readEntry("gamma", engine)
        assertArrayEquals(asset("goldens/gamma.plain"), original)
        val originalText = String(original, Charsets.UTF_8)
        assertTrue(originalText.contains("username:kevin"))

        val edited = entrySetField(original, key = "username", value = "kstewart")
        store.writeEntry("gamma", edited, engine)
        val reread = store.readEntry("gamma", engine)

        // Derived-string comparison: readable statement of the expected
        // result -- only "kevin" became "kstewart", nothing else moved.
        val expectedText = originalText.replaceFirst("username:kevin", "username:kstewart")
        assertArrayEquals(expectedText.toByteArray(Charsets.UTF_8), reread)

        // Explicit byte-range comparison against the ORIGINAL bytes
        // (not the derived string) for the actual round-trip guarantee:
        // the password line, the "username:" key and colon, the plain
        // line, the blank line, the trailing-spaces line, the otpauth
        // line, and the un-terminated final field line are byte-for-byte
        // identical before and after -- only the value slice differs.
        val valueStart = originalText.indexOf("username:kevin") + "username:".length
        val oldValueEnd = valueStart + "kevin".length
        val newValueEnd = valueStart + "kstewart".length
        assertArrayEquals(original.copyOfRange(0, valueStart), reread.copyOfRange(0, valueStart))
        assertArrayEquals(
            original.copyOfRange(oldValueEnd, original.size),
            reread.copyOfRange(newValueEnd, reread.size)
        )
    }
}
