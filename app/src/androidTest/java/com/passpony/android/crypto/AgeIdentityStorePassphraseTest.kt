package com.passpony.android.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.AgeEngine
import uniffi.pass_ffi.CryptoException

/**
 * Importing a passphrase-protected identities file (the format gopass's
 * age backend writes, produced here by the real `age -p`): decrypt in
 * memory, then the same validation and install as a plaintext import.
 * The fixtures wrap a throwaway identity; assets are copies of
 * PassPonyCore's fixtures/passage/passphrase-identities.
 *
 * The test overwrites the app's real identities file, so it saves and
 * restores it around each case.
 */
@RunWith(AndroidJUnit4::class)
class AgeIdentityStorePassphraseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = AgeIdentityStore.identitiesFile(context)
    private var saved: ByteArray? = null

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("passphrase-identities/$name").use { it.readBytes() }

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
    fun detectsEncryptedAndPlaintextFiles() {
        assertTrue(AgeIdentityStore.isPassphraseProtected(asset("identities.binary")))
        assertTrue(AgeIdentityStore.isPassphraseProtected(asset("identities")))
        assertFalse(AgeIdentityStore.isPassphraseProtected(asset("identities.plain")))
    }

    @Test
    fun binaryEncryptedFileImportsAndStoresOnlyPlaintext() {
        AgeIdentityStore.importIdentitiesFile(context, asset("identities.binary"), PASSPHRASE)

        assertPlainInstalled()
    }

    @Test
    fun armoredEncryptedFileImports() {
        AgeIdentityStore.importIdentitiesFile(context, asset("identities"), PASSPHRASE)

        assertPlainInstalled()
    }

    @Test
    fun wrongPassphraseFailsAndWritesNothing() {
        val before = "# existing\n".toByteArray(Charsets.UTF_8)
        file.writeBytes(before)

        try {
            AgeIdentityStore.importIdentitiesFile(context, asset("identities.binary"), "not it")
            fail("expected a wrong passphrase to be rejected")
        } catch (e: CryptoException.DecryptionFailed) {
            // expected
        }

        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun missingPassphraseForEncryptedFileIsRejected() {
        try {
            AgeIdentityStore.importIdentitiesFile(context, asset("identities.binary"), null)
            fail("expected an encrypted file without a passphrase to be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertFalse(file.exists())
    }

    @Test
    fun plaintextFileStillImportsWithoutPassphrase() {
        AgeIdentityStore.importIdentitiesFile(context, asset("identities.plain"), null)

        assertPlainInstalled()
    }

    @Test
    fun importedKeyDecryptsWhatItsRecipientEncrypted() {
        AgeIdentityStore.importIdentitiesFile(context, asset("identities.binary"), PASSPHRASE)

        val engine = AgeEngine.fromIdentitiesText(file.readText(Charsets.UTF_8))
        val secret = "pony-secret\n".toByteArray(Charsets.UTF_8)
        assertEquals(
            secret.toList(),
            engine.decrypt(engine.encrypt(secret, emptyList())).toList()
        )
    }

    @Test
    fun multiIdentityFileImportsAndEveryIdentityDecrypts() {
        AgeIdentityStore.importIdentitiesFile(context, asset("identities.multi"), PASSPHRASE)

        val installed = file.readText(Charsets.UTF_8)
        val recipients = installed.lines()
            .mapNotNull { it.removePrefix("# public key: ").takeIf { r -> r != it } }
        assertEquals(2, recipients.size)

        val engine = AgeEngine.fromIdentitiesText(installed)
        for ((i, recipient) in recipients.withIndex()) {
            val entry = "entry-for-identity-${i + 1}\n".toByteArray(Charsets.UTF_8)
            val ciphertext = engine.encrypt(entry, listOf(recipient))
            assertEquals(entry.toList(), engine.decrypt(ciphertext).toList())
        }
    }

    private fun assertPlainInstalled() {
        val installed = file.readText(Charsets.UTF_8)
        assertEquals(asset("identities.plain").toString(Charsets.UTF_8).trimEnd(), installed.trimEnd())
        assertFalse(installed.contains("age-encryption.org"))
    }

    private companion object {
        const val PASSPHRASE = "correct horse pony"
    }
}
