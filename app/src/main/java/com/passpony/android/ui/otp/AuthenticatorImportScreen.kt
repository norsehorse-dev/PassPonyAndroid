package com.passpony.android.ui.otp

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.passpony.android.R
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.scan.QrScannerDialog
import com.passpony.android.ui.scan.ScanFeedback
import com.passpony.android.ui.util.SecureScreenEffect
import kotlinx.coroutines.launch
import uniffi.pass_ffi.MigrationAccount
import uniffi.pass_ffi.MigrationSkipReason
import uniffi.pass_ffi.OtpKind
import uniffi.pass_ffi.migrationParse

/**
 * Import the accounts of a Google Authenticator export ("Transfer
 * accounts" in that app). Scan every QR code of the export (or paste an
 * `otpauth-migration://` link), pick the accounts, name the entries, and
 * each becomes an OTP-only entry like `pass otp insert` writes, one
 * commit per entry.
 *
 * Every account carries its secret, so all of this lives in plain
 * `remember` state (never saved state), the screen holds FLAG_SECURE, and
 * the state goes when the screen does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatorImportScreen(appViewModel: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SecureScreenEffect()

    val collector = remember { MigrationCollector() }
    var scannedCount by remember { mutableIntStateOf(0) }
    var totalCount by remember { mutableIntStateOf(0) }
    val accounts = remember { mutableStateListOf<MigrationAccount>() }
    val names = remember { mutableStateListOf<String>() }
    val selected = remember { mutableStateListOf<Boolean>() }
    var skippedLines by remember { mutableStateOf(emptyList<String>()) }
    var pasted by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refreshFromCollector() {
        scannedCount = collector.scanned
        totalCount = collector.total
        val all = collector.accounts()
        // Keep names and checkboxes the user already edited for accounts
        // that were there before this code was added.
        val previous = accounts.zip(names.zip(selected)).associate { (a, ns) -> a.uri to ns }
        accounts.clear(); names.clear(); selected.clear()
        for (a in all) {
            val kept = previous[a.uri]
            accounts += a
            names += kept?.first ?: a.suggestedName
            selected += kept?.second ?: true
        }
        skippedLines = collector.skipped().map { s ->
            context.getString(
                R.string.otp_import_skipped_line,
                s.label,
                when (s.reason) {
                    MigrationSkipReason.MD5 -> context.getString(R.string.otp_import_skip_md5)
                    MigrationSkipReason.EMPTY_SECRET -> context.getString(R.string.otp_import_skip_empty)
                    else -> context.getString(R.string.otp_import_skip_unsupported)
                }
            )
        }
    }

    /** Parse one code; returns scanner feedback, null when the export is complete. */
    fun accept(text: String): ScanFeedback? {
        val batch = try {
            migrationParse(text.trim())
        } catch (e: Exception) {
            return ScanFeedback(e.ponyMessage(context))
        }
        val outcome = collector.add(batch)
        refreshFromCollector()
        return when (outcome) {
            is MigrationCollector.Outcome.OtherExport ->
                ScanFeedback(context.getString(R.string.otp_import_other_export))
            is MigrationCollector.Outcome.Duplicate ->
                ScanFeedback(context.getString(R.string.otp_import_already_scanned, outcome.scanned, outcome.total), isError = false)
            is MigrationCollector.Outcome.Added ->
                if (collector.complete) null
                else ScanFeedback(context.getString(R.string.otp_import_progress, outcome.scanned, outcome.total), isError = false)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) showScanner = true else cameraDenied = true }

    if (showScanner) {
        QrScannerDialog(
            onDecoded = { text ->
                val feedback = accept(text)
                if (feedback == null) showScanner = false
                feedback
            },
            onClose = { showScanner = false }
        )
    }

    val problems = ImportNames.problems(names.toList()) { appViewModel.hasEntry(it.trim().trim('/')) }
    val chosen = accounts.indices.filter { selected.getOrElse(it) { false } }
    val blocked = chosen.any { problems[it] != null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.otp_import_title)) },
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
            Text(stringResource(R.string.otp_import_intro), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) showScanner = true else permissionLauncher.launch(Manifest.permission.CAMERA)
            }) { Text(stringResource(R.string.otp_import_scan)) }
            if (cameraDenied) {
                Text(
                    stringResource(R.string.otp_scan_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = { Text(stringResource(R.string.otp_import_paste_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            TextButton(
                onClick = {
                    message = accept(pasted)?.message
                    pasted = ""
                },
                enabled = pasted.isNotBlank()
            ) { Text(stringResource(R.string.otp_import_paste_add)) }

            if (totalCount > 0) {
                Text(
                    stringResource(R.string.otp_import_progress, scannedCount, totalCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            if (accounts.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.otp_import_accounts_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                accounts.forEachIndexed { i, account ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selected[i], onCheckedChange = { selected[i] = it })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                listOfNotNull(account.issuer, account.account.ifBlank { null }).joinToString(" · ") +
                                    if (account.kind == OtpKind.HOTP) " · " + stringResource(R.string.otp_import_kind_hotp) else "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedTextField(
                                value = names[i],
                                onValueChange = { names[i] = it },
                                label = { Text(stringResource(R.string.otp_import_entry_name)) },
                                singleLine = true,
                                enabled = selected[i],
                                isError = selected[i] && problems[i] != null,
                                supportingText = if (selected[i] && problems[i] != null) {
                                    { Text(stringResource(problemText(problems[i]!!))) }
                                } else {
                                    null
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }

            if (skippedLines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.otp_import_skipped_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                for (line in skippedLines) {
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            if (accounts.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        collector.clear()
                        refreshFromCollector()
                        message = null
                    }, enabled = !busy) { Text(stringResource(R.string.otp_import_start_over)) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            busy = true
                            val toWrite = chosen.map { names[it].trim().trim('/') to accounts[it].uri }
                            scope.launch {
                                message = try {
                                    val written = appViewModel.addOtpEntries(toWrite)
                                    collector.clear()
                                    refreshFromCollector()
                                    context.resources.getQuantityString(
                                        R.plurals.otp_import_done, written.size, written.size
                                    )
                                } catch (e: Exception) {
                                    e.ponyMessage(context)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && chosen.isNotEmpty() && !blocked
                    ) {
                        Text(pluralStringResource(R.plurals.otp_import_button, chosen.size, chosen.size))
                    }
                }
            }
        }
    }
}

private fun problemText(problem: ImportNames.Problem): Int = when (problem) {
    ImportNames.Problem.BLANK -> R.string.otp_import_name_blank
    ImportNames.Problem.DUPLICATE -> R.string.otp_import_name_duplicate
    ImportNames.Problem.EXISTS -> R.string.otp_import_name_exists
}
