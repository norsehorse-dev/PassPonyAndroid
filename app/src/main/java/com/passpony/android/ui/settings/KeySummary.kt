package com.passpony.android.ui.settings

import com.passpony.android.crypto.PgpKeyStore

/**
 * Pure display formatting for OpenPGP key material -- no Context, no FFI,
 * no BouncyCastle -- so it is unit-testable on the plain JVM. Ports the
 * formatting iOS's SettingsView/InitializeStoreView inline directly on
 * PGPKeyStore.KeyFileInfo.
 */
object KeySummary {
    /**
     * A fingerprint shortened to its last 16 hex characters with a
     * leading ellipsis, matching iOS's
     * `fp.count > 16 ? "…" + fp.suffix(16) : fp`. Fingerprints shorter
     * than that (should not happen for a real OpenPGP key, but a 16-hex
     * key ID resolves the same way in PgpEngine.resolve()) pass through
     * unchanged.
     */
    fun shortFingerprint(fingerprint: String): String =
        if (fingerprint.length > 16) "…" + fingerprint.takeLast(16) else fingerprint

    /**
     * One line summarizing a key file for the OpenPGP keys / Initialize
     * store lists: "RSA-4096 · …0123456789ABCDEF" or, for a v6 key,
     * "Ed25519 v6 (v6) · …0123456789ABCDEF" -- matches iOS's
     * `"\(key.algorithm)\(key.isV6 ? " (v6)" : "") · \(shortFingerprint(...))"`.
     */
    fun summaryLine(key: PgpKeyStore.KeyFileInfo): String {
        val algorithm = if (key.isV6) "${key.algorithm} (v6)" else key.algorithm
        return "$algorithm · ${shortFingerprint(key.primaryFingerprint)}"
    }

    /**
     * `.gpg-id` file content for the given fingerprints: one per line,
     * newline-terminated -- the same format `pass init` writes, and
     * what pass-core's recipient resolution expects to read back.
     */
    fun formatGpgId(fingerprints: List<String>): String =
        fingerprints.joinToString(separator = "\n", postfix = "\n")

    /** Parse an existing `.gpg-id` file's text back into its recipient
     * lines, for display -- blank lines dropped, no other normalization
     * (pass-core resolves whatever is actually on disk). */
    fun parseGpgId(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
}
