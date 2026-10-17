package com.passpony.android.passkey

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.passpony.android.R
import com.passpony.android.store.UnlockGate
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.theme.PassPonyTheme
import com.passpony.android.ui.util.SecureScreenEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.pass_ffi.PasskeyException
import uniffi.pass_ffi.WebAuthnCreateOptions
import uniffi.pass_ffi.webauthnParseCreateOptions
import uniffi.pass_ffi.webauthnRegistrationJson

/**
 * Save a new passkey (2.0 rc3). Started by the system when the user picks
 * PassPony in a browser's "create a passkey" sheet. Asks which entry the
 * passkey goes in (the site's existing entry when there is one, else a new
 * entry named after the site), then unlocks, generates the key, writes the
 * entry with one commit, and answers the browser.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyCreateActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        val calling = request?.callingRequest as? CreatePublicKeyCredentialRequest
        if (request == null || calling == null) {
            fail(CreateCredentialUnknownException())
            return
        }
        if (PasskeyCaller.browserOrigin(this, request.callingAppInfo) == null || calling.clientDataHash == null) {
            fail(CreateCredentialNoCreateOptionException(PasskeyVault.declineMessage(this)))
            return
        }
        val options = try {
            webauthnParseCreateOptions(calling.requestJson)
        } catch (e: PasskeyException.NoSupportedAlgorithm) {
            fail(CreatePublicKeyCredentialDomException(NotSupportedError(), e.message))
            return
        } catch (e: Exception) {
            fail(CreateCredentialUnknownException())
            return
        }
        // WebAuthn: a credential the site already knows means "already
        // registered", which the site turns into its own message.
        val known = PasskeyIndex.forRpId(this, PasskeyVault.format(this), options.rpId)
        if (options.excludeCredentials.any { ex -> known.any { it.credentialId.contentEquals(ex) } }) {
            fail(CreatePublicKeyCredentialDomException(InvalidStateError()))
            return
        }
        val suggested = PasskeyVault.suggestedEntry(this, options.rpId)

        setContent {
            PassPonyTheme {
                SecureScreenEffect()
                var name by remember { mutableStateOf(suggested) }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                AlertDialog(
                    onDismissRequest = { if (!busy) fail(CreateCredentialCancellationException()) },
                    title = { Text(stringResource(R.string.passkey_create_title)) },
                    text = {
                        Column {
                            Text(
                                stringResource(R.string.passkey_create_for, options.rpId),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (options.userName.isNotEmpty()) {
                                Text(
                                    options.userName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text(stringResource(R.string.xc_entry)) },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                            )
                            Text(
                                stringResource(R.string.passkey_create_entry_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            error?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                busy = true
                                error = null
                                save(name.trim().trim('/'), options) { message ->
                                    busy = false
                                    error = message
                                }
                            },
                            enabled = !busy && name.trim().trim('/').isNotEmpty()
                        ) { Text(stringResource(R.string.action_save)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { fail(CreateCredentialCancellationException()) }, enabled = !busy) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
        }
    }

    private fun save(
        entryName: String,
        options: WebAuthnCreateOptions,
        onError: (String) -> Unit,
    ) {
        fun registerNow() {
            lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        PasskeyVault.register(
                            context = this@PasskeyCreateActivity,
                            entryName = entryName,
                            rpId = options.rpId,
                            userHandle = options.userHandle,
                            username = options.userName,
                            userDisplayName = options.userDisplayName,
                        )
                    }
                }
                outcome.onSuccess { registration ->
                    val json = webauthnRegistrationJson(registration, null)
                    val result = Intent()
                    PendingIntentHandler.setCreateCredentialResponse(result, CreatePublicKeyCredentialResponse(json))
                    setResult(Activity.RESULT_OK, result)
                    finish()
                }.onFailure { e ->
                    onError(e.ponyMessage(this@PasskeyCreateActivity))
                }
            }
        }
        if (UnlockGate.isFresh(this)) {
            registerNow()
        } else {
            UnlockGate.authenticate(
                activity = this,
                reason = getString(R.string.passkey_unlock_reason),
                onSuccess = { registerNow() },
                onError = { _, _ -> onError(getString(R.string.passkey_unlock_needed)) }
            )
        }
    }

    private fun fail(e: CreateCredentialException) {
        val result = Intent()
        PendingIntentHandler.setCreateCredentialException(result, e)
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
