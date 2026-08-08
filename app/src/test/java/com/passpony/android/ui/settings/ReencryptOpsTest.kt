package com.passpony.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.EntryRef
import uniffi.pass_ffi.PassStoreInterface
import uniffi.pass_ffi.StoreFormat

/**
 * ReencryptOps is deliberately typed against PassStoreInterface (not the
 * concrete FFI PassStore) so its request-shaping and entry-name-to-file-
 * name mapping can be exercised here with a fake store, without ever
 * touching a real pass-core handle. CryptoBackend is a plain uniffi
 * callback interface, so it's fakeable too. Ports the cases iOS's
 * ReencryptView.preview()/reencrypt() cover manually on-device.
 */
class ReencryptOpsTest {

    private class FakeCryptoBackend : CryptoBackend {
        override fun encrypt(plaintext: ByteArray, recipients: List<String>): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    private class FakeStore(
        private val targets: List<String> = emptyList(),
        private val rewritten: List<String> = emptyList(),
    ) : PassStoreInterface {
        var lastPreviewSubpath: String? = null
        var lastRunSubpath: String? = null

        override fun entries(): List<EntryRef> = emptyList()
        override fun hasEntry(name: String): Boolean = false
        override fun moveEntry(from: String, to: String, backend: CryptoBackend) {}
        override fun readEntry(name: String, backend: CryptoBackend): ByteArray = ByteArray(0)

        override fun reencryptSubtree(subpath: String, backend: CryptoBackend): List<String> {
            lastRunSubpath = subpath
            return rewritten
        }

        override fun reencryptTargets(subpath: String): List<String> {
            lastPreviewSubpath = subpath
            return targets
        }

        override fun removeEntry(name: String) {}
        override fun writeEntry(name: String, content: ByteArray, backend: CryptoBackend) {}
    }

    @Test
    fun preview_withNullStore_returnsEmpty() {
        assertEquals(emptyList<String>(), ReencryptOps.preview(null, "web"))
    }

    @Test
    fun preview_passesSubpathThrough_andReturnsStoreResult() {
        val store = FakeStore(targets = listOf("web/a", "web/b"))
        assertEquals(listOf("web/a", "web/b"), ReencryptOps.preview(store, "web"))
        assertEquals("web", store.lastPreviewSubpath)
    }

    @Test
    fun preview_withEmptySubpath_meansEntireStore() {
        val store = FakeStore(targets = listOf("a", "b", "c"))
        ReencryptOps.preview(store, "")
        assertEquals("", store.lastPreviewSubpath)
    }

    @Test
    fun run_mapsRewrittenEntriesToAgeFileNames_forPassage() {
        val store = FakeStore(rewritten = listOf("web/a", "web/b"))
        val result = ReencryptOps.run(store, "web", FakeCryptoBackend(), StoreFormat.PASSAGE)
        assertEquals(listOf("web/a", "web/b"), result.entries)
        assertEquals(listOf("web/a.age", "web/b.age"), result.files)
        assertEquals("web", store.lastRunSubpath)
    }

    @Test
    fun run_mapsRewrittenEntriesToGpgFileNames_forPass() {
        val store = FakeStore(rewritten = listOf("email/work"))
        val result = ReencryptOps.run(store, "email", FakeCryptoBackend(), StoreFormat.PASS)
        assertEquals(listOf("email/work"), result.entries)
        assertEquals(listOf("email/work.gpg"), result.files)
    }

    @Test
    fun run_withNothingToRewrite_returnsEmptyResult() {
        val store = FakeStore(rewritten = emptyList())
        val result = ReencryptOps.run(store, "", FakeCryptoBackend(), StoreFormat.PASSAGE)
        assertTrue(result.entries.isEmpty())
        assertTrue(result.files.isEmpty())
    }
}
