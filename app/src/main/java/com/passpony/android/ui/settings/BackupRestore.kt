package com.passpony.android.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.passpony.android.R
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.AppViewModel
import kotlinx.coroutines.launch
import uniffi.pass_ffi.CryptoException
import uniffi.pass_ffi.SshKey
import uniffi.pass_ffi.ageIsEncryptedIdentitiesFile
import uniffi.pass_ffi.backupNormalizeRecoveryCode

/**
 * Restore from a `.passpony` backup, shared by the Backup screen and the
 * onboarding import step: file picker, then a recovery-code prompt, then
 * the restore. A mistyped code is caught by the code's check character
 * before any decrypting; a code that is well-formed but wrong keeps the
 * prompt open. Nothing is written until the backup has decrypted and
 * parsed in full.
 */
@Composable
fun rememberBackupRestorer(appViewModel: AppViewModel, onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sshToConfirm by remember { mutableStateOf<SshKey?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        when {
            bytes == null -> onResult(context.getString(R.string.backup_restore_unreadable))
            !ageIsEncryptedIdentitiesFile(bytes) -> onResult(context.getString(R.string.error_backup_not_a_backup))
            else -> {
                dialogError = null
                pending = bytes
            }
        }
    }

    pending?.let { bytes ->
        RecoveryCodeDialog(
            error = dialogError,
            busy = busy,
            onSubmit = { typed ->
                val code = try {
                    backupNormalizeRecoveryCode(typed)
                } catch (e: Exception) {
                    dialogError = e.ponyMessage(context)
                    return@RecoveryCodeDialog
                }
                busy = true
                dialogError = null
                scope.launch {
                    try {
                        val result = appViewModel.restoreBackup(bytes, code)
                        onResult(restoreSummary(context, result))
                        sshToConfirm = result.sshKeyToConfirm
                        pending = null
                    } catch (e: CryptoException.DecryptionFailed) {
                        dialogError = context.getString(R.string.backup_restore_wrong_code)
                    } catch (e: Exception) {
                        onResult(e.ponyMessage(context))
                        pending = null
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { pending = null }
        )
    }

    sshToConfirm?.let { key ->
        // The backup's SSH key differs from this app's: replacing it could
        // lock the app out of a host that only knows the current key.
        AlertDialog(
            onDismissRequest = {
                sshToConfirm = null
                onResult(context.getString(R.string.backup_ssh_kept))
            },
            title = { Text(stringResource(R.string.backup_ssh_replace_title)) },
            text = { Text(stringResource(R.string.backup_ssh_replace_body)) },
            confirmButton = {
                TextButton(onClick = {
                    appViewModel.replaceSshKey(key)
                    sshToConfirm = null
                    onResult(context.getString(R.string.backup_ssh_replaced))
                }) { Text(stringResource(R.string.backup_ssh_replace)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    sshToConfirm = null
                    onResult(context.getString(R.string.backup_ssh_kept))
                }) { Text(stringResource(R.string.backup_ssh_keep)) }
            }
        )
    }

    return { launcher.launch(arrayOf("*/*")) }
}

internal fun restoreSummary(context: Context, r: AppViewModel.RestoreResult): String {
    val parts = mutableListOf<String>()
    if (r.identitiesAdded > 0) {
        parts += context.resources.getQuantityString(R.plurals.backup_restore_identities, r.identitiesAdded, r.identitiesAdded)
    }
    if (r.pgpKeysAdded > 0) {
        parts += context.resources.getQuantityString(R.plurals.backup_restore_pgp_keys, r.pgpKeysAdded, r.pgpKeysAdded)
    }
    if (r.sshKeyRestored) {
        parts += context.getString(R.string.backup_restore_ssh_key)
    }
    val head = if (parts.isEmpty() && r.sshKeyToConfirm == null) {
        context.getString(R.string.backup_restore_nothing_new)
    } else if (parts.isEmpty()) {
        context.getString(R.string.backup_restore_ssh_differs)
    } else {
        context.getString(R.string.backup_restore_done, parts.joinToString(", "))
    }
    return if (r.unknownSections > 0) {
        head + " " + context.resources.getQuantityString(
            R.plurals.backup_restore_unknown, r.unknownSections, r.unknownSections
        )
    } else {
        head
    }
}

@Composable
private fun RecoveryCodeDialog(
    error: String?,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.backup_restore_title)) },
        text = {
            Column {
                Text(stringResource(R.string.backup_restore_message))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.backup_code_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password
                    ),
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
            TextButton(onClick = { onSubmit(code) }, enabled = code.isNotBlank() && !busy) {
                Text(stringResource(R.string.backup_restore_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
