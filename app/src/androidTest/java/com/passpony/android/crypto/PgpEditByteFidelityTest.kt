package com.passpony.android.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.entryIsOtpOnly
import uniffi.pass_ffi.entryRemoveOtpauth
import uniffi.pass_ffi.entrySetField
import uniffi.pass_ffi.entrySetOtpauth
import uniffi.pass_ffi.entryTotpSummary

/**
 * P08's byte-faithful edit guarantee (plan section 1's P1 round-trip
 * rule), exercised end to end through the real engine rather than
 * PassPonyCore's own Rust-level entry.rs tests: edit one field of a
 * deliberately odd-formatted entry (no-space-after-colon field, a
 * non-field plain line, a blank line, a trailing-spaces line, a
 * non-field otpauth:// line, and no trailing newline on the last line
 * -- see make-edit-fixture.sh) and confirm every byte outside the
 * edited value's own slice survives untouched. 1.1 adds the same
 * check for the otpauth:// line's own operations: replace, remove, and
 * append, through the same store and engine.
 */
@RunWith(AndroidJUnit4::class)
class PgpEditByteFidelityTest {

    private fun asset(path: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("pass-edit-minimal/$path").use { it.readBytes() }

    private class Fixture(val store: PassStore, val engine: PgpEngine)

    private fun openFixture(dirName: String): Fixture {
        val appContext = ApplicationProvider.getApplicationContext<Context>()

        val keyDir = PgpKeyStore.keyDirectory(appContext)
        keyDir.listFiles()?.forEach { it.delete() }
        File(keyDir, "identity.asc").writeBytes(asset("identity.asc"))

        val storeDir = File(appContext.cacheDir, dirName)
        storeDir.deleteRecursively()
        storeDir.mkdirs()
        File(storeDir, "gamma.gpg").writeBytes(asset("store/gamma.gpg"))
        // aapt drops dotfiles from packaged test assets (see
        // PgpEngineFixtureTest), so the fixture is committed as
        // store/gpg-id and written to disk under the real .gpg-id name.
        File(storeDir, ".gpg-id").writeBytes(asset("store/gpg-id"))

        val engine = PgpEngine.load(appContext, passphrase = null)
        return Fixture(PassStore.open(storeDir.absolutePath, StoreFormat.PASS), engine)
    }

    @Test
    fun editingOneFieldLeavesEveryOtherByteUntouched() {
        val fixture = openFixture("pgp-edit-fixture-store")
        val store = fixture.store
        val engine = fixture.engine

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

    @Test
    fun otpauthOperationsLeaveEveryOtherByteUntouched() {
        val fixture = openFixture("pgp-otp-fixture-store")
        val store = fixture.store
        val engine = fixture.engine

        val original = store.readEntry("gamma", engine)
        val originalText = String(original, Charsets.UTF_8)
        val oldUri = "otpauth://totp/Example:kevin?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        val newUri = "otpauth://totp/Other:kevin?secret=MZXW6YTBOI&issuer=Other&digits=8"
        assertTrue(originalText.contains("$oldUri\n"))
        assertFalse(entryIsOtpOnly(original))
        assertEquals("Example:kevin", entryTotpSummary(original)!!.label)

        // Replace: only the URI's own text changes; the line ending and
        // everything around it, including the un-terminated last line
        // after it, stay byte-identical.
        val replaced = entrySetOtpauth(original, newUri)
        store.writeEntry("gamma", replaced, engine)
        val rereadReplaced = store.readEntry("gamma", engine)
        assertArrayEquals(
            originalText.replaceFirst(oldUri, newUri).toByteArray(Charsets.UTF_8),
            rereadReplaced
        )
        val uriStart = originalText.indexOf(oldUri)
        assertArrayEquals(original.copyOfRange(0, uriStart), rereadReplaced.copyOfRange(0, uriStart))
        assertArrayEquals(
            original.copyOfRange(uriStart + oldUri.length, original.size),
            rereadReplaced.copyOfRange(uriStart + newUri.length, rereadReplaced.size)
        )
        assertEquals(8u, entryTotpSummary(rereadReplaced)!!.digits)

        // Remove: the line and its newline go, nothing else moves.
        val removed = entryRemoveOtpauth(rereadReplaced)
        store.writeEntry("gamma", removed, engine)
        val rereadRemoved = store.readEntry("gamma", engine)
        assertArrayEquals(
            originalText.replaceFirst("$oldUri\n", "").toByteArray(Charsets.UTF_8),
            rereadRemoved
        )
        assertNull(entryTotpSummary(rereadRemoved))

        // Append to an entry without a code: the last line had no
        // newline, so exactly one is supplied before the URI's own line.
        val appended = entrySetOtpauth(rereadRemoved, oldUri)
        store.writeEntry("gamma", appended, engine)
        val rereadAppended = store.readEntry("gamma", engine)
        val removedText = String(rereadRemoved, Charsets.UTF_8)
        assertFalse(removedText.endsWith("\n"))
        assertArrayEquals("$removedText\n$oldUri\n".toByteArray(Charsets.UTF_8), rereadAppended)
        assertNotNull(entryTotpSummary(rereadAppended))
    }
}
