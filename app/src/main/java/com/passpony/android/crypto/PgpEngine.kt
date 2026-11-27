package com.passpony.android.crypto

import android.content.Context
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.PgpLockState
import uniffi.pass_ffi.PgpEngine as RustPgpEngine

/**
 * pass (OpenPGP) engine. Since 3.0 this is a thin wrapper over the core's
 * Rust engine (rpgp, the `pgp-engine` feature), the same code iOS and
 * Desktop run for pass stores; before that it was PGPonyCore-Kotlin over
 * BouncyCastle. Recipient resolution, locked-key handling and the errors
 * it raises are the core's.
 *
 * Every key file is handed to the engine with the cached passphrase when
 * it is built. A key the passphrase does not open is left out of decrypt
 * (its public half still encrypts) and its file is listed in
 * [PgpKeyStore.lockedKeyFiles].
 */
class PgpEngine private constructor(
    private val engine: RustPgpEngine,
) : CryptoBackend {

    override fun decrypt(ciphertext: ByteArray): ByteArray = engine.decrypt(ciphertext)

    override fun encrypt(plaintext: ByteArray, recipients: List<String>): ByteArray =
        engine.encrypt(plaintext, recipients)

    companion object {
        fun load(context: Context, passphrase: String? = PassphraseCache.load(context)): PgpEngine {
            val files = PgpKeyStore.keyFiles(context).mapNotNull { file ->
                runCatching { file.readBytes() }.getOrNull()?.let { file.name to it }
            }
            val engine = RustPgpEngine.fromKeyBlobs(files.map { it.second }, passphrase)
            val states = engine.blobLockStates()
            PgpKeyStore.lockedKeyFiles = files.mapIndexedNotNull { index, (name, _) ->
                when (states.getOrNull(index)) {
                    PgpLockState.PASSPHRASE_REQUIRED, PgpLockState.WRONG_PASSPHRASE -> name
                    else -> null
                }
            }
            return PgpEngine(engine)
        }
    }
}
