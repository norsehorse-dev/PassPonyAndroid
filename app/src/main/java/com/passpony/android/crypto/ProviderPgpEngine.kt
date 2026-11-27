package com.passpony.android.crypto

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.util.OpenPgpApi
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.CryptoException

/**
 * 3.0: pass (OpenPGP) crypto done by a provider app. PassPony holds no
 * key material in this mode: the provider decrypts and encrypts, and
 * applies its own expiry and revocation rules.
 *
 * What the rc7 spike settled, for PGPony Android 4.8.2 and OpenKeychain:
 * - a `.gpg-id` fingerprint maps to a 64-bit key ID the provider accepts
 *   (v4: the low 64 bits; v6: the high 64 bits), and the provider picks
 *   the encryption subkey itself;
 * - with armor off the result is a binary message gpg and the built-in
 *   engine both read;
 * - a first call can need more than one provider screen in a row.
 */
class ProviderPgpEngine(
    context: Context,
    private val source: PgpKeySource.Provider,
) : CryptoBackend {
    private val context = context.applicationContext

    init {
        // Bind ahead of the first entry, so a call from the main thread
        // (which cannot wait for the binding) usually finds it ready.
        ProviderConnection.connect(this.context, source)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        val request = ProviderPrompt.takeResume(OpenPgpApi.ACTION_DECRYPT_VERIFY)
            ?: Intent(OpenPgpApi.ACTION_DECRYPT_VERIFY)
        return run(request, ciphertext)
    }

    override fun encrypt(plaintext: ByteArray, recipients: List<String>): ByteArray {
        // pass stores always resolve a .gpg-id; empty means uninitialized.
        if (recipients.isEmpty()) throw CryptoException.NoUsableKey()
        val keyIds = mutableListOf<Long>()
        val userIds = mutableListOf<String>()
        for (line in recipients) {
            val spec = line.trim()
            val id = keyIdOf(spec)
            when {
                id != null -> keyIds += id
                // pass also allows an email address or a name in .gpg-id.
                spec.isNotEmpty() -> userIds += spec
            }
        }
        if (keyIds.isEmpty() && userIds.isEmpty()) throw CryptoException.NoUsableKey()
        val request = ProviderPrompt.takeResume(OpenPgpApi.ACTION_ENCRYPT)
            ?: Intent(OpenPgpApi.ACTION_ENCRYPT)
        // Always this save's recipients, never ones left in a resumed intent.
        request.removeExtra(OpenPgpApi.EXTRA_KEY_IDS)
        request.removeExtra(OpenPgpApi.EXTRA_USER_IDS)
        if (keyIds.isNotEmpty()) request.putExtra(OpenPgpApi.EXTRA_KEY_IDS, keyIds.toLongArray())
        if (userIds.isNotEmpty()) request.putExtra(OpenPgpApi.EXTRA_USER_IDS, userIds.toTypedArray())
        // pass entries are binary .gpg
        request.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, false)
        return run(request, plaintext)
    }

    private fun run(request: Intent, input: ByteArray): ByteArray {
        val api = ProviderConnection.api(context, source)
        val output = ByteArrayOutputStream()
        val result = api.executeApi(request, ByteArrayInputStream(input), output)
        when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
            OpenPgpApi.RESULT_CODE_SUCCESS -> return output.toByteArray()
            OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                @Suppress("DEPRECATION")
                val pending: PendingIntent? = result.getParcelableExtra(OpenPgpApi.RESULT_INTENT)
                if (pending != null && ProviderPrompt.park(pending)) {
                    throw CryptoException.Unavailable(reason = "Your key app needs you first.")
                }
                // Nothing may be shown from here (background work), or the
                // provider sent no screen: the key is not usable right now.
                throw CryptoException.NoUsableKey()
            }
            else -> {
                @Suppress("DEPRECATION")
                val error: OpenPgpError? = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR)
                throw CryptoException.Unavailable(
                    reason = error?.message?.takeIf { it.isNotBlank() } ?: "Your key app reported an error."
                )
            }
        }
    }

    companion object {
        /**
         * The 64-bit key ID for a `.gpg-id` line: a 16-hex key ID as it
         * is, a 40-hex v4 fingerprint's low 64 bits, a 64-hex v6
         * fingerprint's high 64 bits. `0x` and inner spaces tolerated.
         * Null for anything else (an email address, a name).
         */
        fun keyIdOf(line: String): Long? {
            val hex = line.uppercase().removePrefix("0X").replace(" ", "")
            if (hex.isEmpty() || hex.any { it !in "0123456789ABCDEF" }) return null
            val id = when (hex.length) {
                16 -> hex
                40 -> hex.takeLast(16)
                64 -> hex.take(16)
                else -> return null
            }
            return java.lang.Long.parseUnsignedLong(id, 16)
        }
    }
}
