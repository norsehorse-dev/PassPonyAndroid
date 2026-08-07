package com.passpony.android.crypto

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.AgeEngine
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreFormat

/**
 * Opens PassPonyCore's real fixtures/passage/minimal corpus (identities,
 * store/{alpha,beta,gamma}.age, and their golden plaintexts) through the
 * real age engine, not a stand-in. Assets live under this test package's
 * own APK (src/androidTest/assets), read through the instrumentation
 * context; the copy under test lives in the app-under-test's cache dir,
 * matching where the real app would keep a store.
 */
@RunWith(AndroidJUnit4::class)
class AgeEngineFixtureTest {

    private fun asset(path: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("passage-minimal/$path").use { it.readBytes() }

    private fun freshStoreDir(): File {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(appContext.cacheDir, "age-fixture-store")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    @Test
    fun fixtureCorpusDecryptsToGoldenPlaintexts() {
        val storeDir = freshStoreDir()
        for (name in listOf("alpha", "beta", "gamma")) {
            File(storeDir, "$name.age").writeBytes(asset("store/$name.age"))
        }

        val identitiesText = String(asset("identities"), Charsets.UTF_8)
        val backend = AgeCryptoBackend(AgeEngine.fromIdentitiesText(identitiesText))
        val store = PassStore.open(storeDir.absolutePath, StoreFormat.PASSAGE)

        for (name in listOf("alpha", "beta", "gamma")) {
            val plaintext = store.readEntry(name, backend)
            assertArrayEquals(asset("goldens/$name.plain"), plaintext)
        }
    }

    @Test
    fun writeThroughRealEngineRoundTripsByteIdentical() {
        val storeDir = freshStoreDir()
        val identitiesText = String(asset("identities"), Charsets.UTF_8)
        val backend = AgeCryptoBackend(AgeEngine.fromIdentitiesText(identitiesText))
        val store = PassStore.open(storeDir.absolutePath, StoreFormat.PASSAGE)

        val content = "new-secret-1\nusername: kevin\n".toByteArray(Charsets.UTF_8)
        store.writeEntry("web/new-entry", content, backend)
        val read = store.readEntry("web/new-entry", backend)

        assertArrayEquals(content, read)
    }
}
