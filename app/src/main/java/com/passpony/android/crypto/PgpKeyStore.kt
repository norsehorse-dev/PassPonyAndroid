package com.passpony.android.crypto

import android.content.Context
import com.pgpony.android.crypto.PGPCryptoService
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import java.io.File

/**
 * Loads OpenPGP key files from `filesDir/pgp-keys/`, port of iOS's
 * PGPKeyStore. Parsing itself is delegated to
 * PGPCryptoService.shared.importKeyData, which tolerates armored and
 * binary, secret and public-only, files — BC's PGPSecretKeyRing derives its
 * public keys directly from the secret packets, so unlike iOS's hand-rolled
 * parser there is no separate dearmor-to-public-blob step needed for a
 * secret-only export.
 *
 * Key import UI (SAF document picker) is P10; this packet only needs
 * programmatic writes into keyDirectory() for tests.
 */
object PgpKeyStore {
    fun keyDirectory(context: Context): File =
        File(context.filesDir, "pgp-keys").apply { mkdirs() }

    /**
     * Key files that failed to unlock on the last engine load — a wrong or
     * absent passphrase, not a parse failure. Settings (P10) offers the
     * passphrase prompt when this is non-empty. Simple last-write-wins,
     * same as iOS: fine for a status list written from wherever last built
     * a PgpEngine.
     */
    @Volatile
    var lockedKeyFiles: List<String> = emptyList()
        internal set

    data class KeyFileInfo(
        val file: String,
        val primaryFingerprint: String,
        val algorithm: String,
        val isV6: Boolean,
    )

    /** Every importable key file, public material only — no unlock attempt,
     * so this never populates or reads [lockedKeyFiles]. */
    fun availableKeys(context: Context): List<KeyFileInfo> {
        val out = mutableListOf<KeyFileInfo>()
        for (file in keyFiles(context)) {
            val result = runCatching { PGPCryptoService.shared.importKeyData(file.readBytes()) }
                .getOrNull() ?: continue
            out += KeyFileInfo(
                file = file.name,
                primaryFingerprint = result.fingerprint.uppercase(),
                algorithm = result.algorithm.shortName,
                isV6 = result.algorithm.isV6,
            )
        }
        return out
    }

    /** One imported key file: its full public ring (for `.gpg-id`
     * resolution across every fingerprint/key ID in the blob, primary or
     * subkey) and, when usable, its secret ring for decrypt. */
    internal data class KeyEntry(
        val file: String,
        val publicRing: PGPPublicKeyRing,
        val secretRing: PGPSecretKeyRing?,
    )

    /**
     * Parse every key file, probing each secret key against [passphrase].
     * A file that fails to unlock is recorded in [lockedKeyFiles] and its
     * entry keeps a non-null [KeyEntry.publicRing] (so its public key is
     * still resolvable for encrypt) but a null secretRing (excluded from
     * decrypt) — matching iOS: unprotected keys keep working, locked ones
     * are skipped rather than aborting the whole load.
     */
    internal fun loadEntries(context: Context, passphrase: String?): List<KeyEntry> {
        val locked = mutableListOf<String>()
        val entries = mutableListOf<KeyEntry>()
        for (file in keyFiles(context)) {
            val raw = runCatching { file.readBytes() }.getOrNull() ?: continue
            val result = runCatching { PGPCryptoService.shared.importKeyData(raw) }.getOrNull() ?: continue
            val publicRing = result.publicKeyRing ?: continue
            val secretRing = result.secretKeyRing
            val usable = secretRing != null && probeDecryptable(secretRing, passphrase)
            if (secretRing != null && !usable) {
                locked += file.name
            }
            entries += KeyEntry(
                file = file.name,
                publicRing = publicRing,
                secretRing = if (usable) secretRing else null,
            )
        }
        lockedKeyFiles = locked
        return entries
    }

    private fun keyFiles(context: Context): List<File> =
        keyDirectory(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Probe the ring's encryption-capable secret key against [passphrase] —
     * the specific key decrypt() actually needs, not just the primary.
     * Standard OpenPGP practice protects every secret key in a ring with
     * the same passphrase, so this one probe is representative for the
     * whole file.
     */
    private fun probeDecryptable(ring: PGPSecretKeyRing, passphrase: String?): Boolean {
        val candidate = ring.secretKeys.asSequence().firstOrNull { it.publicKey.isEncryptionKey }
            ?: ring.secretKeys.asSequence().firstOrNull()
            ?: return false
        if (candidate.s2KUsage.toInt() == 0) return true // unprotected
        return try {
            candidate.extractPrivateKey(
                BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider())
                    .build((passphrase ?: "").toCharArray())
            )
            true
        } catch (e: PGPException) {
            false
        }
    }
}
