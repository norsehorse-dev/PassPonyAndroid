package com.passpony.android.crypto

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreFormat

/**
 * Opens three real pass fixture stores through the real pass engine, not a
 * stand-in: pass-v4-minimal (Cv25519, from the PassPonyCore corpus, real
 * gpg-generated), pass-rsa-minimal (RSA, real gpg-generated, the software
 * capability Android has that iOS does not), and pass-v6-minimal (X25519
 * v6, PGPonyCore-Kotlin-generated — see GenerateV6FixtureTest for why).
 * Assets live under this test package's own APK (src/androidTest/assets),
 * read through the instrumentation context; the copy under test lives in
 * the app-under-test's cache/files dirs, matching where the real app would
 * keep a store and its imported keys.
 */
@RunWith(AndroidJUnit4::class)
class PgpEngineFixtureTest {

    private fun asset(fixture: String, path: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("$fixture/$path").use { it.readBytes() }

    private fun appContext(): android.content.Context =
        ApplicationProvider.getApplicationContext()

    private fun freshStoreDir(name: String): File {
        val dir = File(appContext().cacheDir, name)
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun installIdentity(fixture: String) {
        val keyDir = PgpKeyStore.keyDirectory(appContext())
        keyDir.listFiles()?.forEach { it.delete() }
        File(keyDir, "identity.asc").writeBytes(asset(fixture, "identity.asc"))
    }

    private fun verifyFixture(fixture: String, storeDirName: String) {
        installIdentity(fixture)
        val storeDir = freshStoreDir(storeDirName)
        for (name in listOf("alpha", "beta", "gamma")) {
            File(storeDir, "$name.gpg").writeBytes(asset(fixture, "store/$name.gpg"))
        }
        File(storeDir, ".gpg-id").writeBytes(asset(fixture, "store/.gpg-id"))

        val backend = PgpEngine.load(appContext(), passphrase = null)
        val store = PassStore.open(storeDir.absolutePath, StoreFormat.PASS)

        for (name in listOf("alpha", "beta", "gamma")) {
            val plaintext = store.readEntry(name, backend)
            assertArrayEquals(asset(fixture, "goldens/$name.plain"), plaintext)
        }

        val content = "new-secret-1\nusername: kevin\n".toByteArray(Charsets.UTF_8)
        store.writeEntry("web/new-entry", content, backend)
        val read = store.readEntry("web/new-entry", backend)
        assertArrayEquals(content, read)
    }

    @Test
    fun v4Cv25519FixtureDecryptsAndRoundTrips() {
        verifyFixture("pass-v4-minimal", "pgp-v4-fixture-store")
    }

    @Test
    fun rsaFixtureDecryptsAndRoundTrips() {
        verifyFixture("pass-rsa-minimal", "pgp-rsa-fixture-store")
    }

    @Test
    fun v6X25519FixtureDecryptsAndRoundTrips() {
        verifyFixture("pass-v6-minimal", "pgp-v6-fixture-store")
    }
}
