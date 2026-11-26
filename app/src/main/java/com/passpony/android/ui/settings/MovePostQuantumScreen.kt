package com.passpony.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.passpony.android.R
import com.passpony.android.store.KeyBackupState
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.theme.SectionHeader
import kotlinx.coroutines.launch
import uniffi.pass_ffi.ReencryptReport

/**
 * 3.0: move a classic passage store to post-quantum keys, or finish a
 * move that did not reach every entry.
 *
 * Nothing is rewritten until this device has a post-quantum key and a
 * keys backup made after that key existed, so the backup can open the
 * store as it will be. The key and the backup are separate steps the
 * person takes here; the move itself is one button.
 *
 * Once the store's recipients file is post-quantum the same screen only
 * counts the entries still on classic keys and offers to save them
 * again. Running that twice is harmless.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovePostQuantumScreen(appViewModel: AppViewModel, onDone: () -> Unit, onBackup: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storeKeys by appViewModel.storeKeys.collectAsState()
    val backedUp by KeyBackupState.backedUp(context).collectAsState(initial = false)

    var hasKey by remember { mutableStateOf(appViewModel.hasPostQuantumKey()) }
    var blockers by remember { mutableStateOf<List<String>>(emptyList()) }
    var classicLeft by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ReencryptReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(storeKeys, report) {
        blockers = appViewModel.postQuantumBlockers()
        classicLeft = appViewModel.classicEntryCount()
    }

    fun createKey() {
        busy = true
        error = null
        scope.launch {
            try {
                appViewModel.createPostQuantumKey()
                hasKey = appViewModel.hasPostQuantumKey()
            } catch (e: Exception) {
                error = e.ponyMessage(context)
            } finally {
                busy = false
            }
        }
    }

    fun move() {
        busy = true
        error = null
        scope.launch {
            try {
                report = appViewModel.moveToPostQuantum()
            } catch (e: Exception) {
                error = e.ponyMessage(context)
            } finally {
                busy = false
            }
        }
    }

    // Leaving mid-run would not stop the rewrite, only hide its result.
    BackHandler(enabled = busy) {}

    val moved = storeKeys?.postQuantum == true
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.move_pq_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = !busy) {
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
            val done = report
            if (done != null) {
                SectionHeader(stringResource(R.string.move_pq_done_header))
                Text(context.resources.getQuantityString(R.plurals.move_pq_done, done.rewritten.size, done.rewritten.size))
                if (done.skipped > 0u) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        context.resources.getQuantityString(R.plurals.move_pq_skipped, done.skipped.toInt(), done.skipped.toInt()),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.move_pq_done_history), style = small, color = muted)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.move_pq_done_devices), style = small, color = muted)
                if (!backedUp) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.move_pq_step_backup))
                    TextButton(onClick = onBackup) { Text(stringResource(R.string.move_pq_backup_button)) }
                }
            } else if (moved) {
                val left = classicLeft
                if (left == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (left == 0) {
                    Text(stringResource(R.string.move_pq_none_left))
                } else {
                    Text(context.resources.getQuantityString(R.plurals.move_pq_left, left, left))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.move_pq_finish_body), style = small, color = muted)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { move() }, enabled = !busy && hasKey && blockers.isEmpty()) {
                        Text(stringResource(R.string.move_pq_finish_button))
                    }
                }
            } else {
                Text(stringResource(R.string.move_pq_intro))
                Spacer(Modifier.height(16.dp))
                SectionHeader(stringResource(R.string.move_pq_before_header))
                for (line in listOf(
                    R.string.move_pq_warn_devices,
                    R.string.move_pq_warn_locked,
                    R.string.move_pq_warn_tools,
                    R.string.move_pq_warn_history,
                )) {
                    Text(stringResource(line), modifier = Modifier.padding(vertical = 4.dp))
                }

                Spacer(Modifier.height(16.dp))
                SectionHeader(stringResource(R.string.move_pq_steps_header))
                if (!hasKey) {
                    Text(stringResource(R.string.move_pq_step_key))
                    TextButton(onClick = { createKey() }, enabled = !busy) {
                        Text(stringResource(R.string.move_pq_key_button))
                    }
                } else if (!backedUp) {
                    Text(stringResource(R.string.move_pq_step_backup))
                    TextButton(onClick = onBackup, enabled = !busy) {
                        Text(stringResource(R.string.move_pq_backup_button))
                    }
                } else {
                    Text(stringResource(R.string.move_pq_step_ready))
                }
                if (blockers.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.move_pq_blocked_folders), color = MaterialTheme.colorScheme.error)
                    for (folder in blockers) {
                        Text(folder, style = small.copy(fontFamily = FontFamily.Monospace))
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { move() },
                    enabled = !busy && hasKey && backedUp && blockers.isEmpty(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.move_pq_button))
                }
            }

            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.move_pq_working), style = small, color = muted)
            }

            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = small, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
