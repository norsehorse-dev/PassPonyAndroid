package com.passpony.android.crypto

import android.content.Context
import java.io.File
import uniffi.pass_ffi.AgeEngine
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.ageGenerateIdentity

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
            file.writeText(text, Charsets.UTF_8)
            restrictToOwner(file)
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
        return file.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .mapNotNull { line -> line.removePrefix("# public key:").takeIf { it != line }?.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Replace the identities file with imported text after confirming it
     * carries at least one usable age identity. `AgeEngine.fromIdentitiesText`
     * silently drops malformed lines and never throws (see its FFI doc),
     * so a bad paste would otherwise install a store that decrypts
     * nothing; the self-encrypt round-trip below rejects that up front and
     * nothing is written unless it passes. Throws [IllegalArgumentException]
     * when the text has no usable identity.
     */
    fun importIdentities(context: Context, text: String) {
        val engine = AgeEngine.fromIdentitiesText(text)
        val usable = try {
            runCatching { engine.decrypt(engine.encrypt(IMPORT_PROBE, emptyList())) }.isSuccess
        } finally {
            engine.close()
        }
        require(usable) { "no usable age identity" }
        val file = identitiesFile(context)
        file.writeText(if (text.endsWith("\n")) text else "$text\n", Charsets.UTF_8)
        restrictToOwner(file)
    }

    // A short self-encrypt probe validates an imported identities file:
    // encrypt to the loaded identities' own public keys, then decrypt.
    // Round-trips only when at least one usable identity is present.
    private val IMPORT_PROBE = "PassPony import probe".toByteArray(Charsets.UTF_8)

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
