package com.passpony.android.crypto

import android.content.Context
import com.passpony.android.store.KeyBackupState
import java.io.File
import uniffi.pass_ffi.AgeEngine
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.ageDecryptIdentitiesFile
import uniffi.pass_ffi.ageGenerateIdentity
import uniffi.pass_ffi.ageIsEncryptedIdentitiesFile
import uniffi.pass_ffi.identitiesDescribe
import uniffi.pass_ffi.identitiesMerge

/**
 * Identities live in an age identities file in this app's private files
 * dir: one AGE-SECRET-KEY-1... per line, blank lines and # comments
 * ignored. Mirrors PassPony iOS's AgeIdentityStore.swift, minus the app
 * group (Android has no equivalent) and iOS's completeFileProtection
 * (this app's private storage is already sandboxed per-UID; the
 * owner-only file mode below is the closest Android analog).
 */
object AgeIdentityStore {
    fun identitiesFile(context: Context): File = File(context.filesDir, "identities")

    /**
     * Loads the engine from the identities file; on first run, generates
     * one identity and persists it (owner-only permissions) so the store
     * works out of the box. Never logs identity material, and does not
     * keep the file text around longer than the AgeEngine construction
     * call that consumes it.
     */
    fun loadOrCreateEngine(context: Context): CryptoBackend {
        val file = identitiesFile(context)
        if (!file.exists()) {
            val generated = ageGenerateIdentity()
            val text = "# created by PassPony\n" +
                "# public key: ${generated.recipientString}\n" +
                "${generated.identityString}\n"
            writeAtomically(file, text)
            KeyBackupState.markChanged(context)
        }
        return AgeCryptoBackend(AgeEngine.fromIdentitiesText(file.readText(Charsets.UTF_8)))
    }

    /** True when an identities file exists in this app's storage. */
    fun hasIdentities(context: Context): Boolean = identitiesFile(context).exists()

    /**
     * Public-key hint lines pulled from `# public key:` comments in the
     * identities file, for display only. Empty when the file is absent or
     * carries no such comment (an imported file may omit it). Never
     * returns secret key material.
     */
    fun publicKeyHints(context: Context): List<String> {
        val file = identitiesFile(context)
        if (!file.exists()) return emptyList()
        return identitiesDescribe(file.readText(Charsets.UTF_8)).mapNotNull { it.publicKeyHint }
    }

    /** How many identities the file holds, hint or not. */
    fun identityCount(context: Context): Int {
        val file = identitiesFile(context)
        if (!file.exists()) return 0
        return identitiesDescribe(file.readText(Charsets.UTF_8)).size
    }

    /** Outcome of an import: identities appended, and ones the file already had. */
    data class ImportResult(val added: Int, val alreadyPresent: Int)

    /**
     * Add the identities in [text] to the identities file after confirming
     * the text carries at least one usable age identity. Since 2.0 this
     * appends instead of replacing (core `identities_merge`): identities
     * already in the file stay, in place, so nothing encrypted to them
     * becomes unreadable; identities the file already has are skipped.
     * `AgeEngine.fromIdentitiesText` silently drops malformed lines and
     * never throws (see its FFI doc), so the self-encrypt round trip below
     * rejects a file with no usable identity before anything is written.
     * Throws [IllegalArgumentException] in that case.
     */
    fun importIdentities(context: Context, text: String): ImportResult {
        val engine = AgeEngine.fromIdentitiesText(text)
        val usable = try {
            runCatching { engine.decrypt(engine.encrypt(IMPORT_PROBE, emptyList())) }.isSuccess
        } finally {
            engine.close()
        }
        require(usable) { "no usable age identity" }
        val file = identitiesFile(context)
        val existing = if (file.exists()) file.readText(Charsets.UTF_8) else ""
        val merged = identitiesMerge(existing, text)
        if (merged.added > 0u) {
            writeAtomically(file, merged.text)
            KeyBackupState.markChanged(context)
        }
        return ImportResult(merged.added.toInt(), merged.alreadyPresent.toInt())
    }

    /**
     * True when [bytes] is an age-encrypted file (binary header or ASCII
     * armor), i.e. a passphrase-protected identities file such as gopass's
     * age backend writes, rather than a plaintext identities file.
     */
    fun isPassphraseProtected(bytes: ByteArray): Boolean = ageIsEncryptedIdentitiesFile(bytes)

    /**
     * Import an identities file read from disk. A plaintext file goes
     * straight to [importIdentities]. A passphrase-protected one (see
     * [isPassphraseProtected]) is decrypted in memory with [passphrase]
     * first and the plaintext handed to the same validation; only that
     * plaintext is ever stored, same as a plaintext import. A wrong
     * passphrase throws `CryptoException.DecryptionFailed`, a file that
     * asks for more scrypt work than the core allows throws
     * `CryptoException.Unavailable`, and in both cases nothing is
     * written. Neither the passphrase nor the decrypted text is logged;
     * the decrypted bytes are zeroed as soon as the text is built (the
     * JVM String copy and the FFI passphrase String cannot be wiped and
     * live until GC).
     */
    fun importIdentitiesFile(context: Context, bytes: ByteArray, passphrase: String?): ImportResult {
        if (!isPassphraseProtected(bytes)) {
            return importIdentities(context, bytes.toString(Charsets.UTF_8))
        }
        requireNotNull(passphrase) { "passphrase required" }
        val decrypted = ageDecryptIdentitiesFile(bytes, passphrase)
        try {
            return importIdentities(context, decrypted.toString(Charsets.UTF_8))
        } finally {
            decrypted.fill(0)
        }
    }

    /** The identities file's text for a backup or export, or null when there is none. */
    fun exportText(context: Context): String? {
        val file = identitiesFile(context)
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    // A short self-encrypt probe validates an imported identities file:
    // encrypt to the loaded identities' own public keys, then decrypt.
    // Round-trips only when at least one usable identity is present.
    private val IMPORT_PROBE = "PassPony import probe".toByteArray(Charsets.UTF_8)

    /**
     * Write through a temp file and rename, so a crash mid-write leaves
     * either the old identities file or the new one, never half of one.
     */
    private fun writeAtomically(file: File, text: String) {
        val body = if (text.endsWith("\n")) text else "$text\n"
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body, Charsets.UTF_8)
        restrictToOwner(tmp)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("couldn't replace the identities file")
        }
        restrictToOwner(file)
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}

/**
 * Adapts AgeEngine to the FFI's CryptoBackend shape. AgeEngine itself
 * implements the separate AgeEngineInterface UniFFI generates for it
 * (same method shapes, different interface type), so this is exactly
 * the two-line delegation the Rust doc comment on AgeEngine describes.
 *
 * Public rather than internal: Kotlin's internal visibility is not
 * reliably shared with the androidTest source set, and the fixture
 * test in AgeEngineFixtureTest exercises this exact adapter rather
 * than a test-local reimplementation of it.
 */
class AgeCryptoBackend(private val engine: AgeEngine) : CryptoBackend {
    override fun encrypt(plaintext: ByteArray, recipients: List<String>): ByteArray =
        engine.encrypt(plaintext, recipients)

    override fun decrypt(ciphertext: ByteArray): ByteArray =
        engine.decrypt(ciphertext)
}
