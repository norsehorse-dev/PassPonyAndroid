package com.passpony.android.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.pgpCacheDataStore by preferencesDataStore(name = "passpony_pgp_cache_prefs")

/**
 * Time-boxed passphrase cache for a passphrase-protected pass (OpenPGP)
 * secret key. Port of iOS's PassphraseCache.swift: there the secret lives
 * in the app-group Keychain and the expiry stamp in group UserDefaults;
 * here the secret is Keystore-AES-GCM-sealed in this app's private files
 * dir and the stamp lives in DataStore. Same 5-minute grace window,
 * defined here rather than a shared UnlockGate constant since Android has
 * no P11 unlock gate yet — that packet should read GRACE_PERIOD_MILLIS
 * from here when it lands, the way iOS's UnlockGate and PassphraseCache
 * already share one constant.
 *
 * androidx.security:security-crypto (EncryptedSharedPreferences) had every
 * API deprecated as of 1.1.0-beta01 in favor of using Android Keystore
 * directly, so this wraps the platform Keystore APIs itself rather than
 * building a new component on a library deprecated before this packet was
 * written.
 */
object PassphraseCache {
    /** Grace period between required unlocks. */
    const val GRACE_PERIOD_MILLIS: Long = 5 * 60 * 1000

    private const val KEYSTORE_ALIAS = "passpony_pgp_passphrase_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private val STAMP_KEY = longPreferencesKey("pgp_passphrase_stamp")

    private fun sealedFile(context: Context): File = File(context.filesDir, "pgp-passphrase.enc")

    private fun secretKey(): SecretKey {
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

    fun save(context: Context, passphrase: String) {
        clear(context)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ciphertext = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val out = sealedFile(context)
        out.writeBytes(byteArrayOf(iv.size.toByte()) + iv + ciphertext)
        restrictToOwner(out)
        runBlocking {
            context.pgpCacheDataStore.edit { it[STAMP_KEY] = System.currentTimeMillis() }
        }
    }

    /**
     * The cached passphrase, or null once the grace window lapsed — the
     * sealed file is cleared on an expired read, same as iOS clearing the
     * Keychain item on an expired load().
     */
    fun load(context: Context): String? {
        val stamp = runBlocking {
            context.pgpCacheDataStore.data.map { it[STAMP_KEY] }.first()
        } ?: return null
        if (System.currentTimeMillis() - stamp >= GRACE_PERIOD_MILLIS) {
            clear(context)
            return null
        }
        val file = sealedFile(context)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return null
        val ivLen = bytes[0].toInt() and 0xFF
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val ciphertext = bytes.copyOfRange(1 + ivLen, bytes.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** The panic-lock action and background/process-death-independent
     * expiry read both land here. */
    fun clear(context: Context) {
        sealedFile(context).delete()
        runBlocking {
            context.pgpCacheDataStore.edit { it.remove(STAMP_KEY) }
        }
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}
