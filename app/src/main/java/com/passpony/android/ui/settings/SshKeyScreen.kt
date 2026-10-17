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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.passpony.android.R
import com.passpony.android.crypto.SshKeyStore
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.util.Clipboard
import com.passpony.android.ui.util.SecureScreenEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.pass_ffi.KnownHost
import uniffi.pass_ffi.SshKeyException
import uniffi.pass_ffi.sshKeyIsEncrypted

/**
 * SSH key and trusted hosts (2.0 rc2), reached from Settings and Sync.
 *
 * One key per app: generate an Ed25519 key or import an OpenSSH private
 * key (a passphrase-protected one is decrypted once, here, and then kept
 * sealed by this device). The public key is shown to copy into a git
 * host. Delete asks first, since a host that only knows this key stops
 * accepting the app. Trusted hosts lists what was pinned on first
 * connection; forgetting one makes the next connection ask again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeyScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SecureScreenEffect()

    var publicKey by remember { mutableStateOf(SshKeyStore.publicKey(context)) }
    var details by remember { mutableStateOf<String?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    var hosts by remember { mutableStateOf(SshKeyStore.trustedHosts(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var passphraseError by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        publicKey = SshKeyStore.publicKey(context)
        val key = SshKeyStore.load(context)
        details = key?.let { "${it.algorithm} · ${it.fingerprint}" }
        unreadable = SshKeyStore.hasKey(context) && key == null
        hosts = SshKeyStore.trustedHosts(context)
    }

    LaunchedEffect(Unit) { refresh() }

    fun importKey(bytes: ByteArray, passphrase: String?) {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.Default) { SshKeyStore.import(context, bytes, passphrase) }
                bytes.fill(0)
                pendingImport = null
                message = context.getString(R.string.ssh_imported)
                refresh()
            } catch (e: SshKeyException.WrongPassphrase) {
                passphraseError = context.getString(R.string.ssh_passphrase_wrong)
            } catch (e: Exception) {
                bytes.fill(0)
                pendingImport = null
                message = e.ponyMessage(context)
            } finally {
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        when {
            bytes == null -> message = context.getString(R.string.error_sshkey_not_a_key)
            sshKeyIsEncrypted(bytes) -> {
                passphraseError = null
                pendingImport = bytes
            }
            else -> importKey(bytes, null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ssh_title)) },
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
            val key = publicKey
            when {
                unreadable -> {
                    Text(
                        stringResource(R.string.ssh_unreadable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    DeleteButton(enabled = !busy) { confirmDelete = true }
                }
                key == null -> {
                    Text(stringResource(R.string.ssh_none), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                message = try {
                                    withContext(Dispatchers.Default) { SshKeyStore.generate(context) }
                                    context.getString(R.string.ssh_generated)
                                } catch (e: Exception) {
                                    e.ponyMessage(context)
                                } finally {
                                    busy = false
                                }
                                refresh()
                            }
                        },
                        enabled = !busy
                    ) { Text(stringResource(R.string.ssh_generate)) }
                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !busy) {
                        Text(stringResource(R.string.ssh_import))
                    }
                }
                else -> {
                    Text(
                        stringResource(R.string.ssh_public_key_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    SelectionContainer {
                        Text(
                            key,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    details?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Text(
                        stringResource(R.string.ssh_public_key_help),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        FilledTonalButton(onClick = {
                            // The public key is not a secret, but the plain
                            // copy still clears itself like every other copy.
                            Clipboard.copyEphemeral(context, key)
                            message = context.getString(R.string.ssh_public_copied)
                        }) { Text(stringResource(R.string.ssh_copy_public)) }
                    }
                    DeleteButton(enabled = !busy) { confirmDelete = true }
                }
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.ssh_hosts_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            if (hosts.isEmpty()) {
                Text(
                    stringResource(R.string.ssh_hosts_none),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            for (host in hosts) {
                TrustedHostRow(host) {
                    SshKeyStore.forget(context, host.host)
                    message = context.getString(R.string.ssh_host_forgotten, host.host)
                    refresh()
                }
                HorizontalDivider()
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.ssh_delete_title)) },
            text = { Text(stringResource(R.string.ssh_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        SshKeyStore.delete(context)
                        message = context.getString(R.string.ssh_deleted)
                        refresh()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.ssh_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    pendingImport?.let { bytes ->
        SshPassphraseDialog(
            error = passphraseError,
            busy = busy,
            onSubmit = { passphrase ->
                passphraseError = null
                importKey(bytes, passphrase)
            },
            onDismiss = {
                bytes.fill(0)
                pendingImport = null
            }
        )
    }
}

@Composable
private fun DeleteButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.padding(top = 4.dp)
    ) { Text(stringResource(R.string.ssh_delete)) }
}

@Composable
private fun TrustedHostRow(host: KnownHost, onForget: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(host.host, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${host.key.keyType} · ${host.key.fingerprint}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.secondary
            )
        }
        TextButton(onClick = onForget) { Text(stringResource(R.string.ssh_host_forget)) }
    }
}

@Composable
private fun SshPassphraseDialog(
    error: String?,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.ssh_passphrase_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ssh_passphrase_message))
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
            TextButton(onClick = { onSubmit(passphrase) }, enabled = passphrase.isNotEmpty() && !busy) {
                Text(stringResource(R.string.ssh_import_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
