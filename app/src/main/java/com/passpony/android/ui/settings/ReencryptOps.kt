package com.passpony.android.ui.settings

import com.passpony.android.store.StorePaths
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.PassStoreInterface
import uniffi.pass_ffi.StoreFormat

/**
 * The re-encrypt-subtree flow's plumbing, factored out of AppViewModel
 * and typed against [PassStoreInterface] (not the concrete FFI
 * [uniffi.pass_ffi.PassStore]) so it is unit-testable with a fake store
 * on the plain JVM -- reencryptSubtree/reencryptTargets themselves are
 * pass-core FFI calls this deliberately never touches, only the request
 * shaping and the entry-name-to-file-name mapping around them. Ports
 * iOS's ReencryptView.preview()/reencrypt().
 */
object ReencryptOps {
    /** Preview of what a re-encrypt would rewrite. No store open (should
     * not happen from the Settings screen, which only offers this once
     * a store is open) reads as "nothing to preview" rather than a crash. */
    fun preview(store: PassStoreInterface?, subpath: String): List<String> =
        store?.reencryptTargets(subpath) ?: emptyList()

    /**
     * Decrypt and re-encrypt every entry under [subpath] to its
     * currently-resolved recipients, returning both the rewritten entry
     * names (for the confirmation message) and their store-relative
     * file names (for the git commit -- entry name plus [format]'s
     * extension, same mapping AppViewModel's own save/move/delete use).
     */
    fun run(
        store: PassStoreInterface,
        subpath: String,
        backend: CryptoBackend,
        format: StoreFormat,
    ): Result {
        val rewritten = store.reencryptSubtree(subpath, backend)
        val files = rewritten.map { StorePaths.entryFileName(it, format) }
        return Result(entries = rewritten, files = files)
    }

    data class Result(val entries: List<String>, val files: List<String>)
}
