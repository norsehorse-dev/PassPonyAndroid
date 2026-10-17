package com.passpony.android.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import uniffi.pass_ffi.HostKey
import uniffi.pass_ffi.KnownHost
import uniffi.pass_ffi.SshConfig
import uniffi.pass_ffi.SshKey
import uniffi.pass_ffi.knownHostsAdd
import uniffi.pass_ffi.knownHostsList
import uniffi.pass_ffi.knownHostsRemove
import uniffi.pass_ffi.sshGenerateKey
import uniffi.pass_ffi.sshImportKey

/**
 * The app's SSH key and the SSH hosts it trusts (2.0 rc2).
 *
 * One key per app, kept unencrypted only in memory: on disk it is sealed
 * with AES-GCM under an Android Keystore key, the same scheme as
 * [PassphraseCache], and unsealed for each network operation. The public
 * half sits next to it in plain text so Settings can show it without
 * unsealing anything. The encrypted `.passpony` backup carries a copy, so
 * the Keystore key is never the only way back to the SSH key.
 *
 * Known hosts are an OpenSSH known_hosts file in app-private storage; the
 * core reads and edits it (`knownHostsAdd`, `knownHostsRemove`).
 */
object SshKeyStore {
    private const val KEYSTORE_ALIAS = "passpony_ssh_key_wrap"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** Goes on the public key line, so a host's key list shows where it came from. */
    const val COMMENT = "passpony-android"

    private fun dir(context: Context): File = File(context.filesDir, "ssh").apply { mkdirs() }
    private fun sealedFile(context: Context) = File(dir(context), "id.enc")
    private fun publicFile(context: Context) = File(dir(context), "id.pub")
    private fun knownHostsFile(context: Context) = File(dir(context), "known_hosts")

    fun hasKey(context: Context): Boolean = sealedFile(context).exists()

    /** `ssh-ed25519 AAAA… passpony-android`, or null with no key. */
    fun publicKey(context: Context): String? =
        publicFile(context).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /**
     * The stored key, unsealed: the private half for the transport and
     * the details Settings shows. Null with no key, or when this device's
     * Keystore can no longer unseal it.
     */
    fun load(context: Context): SshKey? {
        val file = sealedFile(context)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return null
        val ivLen = bytes[0].toInt() and 0xFF
        if (bytes.size <= 1 + ivLen) return null
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val ciphertext = bytes.copyOfRange(1 + ivLen, bytes.size)
        val plain = try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }.doFinal(ciphertext)
        } catch (e: Exception) {
            return null
        }
        return try {
            sshImportKey(plain, null)
        } catch (e: Exception) {
            null
        } finally {
            plain.fill(0)
        }
    }

    /** True when a key file exists but this device can't unseal it. */
    fun isUnreadable(context: Context): Boolean = hasKey(context) && load(context) == null

    /** A new Ed25519 key, stored. Replaces nothing: delete first. */
    fun generate(context: Context): SshKey {
        val key = sshGenerateKey(COMMENT)
        save(context, key)
        return key
    }

    /**
     * Import an OpenSSH private key file. Throws
     * `SshKeyException.NeedsPassphrase` for a protected key without
     * [passphrase] and `WrongPassphrase` for a bad one; nothing is
     * written in either case.
     */
    fun import(context: Context, bytes: ByteArray, passphrase: String?): SshKey {
        val key = sshImportKey(bytes, passphrase)
        save(context, key)
        return key
    }

    /** Store [key] as this app's SSH key, replacing any current one. */
    fun save(context: Context, key: SshKey) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey())
        }
        val plain = key.privateKey.toByteArray(Charsets.UTF_8)
        val sealed = try {
            cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        val iv = cipher.iv
        writeAtomically(sealedFile(context), byteArrayOf(iv.size.toByte()) + iv + sealed)
        writeAtomically(publicFile(context), (key.publicKey + "\n").toByteArray(Charsets.UTF_8))
    }

    fun delete(context: Context) {
        sealedFile(context).delete()
        publicFile(context).delete()
    }

    // --- known hosts ---------------------------------------------------------

    fun knownHosts(context: Context): String =
        knownHostsFile(context).takeIf { it.exists() }?.readText() ?: ""

    fun trustedHosts(context: Context): List<KnownHost> = knownHostsList(knownHosts(context))

    fun isPinned(context: Context, host: String): Boolean =
        trustedHosts(context).any { it.host == host }

    /** Pin [key] for [host] after the user confirmed its fingerprint. */
    fun trust(context: Context, host: String, key: HostKey) {
        val text = knownHostsAdd(knownHosts(context), host, key)
        writeAtomically(knownHostsFile(context), text.toByteArray(Charsets.UTF_8))
    }

    fun forget(context: Context, host: String) {
        val text = knownHostsRemove(knownHosts(context), host)
        writeAtomically(knownHostsFile(context), text.toByteArray(Charsets.UTF_8))
    }

    /** What the git transport needs for SSH remotes; HTTPS ignores it. */
    fun config(context: Context): SshConfig =
        SshConfig(privateKey = load(context)?.privateKey, knownHosts = knownHosts(context))

    // --- plumbing ------------------------------------------------------------

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.setReadable(false, false)
        tmp.setWritable(false, false)
        tmp.setReadable(true, true)
        tmp.setWritable(true, true)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("couldn't write ${target.name}")
        }
    }
}
