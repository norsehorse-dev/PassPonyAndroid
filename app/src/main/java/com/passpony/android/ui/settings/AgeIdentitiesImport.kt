package com.passpony.android.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.passpony.android.R
import com.passpony.android.crypto.AgeIdentityStore
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.util.SecureScreenEffect
import kotlinx.coroutines.launch
import uniffi.pass_ffi.CryptoException

/**
 * The age identities import flow shared by Settings and onboarding:
 * file picker, then (only when the picked file is age-encrypted, as
 * gopass's age backend writes `identities`) a passphrase prompt, then
 * the same validation and install a plaintext file gets. Returns the
 * launcher; the passphrase dialog is emitted where this is called.
 * [onResult] receives the user-facing outcome message.
 */
@Composable
fun rememberAgeIdentitiesImporter(
    appViewModel: AppViewModel,
    onResult: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun import(bytes: ByteArray, passphrase: String?): Boolean =
        try {
            appViewModel.importAgeIdentitiesFile(bytes, passphrase)
            onResult(context.getString(R.string.settings_age_import_success))
            true
        } catch (e: CryptoException.DecryptionFailed) {
            // Wrong passphrase (or not a passphrase file): keep the
            // prompt open so the user can retry; nothing was written.
            dialogError = context.getString(R.string.settings_age_passphrase_wrong)
            false
        } catch (e: Exception) {
            onResult(ageImportFailureMessage(context, e))
            true
        }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            onResult(context.getString(R.string.settings_age_import_failed))
            return@rememberLauncherForActivityResult
        }
        if (AgeIdentityStore.isPassphraseProtected(bytes)) {
            dialogError = null
            pending = bytes
        } else {
            scope.launch { import(bytes, null) }
        }
    }

    pending?.let { bytes ->
        AgeIdentitiesPassphraseDialog(
            error = dialogError,
            busy = busy,
            onSubmit = { passphrase ->
                scope.launch {
                    busy = true
                    dialogError = null
                    val finished = import(bytes, passphrase)
                    busy = false
                    if (finished) {
                        bytes.fill(0)
                        pending = null
                    }
                }
            },
            onDismiss = {
                bytes.fill(0)
                pending = null
            },
        )
    }

    return { launcher.launch(arrayOf("*/*")) }
}

private fun ageImportFailureMessage(context: Context, e: Exception): String =
    when (e) {
        is CryptoException.Unavailable -> context.getString(R.string.settings_age_passphrase_too_costly)
        else -> context.getString(R.string.settings_age_import_failed)
    }

@Composable
private fun AgeIdentitiesPassphraseDialog(
    error: String?,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SecureScreenEffect()
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        // The dialog has its own window; secure it directly rather than
        // relying on the host activity's flag (onboarding does not set one).
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.settings_age_passphrase_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_age_passphrase_message))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.settings_age_passphrase_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(passphrase) },
                enabled = passphrase.isNotEmpty() && !busy
            ) { Text(stringResource(R.string.settings_unlock)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
