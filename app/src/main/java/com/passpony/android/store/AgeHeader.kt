package com.passpony.android.store

import java.io.File
import uniffi.pass_ffi.StoreFs

/**
 * Reads the recipient stanza types from the header of a binary age file
 * without decrypting it (3.0). Used to tell what kind of keys a store
 * with no `.age-recipients` file was saved with: its entries say so in
 * the clear. Pure Kotlin so it is unit-tested on the JVM.
 */
object AgeHeader {
    private const val MAGIC = "age-encryption.org/v1"
    private const val MAX_HEADER = 64 * 1024
    private val PQ_STANZAS = setOf("mlkem768x25519", "mlkem768p256tag")

    /** The stanza types in file order; empty when this is not a binary age file. */
    fun stanzaTypes(bytes: ByteArray): List<String> {
        val text = String(bytes, 0, minOf(bytes.size, MAX_HEADER), Charsets.ISO_8859_1)
        if (!text.startsWith(MAGIC)) return emptyList()
        val types = mutableListOf<String>()
        for (line in text.lineSequence()) {
            if (line.startsWith("---")) break
            if (line.startsWith("-> ")) {
                line.removePrefix("-> ").substringBefore(' ').takeIf { it.isNotEmpty() }?.let { types += it }
            }
        }
        return types
    }

    /** True when any stanza is a post-quantum one. */
    fun isPostQuantum(bytes: ByteArray): Boolean = stanzaTypes(bytes).any { it in PQ_STANZAS }

    /**
     * What the first `.age` entry in the store was encrypted with: true
     * for post-quantum, false for classic, null when the store has no
     * entry to look at.
     */
    fun sniffStore(fs: StoreFs): Boolean? {
        val entry = fs.walkFiles().firstOrNull { it.endsWith(".age") } ?: return null
        val head = runCatching { fs.read(entry) }.getOrNull() ?: return null
        if (stanzaTypes(head).isEmpty()) return null
        return isPostQuantum(head)
    }

    /** [sniffStore] for a store in a plain directory. */
    fun sniffStore(root: File): Boolean? = sniffStore(FileStoreFs(root))

    /**
     * How many `.age` entries in the store have no post-quantum stanza.
     * In a post-quantum store these are entries a move to post-quantum
     * keys has not reached: it was interrupted, this device could not
     * read them, or another device saved them before it updated. A file
     * that is not a binary age file is not counted.
     */
    fun classicEntries(fs: StoreFs): Int =
        fs.walkFiles().filter { it.endsWith(".age") }.count { path ->
            val head = runCatching { fs.read(path) }.getOrNull() ?: return@count false
            stanzaTypes(head).isNotEmpty() && !isPostQuantum(head)
        }

    /** [classicEntries] for a store in a plain directory. */
    fun classicEntries(root: File): Int = classicEntries(FileStoreFs(root))
}
