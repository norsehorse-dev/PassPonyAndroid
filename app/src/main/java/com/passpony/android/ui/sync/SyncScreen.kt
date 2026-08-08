package com.passpony.android.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.passpony.android.R
import com.passpony.android.ui.AppViewModel
import uniffi.pass_ffi.ConflictChoice
import uniffi.pass_ffi.SyncStatus

/**
 * Port of PassPony iOS's SyncView: status, publish, clone, remote
 * management, sync now / push, and the per-file conflict dialog.
 * Section visibility follows iOS exactly: no repo yet -> publish +
 * clone; repo with no remote -> actions + publish; repo with a remote
 * -> actions + remote management. [status] doubles as the "has a repo"
 * flag the same way iOS checks `model.git == nil`, since AppViewModel
 * only ever has a non-null [AppViewModel.syncStatus] once [AppViewModel.git]
 * itself is non-null.
 *
 * The conflict dialog has no working Cancel, matching SyncView.swift's
 * actual behavior today rather than docs/conflict-ux.md's aspirational
 * spec: PassPonyCore's ConflictResolver callback only ever returns a
 * ConflictChoice, with no way to abort an in-flight rebase, and iOS's
 * own confirmationDialog Cancel button isn't wired to resume its
 * blocked continuation either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    appViewModel: AppViewModel,
    onDone: () -> Unit,
    syncViewModel: SyncViewModel = viewModel()
) {
    val status by appViewModel.syncStatus.collectAsState()
    var cloneUrl by remember { mutableStateOf("") }
    var publishUrl by remember { mutableStateOf("") }
    val busy = syncViewModel.busy
    val message = syncViewModel.message
    val conflictPath = syncViewModel.resolver.conflictPath

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.sync_done)) }
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
            StatusSection(status)
            Spacer(Modifier.height(16.dp))

            if (status == null) {
                PublishSection(
                    url = publishUrl,
                    onUrlChange = { publishUrl = it },
                    busy = busy,
                    onPublish = { syncViewModel.publish(appViewModel, publishUrl) }
                )
                Spacer(Modifier.height(16.dp))
                CloneSection(
                    url = cloneUrl,
                    onUrlChange = { cloneUrl = it },
                    busy = busy,
                    onClone = { syncViewModel.clone(appViewModel, cloneUrl) }
                )
            } else {
                ActionsSection(
                    busy = busy,
                    onSyncNow = { syncViewModel.syncNow(appViewModel) },
                    onPush = { syncViewModel.push(appViewModel) }
                )
                Spacer(Modifier.height(16.dp))
                if (!status.hasRemote) {
                    PublishSection(
                        url = publishUrl,
                        onUrlChange = { publishUrl = it },
                        busy = busy,
                        onPublish = { syncViewModel.publish(appViewModel, publishUrl) }
                    )
                } else {
                    RemoteSection(
                        currentUrl = appViewModel.remoteUrl(),
                        newUrl = publishUrl,
                        onNewUrlChange = { publishUrl = it },
                        busy = busy,
                        onUpdate = {
                            syncViewModel.updateRemote(appViewModel, publishUrl)
                            publishUrl = ""
                        }
                    )
                }
            }

            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    conflictPath?.let { path ->
        AlertDialog(
            // No dismiss-by-tapping-outside: see the class doc on why
            // there's no working Cancel here yet.
            onDismissRequest = {},
            title = { Text(stringResource(R.string.sync_conflict_title, path)) },
            text = {
                Column {
                    Text(stringResource(R.string.sync_conflict_body))
                    Spacer(Modifier.height(16.dp))
                    TextButton(
                        onClick = { syncViewModel.resolveConflict(ConflictChoice.KEEP_LOCAL) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.sync_conflict_keep_local)) }
                    TextButton(
                        onClick = { syncViewModel.resolveConflict(ConflictChoice.KEEP_REMOTE) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.sync_conflict_keep_remote)) }
                    TextButton(
                        onClick = { syncViewModel.resolveConflict(ConflictChoice.KEEP_BOTH) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.sync_conflict_keep_both)) }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusSection(status: SyncStatus?) {
    SectionHeader(stringResource(R.string.sync_status_header))
    if (status == null) {
        Text(stringResource(R.string.sync_no_repository), style = MaterialTheme.typography.bodyMedium)
    } else {
        LabeledRow(stringResource(R.string.sync_unpushed), status.ahead.toString())
        LabeledRow(stringResource(R.string.sync_behind_remote), status.behind.toString())
        LabeledRow(
            stringResource(R.string.sync_remote_label),
            stringResource(if (status.hasRemote) R.string.sync_remote_configured else R.string.sync_remote_none)
        )
    }
}

@Composable
private fun PublishSection(url: String, onUrlChange: (String) -> Unit, busy: Boolean, onPublish: () -> Unit) {
    SectionHeader(stringResource(R.string.sync_publish_header))
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        label = { Text(stringResource(R.string.sync_publish_url_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
    TextButton(onClick = onPublish, enabled = url.isNotEmpty() && !busy) {
        Text(stringResource(R.string.sync_publish))
    }
    Text(
        stringResource(R.string.sync_publish_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
private fun CloneSection(url: String, onUrlChange: (String) -> Unit, busy: Boolean, onClone: () -> Unit) {
    SectionHeader(stringResource(R.string.sync_clone_header))
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        label = { Text(stringResource(R.string.sync_publish_url_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
    TextButton(
        onClick = onClone,
        enabled = url.isNotEmpty() && !busy,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Text(stringResource(R.string.sync_clone_button))
    }
    Text(
        stringResource(R.string.sync_clone_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
private fun RemoteSection(
    currentUrl: String?,
    newUrl: String,
    onNewUrlChange: (String) -> Unit,
    busy: Boolean,
    onUpdate: () -> Unit
) {
    SectionHeader(stringResource(R.string.sync_remote_header))
    LabeledRow(stringResource(R.string.sync_remote_current), SyncMessages.redacted(currentUrl ?: "—"))
    OutlinedTextField(
        value = newUrl,
        onValueChange = onNewUrlChange,
        label = { Text(stringResource(R.string.sync_remote_new_url_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
    TextButton(onClick = onUpdate, enabled = newUrl.isNotEmpty() && !busy) {
        Text(stringResource(R.string.sync_update_remote))
    }
}

@Composable
private fun ActionsSection(busy: Boolean, onSyncNow: () -> Unit, onPush: () -> Unit) {
    SectionHeader(stringResource(R.string.sync_actions_header))
    Row(modifier = Modifier.padding(top = 8.dp)) {
        TextButton(onClick = onSyncNow, enabled = !busy) { Text(stringResource(R.string.sync_now)) }
        TextButton(onClick = onPush, enabled = !busy) { Text(stringResource(R.string.sync_push)) }
    }
    if (busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
