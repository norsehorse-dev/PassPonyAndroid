package com.passpony.android.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.autofill.AutofillManager
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.passpony.android.BuildConfig
import com.passpony.android.R
import com.passpony.android.crypto.AgeIdentityStore
import com.passpony.android.crypto.PgpKeyStore
import com.passpony.android.crypto.SshKeyStore
import com.passpony.android.store.StorePaths
import com.passpony.android.ui.AppViewModel
import uniffi.pass_ffi.RemoteScheme
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.coreVersion
import uniffi.pass_ffi.remoteScheme
import uniffi.pass_ffi.sshKnownHostsName

/**
 * Settings, then Diagnostics: the screen the support page describes,
 * with the rows the inline section used to show plus app, Android,
 * device, autofill, language, and key state, and two ways to get it into
 * a support message. Everything shown is assembled by [DiagnosticsReport],
 * whose redaction is unit-tested; this screen only collects the inputs.
 * Nothing here decrypts, loads a key, or touches the network.
 *
 * Copy uses the plain system clipboard rather than the 45-second
 * ephemeral one: the report holds no secret, and it is meant to be
 * pasted somewhere a minute later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(appViewModel: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val entries by appViewModel.entries.collectAsState()
    val syncStatus by appViewModel.syncStatus.collectAsState()
    val format = appViewModel.format
    var copied by remember { mutableStateOf(false) }

    val report = remember(entries.size, syncStatus, format) {
        DiagnosticsReport(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            flavor = BuildConfig.FLAVOR,
            coreVersion = coreVersion(),
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            format = if (format == StoreFormat.PASSAGE) "passage" else "pass",
            storePath = StorePaths.storeRoot(context, format).absolutePath,
            entryCount = entries.size,
            git = syncStatus?.let { status ->
                DiagnosticsReport.GitInfo(
                    ahead = status.ahead.toInt(),
                    behind = status.behind.toInt(),
                    dirty = status.dirty,
                    remoteHost = if (status.hasRemote) DiagnosticsReport.remoteHost(appViewModel.remoteUrl()) else null,
                    scheme = appViewModel.remoteUrl()?.takeIf { status.hasRemote }?.let { schemeName(remoteScheme(it)) },
                    hostPinned = appViewModel.remoteUrl()?.takeIf { status.hasRemote }
                        ?.let { sshKnownHostsName(it) }
                        ?.let { SshKeyStore.isPinned(context, it) },
                )
            },
            autofillEnabled = isAutofillEnabled(context),
            languageTag = LanguageManager.currentTag(),
            sshKey = SshKeyStore.load(context)?.algorithm,
            keys = if (format == StoreFormat.PASSAGE) {
                DiagnosticsReport.KeyInfo.Passage(AgeIdentityStore.identitiesFile(context).exists())
            } else {
                DiagnosticsReport.KeyInfo.Pass(PgpKeyStore.availableKeys(context).size)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
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
            DiagnosticRow(stringResource(R.string.diagnostics_app), "${report.appVersion} (${report.versionCode}, ${report.flavor})")
            DiagnosticRow(stringResource(R.string.settings_diagnostics_core), report.coreVersion)
            DiagnosticRow(stringResource(R.string.diagnostics_android), "${report.androidRelease} (API ${report.sdkInt})")
            DiagnosticRow(stringResource(R.string.diagnostics_device), report.device)
            DiagnosticRow(stringResource(R.string.settings_diagnostics_abi), report.abi)
            DiagnosticRow(
                stringResource(R.string.settings_diagnostics_format),
                stringResource(if (format == StoreFormat.PASSAGE) R.string.settings_format_passage else R.string.settings_format_pass)
            )
            DiagnosticRow(stringResource(R.string.settings_diagnostics_store_path), report.storePath)
            DiagnosticRow(stringResource(R.string.settings_diagnostics_entries), report.entryCount.toString())
            DiagnosticRow(
                stringResource(R.string.settings_diagnostics_git),
                report.git?.let { git ->
                    "↑${git.ahead} ↓${git.behind}" +
                        (if (git.dirty) " (dirty)" else "") +
                        (git.scheme?.let { " $it" } ?: "") +
                        (git.remoteHost?.let { " $it" } ?: "") +
                        (git.hostPinned?.let { if (it) " (host pinned)" else " (host not pinned)" } ?: "")
                } ?: stringResource(R.string.settings_diagnostics_git_not_configured)
            )
            DiagnosticRow(
                stringResource(R.string.diagnostics_autofill),
                stringResource(if (report.autofillEnabled) R.string.diagnostics_on else R.string.diagnostics_off)
            )
            DiagnosticRow(
                stringResource(R.string.diagnostics_language),
                report.languageTag.ifEmpty { stringResource(R.string.diagnostics_system) }
            )
            DiagnosticRow(
                stringResource(R.string.diagnostics_keys),
                when (val keys = report.keys) {
                    is DiagnosticsReport.KeyInfo.Pass -> stringResource(R.string.diagnostics_keys_pass, keys.keyFiles)
                    is DiagnosticsReport.KeyInfo.Passage -> stringResource(
                        if (keys.identitiesPresent) R.string.diagnostics_keys_passage_present else R.string.diagnostics_keys_passage_missing
                    )
                }
            )
            DiagnosticRow(
                stringResource(R.string.ssh_title),
                report.sshKey ?: stringResource(R.string.sync_remote_none)
            )

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.diagnostics_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(onClick = {
                    copyPlain(context, report.render())
                    copied = true
                }) { Text(stringResource(R.string.diagnostics_copy)) }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { share(context, report.render()) }) {
                    Text(stringResource(R.string.diagnostics_share))
                }
            }
            if (copied) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.diagnostics_copied), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.padding(end = 16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.End
        )
    }
}

private fun isAutofillEnabled(context: Context): Boolean =
    runCatching { context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true }
        .getOrDefault(false)

private fun copyPlain(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("PassPony diagnostics", text))
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "PassPony diagnostics")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

private fun schemeName(scheme: RemoteScheme): String = when (scheme) {
    RemoteScheme.HTTPS -> "https"
    RemoteScheme.HTTP -> "http"
    RemoteScheme.SSH -> "ssh"
    RemoteScheme.FILE -> "file"
    RemoteScheme.OTHER -> "other"
}
