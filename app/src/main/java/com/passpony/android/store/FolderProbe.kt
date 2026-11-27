package com.passpony.android.store

import uniffi.pass_ffi.StoreFs

/**
 * 3.0: what a folder the person picked holds, decided from the files
 * alone. `.gpg-id` or any `.gpg` file means a pass store;
 * `.age-recipients` or any `.age` file means a passage store. Nothing is
 * decrypted and nothing is written.
 */
object FolderProbe {
    enum class Kind {
        /** Nothing but dot files (a sync tool's own markers, say). */
        EMPTY,
        PASS,
        PASSAGE,

        /** Signs of both formats. The person says which to open. */
        BOTH,

        /** Files, but no store of either format. The person says what to make of it. */
        UNKNOWN,
    }

    fun inspect(fs: StoreFs): Kind {
        val root = fs.list("")
        val rootFiles = root.filterNot { it.isDir }.map { it.name }.toSet()
        val files = fs.walkFiles().toList()
        val pass = ".gpg-id" in rootFiles || files.any { it.endsWith(".gpg") }
        val passage = ".age-recipients" in rootFiles || files.any { it.endsWith(".age") }
        return when {
            pass && passage -> Kind.BOTH
            pass -> Kind.PASS
            passage -> Kind.PASSAGE
            root.none { !it.name.startsWith(".") } -> Kind.EMPTY
            else -> Kind.UNKNOWN
        }
    }
}
