package com.passpony.android.crypto

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.util.OpenPgpApi
import uniffi.pass_ffi.CryptoException
import uniffi.pass_ffi.pgpKeyInfos

/**
 * 3.0: one call to the provider, shared by the engine and by the few
 * screens that talk to the provider directly (choosing a key, checking
 * the connection). A call the provider wants to show a screen for is
 * parked with [ProviderPrompt] and fails; run it again once the screen
 * came back OK.
 */
object ProviderOps {
    class Answer(val result: Intent, val output: ByteArray)

    /** Throws a [CryptoException] for anything but success. */
    fun execute(
        context: Context,
        source: PgpKeySource.Provider,
        request: Intent,
        input: ByteArray? = null,
    ): Answer {
        // After a provider screen, the intent it handed back is what gets
        // sent, as the API specifies, with this call's own extras on top
        // and never the recipients of an earlier call.
        val action = request.action.orEmpty()
        val sent = ProviderPrompt.takeResume(action)?.apply {
            removeExtra(OpenPgpApi.EXTRA_KEY_IDS)
            removeExtra(OpenPgpApi.EXTRA_USER_IDS)
            request.extras?.let { putExtras(it) }
        } ?: request

        val api = ProviderConnection.api(context, source)
        val output = ByteArrayOutputStream()
        val result = api.executeApi(sent, input?.let { ByteArrayInputStream(it) }, output)
        when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
            OpenPgpApi.RESULT_CODE_SUCCESS -> return Answer(result, output.toByteArray())
            OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                @Suppress("DEPRECATION")
                val pending: PendingIntent? = result.getParcelableExtra(OpenPgpApi.RESULT_INTENT)
                if (pending != null && ProviderPrompt.park(pending, action)) {
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

    /** Asks the provider whether PassPony may use it. Its consent screen is parked like any other. */
    fun checkPermission(context: Context, source: PgpKeySource.Provider) {
        execute(context, source, Intent(OpenPgpApi.ACTION_CHECK_PERMISSION))
    }

    /**
     * The full fingerprint of a key the person picks in the provider, for
     * a new store's `.gpg-id`. The provider's key picker is its own
     * screen, so the first run parks it and fails; the run after it came
     * back OK returns the fingerprint. A fingerprint and not a key ID,
     * since that is what desktop `pass` users expect to see in the file.
     */
    fun pickKeyFingerprint(context: Context, source: PgpKeySource.Provider): String {
        val picked = execute(context, source, Intent(OpenPgpApi.ACTION_GET_SIGN_KEY_ID))
        val keyId = picked.result.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0L)
        if (keyId == 0L) throw CryptoException.NoUsableKey()
        val request = Intent(OpenPgpApi.ACTION_GET_KEY)
            .putExtra(OpenPgpApi.EXTRA_KEY_ID, keyId)
            .putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, false)
        val key = execute(context, source, request)
        return pgpKeyInfos(key.output).firstOrNull()?.primaryFingerprint?.uppercase()
            ?: throw CryptoException.Unavailable(reason = "Your key app did not hand over that key.")
    }
}
