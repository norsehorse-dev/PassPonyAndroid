package com.passpony.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.passpony.android.R
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.AppViewModel
import kotlinx.coroutines.launch
import uniffi.pass_ffi.EntryRef

/**
 * Port of PassPony iOS's ReencryptView: re-encrypt a subtree to its
 * currently-resolved recipients, with a preview of exactly which files
 * will be rewritten shown before anything is touched. Use after a
 * recipients file changed (synced from another device, or edited
 * externally).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReencryptScreen(appViewModel: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by appViewModel.entries.collectAsState()

    var subpath by remember { mutableStateOf("") } // "" = entire store
    var targets by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var scopeExpanded by remember { mutableStateOf(false) }

    val folders = remember(entries) { folderPrefixes(entries) }

    fun preview() {
        try {
            targets = appViewModel.reencryptPreview(subpath)
            message = null
        } catch (e: Exception) {
            message = e.ponyMessage(context)
        }
    }

    fun reencrypt() {
        busy = true
        message = null
        scope.launch {
            try {
                val rewritten = appViewModel.reencryptNow(subpath)
                message = context.getString(R.string.reencrypt_success, rewritten.size)
                targets = null
            } catch (e: Exception) {
                message = e.ponyMessage(context)
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reencrypt_title)) },
                navigationIcon = {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.reencrypt_done)) }
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
            SectionHeader(stringResource(R.string.reencrypt_scope_header))
            Box {
                TextButton(onClick = { scopeExpanded = true }, enabled = !busy) {
                    Text(subpath.ifEmpty { stringResource(R.string.reencrypt_scope_entire_store) })
                }
                DropdownMenu(expanded = scopeExpanded, onDismissRequest = { scopeExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reencrypt_scope_entire_store)) },
                        onClick = {
                            scopeExpanded = false
                            subpath = ""
                            targets = null
                        }
                    )
                    for (folder in folders) {
                        DropdownMenuItem(
                            text = { Text(folder) },
                            onClick = {
                                scopeExpanded = false
                                subpath = folder
                                targets = null
                            }
                        )
                    }
                }
            }
            TextButton(onClick = { preview() }, enabled = !busy) {
                Text(stringResource(R.string.reencrypt_preview))
            }
            Text(
                stringResource(R.string.reencrypt_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            targets?.let { list ->
                Spacer(Modifier.height(16.dp))
                SectionHeader(stringResource(R.string.reencrypt_will_rewrite, list.size))
                for (name in list) {
                    Text(name, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { reencrypt() },
                    enabled = !busy && list.isNotEmpty(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.reencrypt_now))
                }
            }

            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Distinct folder prefixes in the store, deepest included -- matches
 * iOS ReencryptView.folders. */
private fun folderPrefixes(entries: List<EntryRef>): List<String> {
    val out = sortedSetOf<String>()
    for (entry in entries) {
        val components = entry.name.split("/").dropLast(1)
        var path = ""
        for (component in components) {
            path = if (path.isEmpty()) component else "$path/$component"
            out += path
        }
    }
    return out.toList()
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
}
