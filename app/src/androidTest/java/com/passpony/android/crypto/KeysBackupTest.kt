package com.passpony.android.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.AgeEngine
import uniffi.pass_ffi.BackupSection
import uniffi.pass_ffi.CryptoException
import uniffi.pass_ffi.ageDecryptWithPassphrase
import uniffi.pass_ffi.ageEncryptWithPassphrase
import uniffi.pass_ffi.backupDecode
import uniffi.pass_ffi.backupEncode
import uniffi.pass_ffi.backupGenerateRecoveryCode
import uniffi.pass_ffi.backupKindAgeIdentities
import uniffi.pass_ffi.backupNormalizeRecoveryCode

/**
 * 2.0 rc1 key safety on the device: importing a second identity keeps the
 * first (entries encrypted to it stay readable), and a `.passpony`
 * backup made here opens with its recovery code, typed sloppily, and not
 * with any other code. Uses the throwaway identities from PassPonyCore's
 * passphrase-identities fixture; saves and restores the app's real
 * identities file around each case.
 */
@RunWith(AndroidJUnit4::class)
class KeysBackupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = AgeIdentityStore.identitiesFile(context)
    private var saved: ByteArray? = null

    private fun asset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("passphrase-identities/$name").use { it.readBytes() }.toString(Charsets.UTF_8)

    @Before
    fun setUp() {
        saved = if (file.exists()) file.readBytes() else null
        file.delete()
    }

    @After
    fun tearDown() {
        file.delete()
        saved?.let { file.writeBytes(it) }
    }

    @Test
    fun importingASecondIdentityKeepsTheFirst() {
        val first = asset("identities.plain")
        assertEquals(1, AgeIdentityStore.importIdentities(context, first).added)
        val firstRecipient = AgeIdentityStore.publicKeyHints(context).single()
        val secret = "written under the first identity\n".toByteArray(Charsets.UTF_8)
        val ciphertext = AgeEngine.fromIdentitiesText(first).use { it.encrypt(secret, listOf(firstRecipient)) }

        val r = AgeIdentityStore.importIdentities(context, asset("identities.multi.plain"))
        assertEquals(2, r.added)
        assertEquals(0, r.alreadyPresent)
        assertEquals(3, AgeIdentityStore.identityCount(context))
        assertTrue(file.readText(Charsets.UTF_8).startsWith(first.trimEnd()))

        // The merged file still opens what the first identity encrypted.
        val merged = AgeEngine.fromIdentitiesText(file.readText(Charsets.UTF_8))
        merged.use { assertArrayEquals(secret, it.decrypt(ciphertext)) }

        // Importing the first file again changes nothing.
        val again = AgeIdentityStore.importIdentities(context, first)
        assertEquals(0, again.added)
        assertEquals(1, again.alreadyPresent)
        assertEquals(3, AgeIdentityStore.identityCount(context))
    }

    @Test
    fun backupOpensWithItsCodeOnly() {
        val identities = asset("identities.plain")
        val code = backupGenerateRecoveryCode()
        val plaintext = backupEncode(
            listOf(BackupSection(backupKindAgeIdentities(), "", identities.toByteArray(Charsets.UTF_8)))
        )
        val backup = ageEncryptWithPassphrase(plaintext, code)
        assertTrue(AgeIdentityStore.isPassphraseProtected(backup))

        val sloppy = backupNormalizeRecoveryCode(code.lowercase().replace("-", " "))
        val sections = backupDecode(ageDecryptWithPassphrase(backup, sloppy))
        assertEquals(1, sections.size)
        assertEquals(identities, sections[0].data.toString(Charsets.UTF_8))

        val other = backupGenerateRecoveryCode()
        try {
            ageDecryptWithPassphrase(backup, other)
            fail("a different recovery code must not open the backup")
        } catch (e: CryptoException.DecryptionFailed) {
            // expected
        }
    }
}
