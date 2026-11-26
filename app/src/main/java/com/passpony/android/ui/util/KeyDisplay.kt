package com.passpony.android.ui.util

import java.security.MessageDigest
import java.util.Locale

/**
 * How an age public key is shown (3.0). A classic key is 62 characters
 * and is shown whole. A post-quantum key is 1959, so it gets a short
 * form for lists and a fingerprint for the one place a person has to
 * compare two keys by eye.
 *
 * The fingerprint is the first 16 bytes of SHA-256 over the key exactly
 * as written in `.age-recipients` (trimmed, lower case), as eight groups
 * of four hex digits. The tail of the key is not used for comparing: its
 * last six characters are a checksum and the few before them could be
 * matched by someone generating keys on purpose. Any other PassPony, and
 * `sha256sum` on a desktop, gives the same digits for the same key.
 */
object KeyDisplay {
    private const val LONG_KEY = 100
    private const val PQ_PREFIX = "age1pq1"
    private const val TAG_PQ_PREFIX = "age1tagpq1"

    /** True when the key is too long to show or compare whole. */
    fun isLong(key: String): Boolean = key.trim().length > LONG_KEY

    /** The key for a list row: whole when short, else prefix and tail. */
    fun short(key: String): String {
        val k = key.trim()
        if (!isLong(k)) return k
        val lower = k.lowercase()
        val prefix = when {
            lower.startsWith(TAG_PQ_PREFIX) -> k.substring(0, TAG_PQ_PREFIX.length)
            lower.startsWith(PQ_PREFIX) -> k.substring(0, PQ_PREFIX.length)
            else -> k.take(8)
        }
        return prefix + "\u2026" + k.takeLast(8)
    }

    /** Eight groups of four hex digits; see the class comment. */
    fun fingerprint(key: String): String {
        val normalized = key.trim().lowercase().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized)
        return digest.take(16)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
            .chunked(4)
            .joinToString(" ")
    }
}
