package com.passpony.android.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.passpony.android.BuildConfig
import com.passpony.android.R
import com.passpony.android.crypto.PassphraseCache
import com.passpony.android.crypto.PgpEngine
import com.passpony.android.crypto.PgpKeyStore
import com.passpony.android.store.StorePaths
import com.passpony.android.store.UnlockGate
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.util.SecureScreenEffect
import java.io.File
import kotlinx.coroutines.launch
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.SyncStatus
import uniffi.pass_ffi.coreVersion

/**
 * Port of PassPony iOS's SettingsView: store format switch, OpenPGP key
 * management for pass stores, maintenance (re-encrypt), language,
 * About, and diagnostics. InitializeStoreView and ReencryptView are
 * separate nav destinations here (iOS presents them as sheets); coming
 * back from either re-enters this composable fresh, which is what
 * re-reads the key list / .gpg-id / locked-key state -- the same
 * refresh iOS drives explicitly via onAppear / sheet onDismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appViewModel: AppViewModel,
    onDone: () -> Unit,
    onInitializeStore: () -> Unit,
    onReencrypt: () -> Unit,
    onLock: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Settings shows key fingerprints, .gpg-id recipients, and the pass
    // key passphrase field -- all key material, per SecureWindow.kt's
    // own doc comment anticipating this screen.
    SecureScreenEffect()

    var format by remember { mutableStateOf(appViewModel.format) }
    var keyFiles by remember { mutableStateOf(emptyList<PgpKeyStore.KeyFileInfo>()) }
    var lockedKeys by remember { mutableStateOf(emptyList<String>()) }
    var keyPassphrase by remember { mutableStateOf("") }
    var gpgIdLines by remember { mutableStateOf(emptyList<String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    val entries by appViewModel.entries.collectAsState()
    val syncStatus by appViewModel.syncStatus.collectAsState()

    fun refreshKeys() {
        keyFiles = PgpKeyStore.availableKeys(context)
        if (format == StoreFormat.PASS) {
            val gpgId = StorePaths.gpgIdFile(context)
            gpgIdLines = if (gpgId.exists()) KeySummary.parseGpgId(gpgId.readText()) else emptyList()
            // Building the engine is what discovers locked keys --
            // opening the store alone never decrypts, so this must
            // actually load one. Return value unused on purpose.
            PgpEngine.load(context)
            lockedKeys = PgpKeyStore.lockedKeyFiles
        } else {
            gpgIdLines = emptyList()
            lockedKeys = emptyList()
        }
    }

    LaunchedEffect(format) { refreshKeys() }

    val keyImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val keyDir = PgpKeyStore.keyDirectory(context)
        var imported = 0
        for (uri in uris) {
            val name = displayName(context, uri) ?: continue
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: continue
                File(keyDir, name).writeBytes(bytes)
                imported++
            } catch (e: Exception) {
                message = context.getString(R.string.settings_import_failed, name, e.message ?: "")
            }
        }
        if (imported > 0) {
            message = context.getString(R.string.settings_import_success, imported)
        }
        refreshKeys()
        appViewModel.openStore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.settings_done)) }
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
            SessionSection(onLock = onLock)

            Spacer(Modifier.height(16.dp))
            FormatSection(
                format = format,
                onSelect = { newFormat ->
                    format = newFormat
                    scope.launch { appViewModel.switchFormat(newFormat) }
                }
            )

            if (format == StoreFormat.PASS) {
                Spacer(Modifier.height(16.dp))
                OpenPgpKeysSection(
                    keyFiles = keyFiles,
                    onImport = { keyImportLauncher.launch(arrayOf("*/*")) },
                    onDelete = { name ->
                        File(PgpKeyStore.keyDirectory(context), name).delete()
                        refreshKeys()
                        appViewModel.openStore()
                    }
                )

                if (lockedKeys.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    LockedKeysSection(
                        lockedKeys = lockedKeys,
                        passphrase = keyPassphrase,
                        onPassphraseChange = { keyPassphrase = it },
                        onUnlock = {
                            val attempt = keyPassphrase
                            keyPassphrase = ""
                            val triedAgainst = lockedKeys.size
                            PgpEngine.load(context, passphrase = attempt)
                            val stillLocked = PgpKeyStore.lockedKeyFiles
                            message = when {
                                stillLocked.isEmpty() -> {
                                    PassphraseCache.save(context, attempt)
                                    appViewModel.openStore()
                                    context.getString(R.string.settings_key_unlocked)
                                }
                                stillLocked.size == triedAgainst ->
                                    context.getString(R.string.settings_wrong_passphrase)
                                else -> {
                                    PassphraseCache.save(context, attempt)
                                    appViewModel.openStore()
                                    context.getString(R.string.settings_unlocked_some, stillLocked.size)
                                }
                            }
                            lockedKeys = stillLocked
                            refreshKeys()
                        }
                    )
                }

                Spacer(Modifier.height(16.dp))
                GpgIdSection(
                    gpgIdLines = gpgIdLines,
                    hasKeys = keyFiles.isNotEmpty(),
                    onInitialize = onInitializeStore
                )
            }

            Spacer(Modifier.height(16.dp))
            MaintenanceSection(onReencrypt = onReencrypt)

            Spacer(Modifier.height(16.dp))
            LanguageSection(
                onSelect = { tag ->
                    LanguageManager.activityOf(context)?.let { LanguageManager.apply(it, tag) }
                }
            )
            TextButton(onClick = {
                scope.launch { StorePaths.setOnboardingCompleted(context, false) }
                onDone()
            }) {
                Text(stringResource(R.string.settings_tour_replay))
            }

            Spacer(Modifier.height(16.dp))
            AboutSection(context)

            Spacer(Modifier.height(16.dp))
            OtherAppsSection(context)

            Spacer(Modifier.height(16.dp))
            DiagnosticsSection(
                storePath = StorePaths.storeRoot(context, format).absolutePath,
                format = format,
                entryCount = entries.size,
                syncStatus = syncStatus
            )

            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return cursor.getString(idx)
        }
    }
    return uri.lastPathSegment
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
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
private fun SessionSection(onLock: () -> Unit) {
    SectionHeader(stringResource(R.string.settings_session_header))
    TextButton(
        onClick = onLock,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
        Text(stringResource(R.string.settings_lock_now))
    }
    Text(
        stringResource(R.string.settings_session_footer, (UnlockGate.GRACE_PERIOD_MILLIS / 60000L).toInt()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
private fun FormatSection(format: StoreFormat, onSelect: (StoreFormat) -> Unit) {
    Row {
        TextButton(onClick = { onSelect(StoreFormat.PASSAGE) }) {
            Text(
                stringResource(R.string.settings_format_passage),
                fontWeight = if (format == StoreFormat.PASSAGE) FontWeight.Bold else FontWeight.Normal
            )
        }
        TextButton(onClick = { onSelect(StoreFormat.PASS) }) {
            Text(
                stringResource(R.string.settings_format_pass),
                fontWeight = if (format == StoreFormat.PASS) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
    Text(
        stringResource(R.string.settings_format_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
private fun OpenPgpKeysSection(
    keyFiles: List<PgpKeyStore.KeyFileInfo>,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_pgp_keys_header))
    if (keyFiles.isEmpty()) {
        Text(
            stringResource(R.string.settings_pgp_keys_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
    for (key in keyFiles) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(key.file)
                Text(
                    KeySummary.summaryLine(key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            IconButton(onClick = { onDelete(key.file) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_key_delete))
            }
        }
    }
    TextButton(onClick = onImport) {
        Text(stringResource(R.string.settings_pgp_import))
    }
}

@Composable
private fun LockedKeysSection(
    lockedKeys: List<String>,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    onUnlock: () -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_locked_keys_header))
    for (file in lockedKeys) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(file)
        }
    }
    OutlinedTextField(
        value = passphrase,
        onValueChange = onPassphraseChange,
        label = { Text(stringResource(R.string.settings_key_passphrase_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
    TextButton(onClick = onUnlock, enabled = passphrase.isNotEmpty()) {
        Text(stringResource(R.string.settings_unlock))
    }
    Text(
        stringResource(R.string.settings_locked_keys_footer, (UnlockGate.GRACE_PERIOD_MILLIS / 60000L).toInt()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
private fun GpgIdSection(gpgIdLines: List<String>, hasKeys: Boolean, onInitialize: () -> Unit) {
    SectionHeader(stringResource(R.string.settings_gpgid_header))
    if (gpgIdLines.isEmpty()) {
        TextButton(onClick = onInitialize, enabled = hasKeys) {
            Text(stringResource(R.string.settings_gpgid_init))
        }
        Text(
            stringResource(R.string.settings_gpgid_footer_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    } else {
        for (line in gpgIdLines) {
            Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
        Text(
            stringResource(R.string.settings_gpgid_footer_set),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun MaintenanceSection(onReencrypt: () -> Unit) {
    SectionHeader(stringResource(R.string.settings_maintenance_header))
    TextButton(onClick = onReencrypt) {
        Text(stringResource(R.string.settings_reencrypt))
    }
}

@Composable
private fun LanguageSection(onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentTag = LanguageManager.currentTag()
    val currentName = LanguageManager.supported.firstOrNull { it.first == currentTag }?.second
        ?: LanguageManager.supported.first().second
    SectionHeader(stringResource(R.string.settings_language))
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(currentName)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((tag, name) in LanguageManager.supported) {
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onSelect(tag)
                    }
                )
            }
        }
    }
}

@Composable
private fun AboutSection(context: Context) {
    SectionHeader(stringResource(R.string.settings_about_header))
    LabeledRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
    LinkRow(
        title = stringResource(R.string.settings_app_repo_title),
        subtitle = stringResource(R.string.settings_app_repo_subtitle),
        url = APP_REPO_URL,
        context = context
    )
    LinkRow(
        title = stringResource(R.string.settings_core_repo_title),
        subtitle = stringResource(R.string.settings_core_repo_subtitle),
        url = CORE_REPO_URL,
        context = context
    )
    TextButton(onClick = { openUrl(context, "mailto:norsehorse@norsehor.se") }) {
        Text(stringResource(R.string.settings_contact))
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, url: String, context: Context) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openUrl(context, url) }
            .padding(vertical = 6.dp)
    ) {
        Text(title)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

private data class OtherApp(val name: String, val tagline: String, val url: String)

private val OTHER_APPS = listOf(
    OtherApp("PGPony", "OpenPGP messages, files, and keys", "https://pgpony.app"),
    OtherApp("AgePony", "Simple, modern file encryption (age)", "https://agepony.com"),
    OtherApp("QuorumPony", "Quorum key backup & recovery", "https://quorumpony.com"),
    OtherApp("CarrierPony", "PGP chat and encrypted files", "https://carrierpony.com"),
    OtherApp("BurnPony", "Self-destructing encrypted messages", "https://burnpony.app"),
    OtherApp("RelayPony", "Encrypted file transfer", "https://relaypony.app"),
)

@Composable
private fun OtherAppsSection(context: Context) {
    SectionHeader(stringResource(R.string.settings_other_apps_header))
    LinkRow(
        title = stringResource(R.string.settings_other_apps_site_title),
        subtitle = stringResource(R.string.settings_other_apps_site_subtitle),
        url = NORSEHORSE_SITE_URL,
        context = context
    )
    for (app in OTHER_APPS) {
        LinkRow(title = app.name, subtitle = app.tagline, url = app.url, context = context)
    }
    Text(
        stringResource(R.string.settings_other_apps_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
}

private const val APP_REPO_URL = "https://github.com/norsehorse-dev/PassPonyAndroid"
private const val CORE_REPO_URL = "https://github.com/norsehorse-dev/PassPonyCore"
private const val NORSEHORSE_SITE_URL = "https://pony.norsehor.se"

@Composable
private fun DiagnosticsSection(storePath: String, format: StoreFormat, entryCount: Int, syncStatus: SyncStatus?) {
    SectionHeader(stringResource(R.string.settings_diagnostics_header))
    LabeledRow(stringResource(R.string.settings_diagnostics_core), coreVersion())
    LabeledRow(stringResource(R.string.settings_diagnostics_entries), entryCount.toString())
    LabeledRow(
        stringResource(R.string.settings_diagnostics_format),
        stringResource(if (format == StoreFormat.PASSAGE) R.string.settings_format_passage else R.string.settings_format_pass)
    )
    LabeledRow(stringResource(R.string.settings_diagnostics_store_path), storePath)
    LabeledRow(stringResource(R.string.settings_diagnostics_abi), Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
    val gitValue = if (syncStatus != null) {
        "↑${syncStatus.ahead} ↓${syncStatus.behind}" + if (syncStatus.dirty) " (dirty)" else ""
    } else {
        stringResource(R.string.settings_diagnostics_git_not_configured)
    }
    LabeledRow(stringResource(R.string.settings_diagnostics_git), gitValue)
}
