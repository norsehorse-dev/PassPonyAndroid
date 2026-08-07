package com.passpony.android.crypto

import android.content.Context
import com.pgpony.android.crypto.PGPCryptoError
import com.pgpony.android.crypto.PGPCryptoService
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.CryptoException

/**
 * pass (OpenPGP) engine over PGPonyCore-Kotlin / BouncyCastle. Port of
 * iOS's PGPonyEngine: same recipient-resolution rule, same locked-key
 * handling (via PgpKeyStore), same armored/binary tolerance. Unlike iOS,
 * RSA recipients are accepted on encrypt here — BC handles RSA natively in
 * software, so there is no smartcard-only gate the way
 * NorseHorsePGPCore's hand-rolled Cv25519/X25519-only parser requires.
 */
class PgpEngine private constructor(
    private val entries: List<PgpKeyStore.KeyEntry>,
    private val passphrase: String?,
) : CryptoBackend {

    private val secretRings: List<PGPSecretKeyRing> = entries.mapNotNull { it.secretRing }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (secretRings.isEmpty()) throw CryptoException.NoUsableKey()
        return try {
            PGPCryptoService.shared.decrypt(ciphertext, secretRings, passphrase).data
        } catch (e: PGPCryptoError.DecryptionFailed) {
            // The one message PGPCryptoService.decrypt() throws when no
            // held secret key's ID matches any PKESK in the ciphertext
            // (PGPCryptoService.kt's `decryptedStream == null` branch) —
            // the exact "no usable key" case pass expects. Anything else
            // reaching here is a genuine corruption/parse failure.
            if (e.message == NO_MATCHING_KEY_MESSAGE) {
                throw CryptoException.NoUsableKey()
            }
            throw CryptoException.DecryptionFailed()
        } catch (e: PGPCryptoError.PassphraseRequired) {
            // Reachable only if a locked ring somehow made it into
            // secretRings; PgpKeyStore already excludes those, so this is
            // a belt-and-suspenders match for the "no usable key" case.
            throw CryptoException.NoUsableKey()
        } catch (e: PGPCryptoError.InvalidPassphrase) {
            throw CryptoException.NoUsableKey()
        } catch (e: PGPCryptoError) {
            throw CryptoException.DecryptionFailed()
        } catch (e: Exception) {
            throw CryptoException.DecryptionFailed()
        }
    }

    override fun encrypt(plaintext: ByteArray, recipients: List<String>): ByteArray {
        // pass stores always resolve a .gpg-id; empty means uninitialized.
        if (recipients.isEmpty()) throw CryptoException.NoUsableKey()
        val targets = mutableListOf<PGPPublicKeyRing>()
        for (spec in recipients) {
            val entry = resolve(spec)
                ?: throw CryptoException.Unavailable(
                    reason = "No imported key matches $spec (fingerprint or 16-hex key ID)"
                )
            if (entry.publicRing.publicKeys.asSequence().none { it.isEncryptionKey }) {
                throw CryptoException.Unavailable(
                    reason = "Key for $spec has no usable encryption subkey"
                )
            }
            targets += entry.publicRing
        }
        return try {
            PGPCryptoService.shared.encrypt(
                data = plaintext,
                recipientPublicKeys = targets,
                armor = false, // pass entries are binary .gpg
            )
        } catch (e: PGPCryptoError) {
            throw CryptoException.EncryptionFailed()
        }
    }

    /**
     * Match a `.gpg-id` spec against any key in the imported files: an
     * uppercase-hex fingerprint (primary or subkey) or a 16-hex key ID.
     * `0x` prefix and inner spaces tolerated, fingerprint-suffix match at
     * 16+ chars — the exact rule iOS's PGPonyEngine.resolve() uses.
     */
    private fun resolve(spec: String): PgpKeyStore.KeyEntry? {
        val needle = spec.uppercase().replace("0X", "").replace(" ", "")
        if (needle.isEmpty()) return null
        return entries.firstOrNull { entry ->
            entry.publicRing.publicKeys.asSequence().any { key ->
                val fp = PGPCryptoService.shared.fingerprintHex(key).uppercase()
                val kid = String.format("%016X", key.keyID)
                fp == needle || kid == needle || (needle.length >= 16 && fp.endsWith(needle))
            }
        }
    }

    companion object {
        private const val NO_MATCHING_KEY_MESSAGE =
            "Decryption failed: No matching decryption key found"

        fun load(context: Context, passphrase: String? = PassphraseCache.load(context)): PgpEngine {
            val entries = PgpKeyStore.loadEntries(context, passphrase)
            return PgpEngine(entries, passphrase)
        }
    }
}
