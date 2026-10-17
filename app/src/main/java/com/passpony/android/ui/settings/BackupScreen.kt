package com.passpony.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.passpony.android.R
import com.passpony.android.crypto.AgeIdentityStore
import com.passpony.android.crypto.PgpKeyStore
import com.passpony.android.store.KeyBackupState
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.util.Clipboard
import com.passpony.android.ui.util.SecureScreenEffect
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.pass_ffi.BackupException
import uniffi.pass_ffi.backupGenerateRecoveryCode
import uniffi.pass_ffi.backupNormalizeRecoveryCode

private sealed class BackupStep {
    data object Idle : BackupStep()
    data class ShowCode(val code: String) : BackupStep()
    data class Confirm(val code: String) : BackupStep()
    data class Ready(val code: String) : BackupStep()
}

/**
 * Keys backup (2.0): an encrypted `.passpony` backup of every key on this
 * device, protected by a generated recovery code the user must type back
 * before saving; a plain export of the age identities file for people
 * who keep their own copy; and restore. The code and the plaintext key
 * material live only in this screen's state, under FLAG_SECURE.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(appViewModel: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SecureScreenEffect()

    var step by remember { mutableStateOf<BackupStep>(BackupStep.Idle) }
    var typed by remember { mutableStateOf("") }
    var confirmError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showPlainWarning by remember { mutableStateOf(false) }
    val backedUp by KeyBackupState.backedUp(context).collectAsState(initial = true)
    val hasAgeIdentities = remember { AgeIdentityStore.hasIdentities(context) }
    val pgpKeyCount = remember { PgpKeyStore.keyDirectory(context).listFiles()?.count { it.isFile } ?: 0 }
    val today = remember { LocalDate.now().toString() }

    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val ready = step as? BackupStep.Ready ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            message = try {
                val bytes = appViewModel.buildBackup(ready.code)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: throw java.io.IOException("couldn't open the file")
                }
                appViewModel.markKeysBackedUp()
                step = BackupStep.Idle
                context.getString(R.string.backup_saved)
            } catch (e: Exception) {
                context.getString(R.string.backup_save_failed, e.ponyMessage(context))
            } finally {
                busy = false
            }
        }
    }

    val savePlain = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val text = AgeIdentityStore.exportText(context) ?: throw java.io.IOException("no identities")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw java.io.IOException("couldn't open the file")
                }
                appViewModel.markKeysBackedUp()
                context.getString(R.string.backup_plain_saved)
            } catch (e: Exception) {
                context.getString(R.string.backup_save_failed, e.ponyMessage(context))
            }
        }
    }

    val launchRestore = rememberBackupRestorer(appViewModel) { result -> message = result }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                stringResource(if (backedUp) R.string.backup_status_done else R.string.backup_status_needed),
                style = MaterialTheme.typography.bodyMedium,
                color = if (backedUp) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.backup_sync_is_not_backup),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.backup_encrypted_header), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.backup_encrypted_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))

            when (val s = step) {
                BackupStep.Idle -> FilledTonalButton(
                    onClick = {
                        message = null
                        step = try {
                            BackupStep.ShowCode(backupGenerateRecoveryCode())
                        } catch (e: BackupException) {
                            message = e.ponyMessage(context)
                            BackupStep.Idle
                        }
                    },
                    enabled = hasAgeIdentities || pgpKeyCount > 0
                ) { Text(stringResource(R.string.backup_create)) }

                is BackupStep.ShowCode -> {
                    Text(stringResource(R.string.backup_code_intro), style = MaterialTheme.typography.bodyMedium)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        Text(
                            s.code,
                            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { Clipboard.copyEphemeral(context, s.code) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.entry_detail_copy))
                        }
                    }
                    Text(
                        stringResource(R.string.backup_code_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick = {
                            typed = ""
                            confirmError = null
                            step = BackupStep.Confirm(s.code)
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text(stringResource(R.string.backup_code_written)) }
                    TextButton(onClick = { step = BackupStep.Idle }) { Text(stringResource(R.string.action_cancel)) }
                }

                is BackupStep.Confirm -> {
                    Text(stringResource(R.string.backup_confirm_intro), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text(stringResource(R.string.backup_code_hint)) },
                        singleLine = true,
                        isError = confirmError != null,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    confirmError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            val normalized = runCatching { backupNormalizeRecoveryCode(typed) }
                            confirmError = when {
                                normalized.isFailure -> normalized.exceptionOrNull()!!.ponyMessage(context)
                                normalized.getOrNull() != s.code -> context.getString(R.string.backup_confirm_mismatch)
                                else -> null
                            }
                            if (confirmError == null) {
                                step = BackupStep.Ready(s.code)
                                saveBackup.launch("passpony-backup-$today.passpony")
                            }
                        },
                        enabled = typed.isNotBlank(),
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text(stringResource(R.string.backup_save)) }
                    TextButton(onClick = { step = BackupStep.ShowCode(s.code) }) {
                        Text(stringResource(R.string.backup_show_code_again))
                    }
                }

                is BackupStep.Ready -> {
                    Text(stringResource(R.string.backup_choose_location), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { saveBackup.launch("passpony-backup-$today.passpony") },
                        enabled = !busy,
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text(stringResource(R.string.backup_save)) }
                    TextButton(onClick = { step = BackupStep.Idle }, enabled = !busy) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }

            if (hasAgeIdentities) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Text(stringResource(R.string.backup_plain_header), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.backup_plain_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                TextButton(onClick = { showPlainWarning = true }) { Text(stringResource(R.string.backup_plain_export)) }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text(stringResource(R.string.backup_restore_header), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.backup_restore_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            TextButton(onClick = launchRestore) { Text(stringResource(R.string.backup_restore_button)) }

            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showPlainWarning) {
        AlertDialog(
            onDismissRequest = { showPlainWarning = false },
            title = { Text(stringResource(R.string.backup_plain_warning_title)) },
            text = { Text(stringResource(R.string.backup_plain_warning_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showPlainWarning = false
                    savePlain.launch("passpony-age-identities.txt")
                }) { Text(stringResource(R.string.backup_plain_export_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showPlainWarning = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
