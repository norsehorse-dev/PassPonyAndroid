package com.passpony.android.crypto

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.CryptoException
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreFormat

/**
 * Locked-key behavior against pass-v6-locked (see GenerateV6FixtureTest):
 * a protected key without the cached passphrase loads as locked and
 * decrypt fails NoUsableKey; after PassphraseCache.save() a fresh
 * PgpEngine.load() picks the cached passphrase up automatically and
 * decrypts. The fixed passphrase here must match
 * GenerateV6FixtureTest.LOCKED_FIXTURE_PASSPHRASE exactly — the two live
 * in separate source sets (test vs androidTest) so it cannot be shared as
 * one constant.
 */
@RunWith(AndroidJUnit4::class)
class PgpLockedKeyTest {
    private val fixturePassphrase = "fixture-not-a-real-passphrase"

    private fun asset(path: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("pass-v6-locked/$path").use { it.readBytes() }

    private fun appContext(): android.content.Context =
        ApplicationProvider.getApplicationContext()

    private fun installLockedIdentity(): String {
        val keyDir = PgpKeyStore.keyDirectory(appContext())
        keyDir.listFiles()?.forEach { it.delete() }
        val file = File(keyDir, "identity.asc")
        file.writeBytes(asset("identity.asc"))
        PassphraseCache.clear(appContext())
        return file.name
    }

    private fun freshStoreDir(): File {
        val dir = File(appContext().cacheDir, "pgp-locked-fixture-store")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    @Test
    fun lockedKeyIsRecordedAndUnlocksAfterPassphraseCacheSave() {
        val fileName = installLockedIdentity()
        val storeDir = freshStoreDir()
        File(storeDir, "alpha.gpg").writeBytes(asset("store/alpha.gpg"))
        // See PgpEngineFixtureTest: aapt drops dotfiles from packaged
        // assets by default, so the fixture is committed as store/gpg-id.
        File(storeDir, ".gpg-id").writeBytes(asset("store/gpg-id"))
        val store = PassStore.open(storeDir.absolutePath, StoreFormat.PASS)

        // No cached passphrase: the key loads as locked and decrypt has
        // nothing usable.
        val lockedEngine = PgpEngine.load(appContext(), passphrase = null)
        assertEquals(listOf(fileName), PgpKeyStore.lockedKeyFiles)
        try {
            store.readEntry("alpha", lockedEngine)
            fail("expected NoUsableKey while the key is locked")
        } catch (e: CryptoException.NoUsableKey) {
            // expected
        }

        // Cache the real passphrase, then rebuild the engine (mirrors the
        // app re-creating EngineProvider's engine after a passphrase
        // prompt): the key now unlocks and lockedKeyFiles clears.
        PassphraseCache.save(appContext(), fixturePassphrase)
        val unlockedEngine = PgpEngine.load(appContext())
        assertTrue(PgpKeyStore.lockedKeyFiles.isEmpty())
        val plaintext = store.readEntry("alpha", unlockedEngine)
        assertArrayEquals(asset("goldens/alpha.plain"), plaintext)
    }
}
