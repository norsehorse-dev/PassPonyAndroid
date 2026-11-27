package com.passpony.android.crypto

import android.content.Context
import java.io.File
import uniffi.pass_ffi.pgpKeyInfos

/**
 * The OpenPGP key files in `filesDir/pgp-keys/`, port of iOS's
 * PGPKeyStore. The files stay exactly where and as they were imported
 * (armored or binary, secret or public-only); since 3.0 they are read by
 * the core's Rust engine (rpgp), the same one iOS and Desktop use.
 */
object PgpKeyStore {
    fun keyDirectory(context: Context): File =
        File(context.filesDir, "pgp-keys").apply { mkdirs() }

    /**
     * Key files that failed to unlock on the last engine load: a wrong or
     * absent passphrase, not a parse failure. Settings offers the
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

    /** Every importable key file, public material only: no unlock attempt,
     * so this never populates or reads [lockedKeyFiles]. A file holding
     * several keys is listed by its first. */
    fun availableKeys(context: Context): List<KeyFileInfo> {
        val out = mutableListOf<KeyFileInfo>()
        for (file in keyFiles(context)) {
            val info = runCatching { pgpKeyInfos(file.readBytes()) }.getOrNull()?.firstOrNull() ?: continue
            out += KeyFileInfo(
                file = file.name,
                primaryFingerprint = info.primaryFingerprint.uppercase(),
                algorithm = info.algorithm,
                isV6 = info.isV6,
            )
        }
        return out
    }

    internal fun keyFiles(context: Context): List<File> =
        keyDirectory(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?: emptyList()
}
