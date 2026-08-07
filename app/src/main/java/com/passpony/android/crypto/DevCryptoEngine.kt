package com.passpony.android.crypto

import com.passpony.android.BuildConfig
import uniffi.pass_ffi.CryptoBackend

/**
 * Debug-only stand-in engine: a reversible byte flip, not real encryption.
 * Used until P05 (age engine) and P06 (pass engine) land, and afterward
 * only when neither real engine can load. Mirrors PassPony iOS's rule
 * that a build with no real engine traps in release rather than silently
 * "encrypting" a store with something reversible: check() throws
 * IllegalStateException, which is not caught anywhere on this path, so a
 * release build that somehow reaches this crashes instead of continuing.
 */
object DevCryptoEngine : CryptoBackend {
    override fun encrypt(plaintext: ByteArray, recipients: List<String>): ByteArray {
        check(BuildConfig.DEBUG) { "DevCryptoEngine must never run in a release build" }
        return flip(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        check(BuildConfig.DEBUG) { "DevCryptoEngine must never run in a release build" }
        return flip(ciphertext)
    }

    private fun flip(bytes: ByteArray): ByteArray =
        ByteArray(bytes.size) { i -> bytes[i].toInt().inv().toByte() }
}
