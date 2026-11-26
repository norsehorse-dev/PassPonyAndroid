package com.passpony.android.store

import java.io.File

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
     * What the first readable `.age` entry under [root] was encrypted
     * with: true for post-quantum, false for classic, null when the
     * store has no entry to look at.
     */
    fun sniffStore(root: File): Boolean? {
        val entry = root.walkTopDown()
            .onEnter { it.name != ".git" }
            .firstOrNull { it.isFile && it.name.endsWith(".age") }
            ?: return null
        val head = runCatching {
            entry.inputStream().use { input ->
                val buffer = ByteArray(MAX_HEADER)
                val read = input.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
        }.getOrNull() ?: return null
        if (stanzaTypes(head).isEmpty()) return null
        return isPostQuantum(head)
    }
}
