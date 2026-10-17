package com.passpony.android.passkey

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.passpony.android.R
import com.passpony.android.store.UnlockGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.pass_ffi.webauthnAssertionJson

/**
 * Sign in with a stored passkey (2.0 rc3). Started by the system when the
 * user picks one of PassPony's entries in the passkey sheet; translucent,
 * so a fresh unlock reads as an instant sign-in.
 *
 * The unlock gate is the user verification: within the grace window the
 * passkey signs at once, otherwise the device's biometric or credential
 * prompt comes first. Nothing is decrypted before that. Any failure ends
 * as a cancel or "no credential", never with key material or entry text
 * in an error.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyGetActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        val option = request?.credentialOptions?.filterIsInstance<GetPublicKeyCredentialOption>()?.firstOrNull()
        val entryName = intent.getStringExtra(EXTRA_ENTRY_NAME)
        val rpId = intent.getStringExtra(EXTRA_RP_ID)
        val credentialId = intent.getByteArrayExtra(EXTRA_CREDENTIAL_ID)
        val clientDataHash = option?.clientDataHash
        if (request == null || option == null || entryName == null || rpId == null || credentialId == null) {
            fail(GetCredentialUnknownException())
            return
        }
        // A browser hands over the clientDataHash; anything else was
        // already turned away in the begin step, and is again here.
        if (PasskeyCaller.browserOrigin(this, request.callingAppInfo) == null || clientDataHash == null) {
            fail(NoCredentialException(PasskeyVault.declineMessage(this)))
            return
        }

        fun signNow() {
            lifecycleScope.launch {
                val json = withContext(Dispatchers.IO) {
                    runCatching {
                        val passkey = PasskeyVault.find(this@PasskeyGetActivity, entryName, rpId, credentialId)
                            ?: return@runCatching null
                        val (authData, signature, userHandle) = PasskeyVault.sign(passkey, clientDataHash)
                        webauthnAssertionJson(passkey.credentialId, userHandle, authData, signature, null)
                    }.getOrNull()
                }
                if (json == null) {
                    fail(NoCredentialException())
                    return@launch
                }
                val result = Intent()
                PendingIntentHandler.setGetCredentialResponse(result, GetCredentialResponse(PublicKeyCredential(json)))
                setResult(Activity.RESULT_OK, result)
                finish()
            }
        }

        if (UnlockGate.isFresh(this)) {
            signNow()
        } else {
            UnlockGate.authenticate(
                activity = this,
                reason = getString(R.string.passkey_unlock_reason),
                onSuccess = { signNow() },
                onError = { _, _ -> fail(GetCredentialCancellationException()) }
            )
        }
    }

    private fun fail(e: GetCredentialException) {
        val result = Intent()
        PendingIntentHandler.setGetCredentialException(result, e)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    companion object {
        const val EXTRA_ENTRY_NAME = "com.passpony.android.passkey.EXTRA_ENTRY_NAME"
        const val EXTRA_RP_ID = "com.passpony.android.passkey.EXTRA_RP_ID"
        const val EXTRA_CREDENTIAL_ID = "com.passpony.android.passkey.EXTRA_CREDENTIAL_ID"
    }
}
