package com.passpony.android.ui.edit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.passpony.android.R
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.components.TotpRing
import com.passpony.android.ui.scan.QrScannerDialog
import com.passpony.android.ui.util.SecureScreenEffect
import uniffi.pass_ffi.TotpAlgorithm
import uniffi.pass_ffi.TotpSummary
import uniffi.pass_ffi.totpBuildUri
import uniffi.pass_ffi.totpDescribe

private const val TAB_PASTE = 0
private const val TAB_KEY = 1
private const val TAB_SCAN = 2

/**
 * Three ways to set up a one-time code, all ending in one canonical
 * `otpauth://totp/` URI built or validated by the core: paste a URI,
 * type the secret key with its account and issuer, or scan the QR code
 * a site shows during two-factor setup (which lands in the Paste tab
 * with the URI filled in, so the summary and live preview are visible
 * before Use). Nothing here touches the entry; [onUse] hands the URI to
 * the calling screen, which records it as an EntryEditState step or a
 * NewEntryContent line.
 *
 * The URI and the typed secret are secrets: plain `remember` state,
 * never `rememberSaveable`, cleared with the sheet, and FLAG_SECURE
 * while it is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TotpSetupSheet(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    var tab by remember { mutableIntStateOf(TAB_PASTE) }
    var pasted by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var secretRevealed by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf("") }
    var issuer by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var algorithm by remember { mutableStateOf(TotpAlgorithm.SHA1) }
    var digits by remember { mutableIntStateOf(6) }
    var period by remember { mutableStateOf("30") }
    var showScanner by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }

    SecureScreenEffect()

    // The candidate URI for the current tab: success carries a URI the
    // core accepted, failure carries the TotpException to explain.
    val candidate: Result<String>? = remember(tab, pasted, secret, account, issuer, algorithm, digits, period) {
        when (tab) {
            TAB_PASTE -> pasted.trim().takeIf { it.isNotEmpty() }?.let { text ->
                runCatching { totpDescribe(text); text }
            }
            TAB_KEY -> if (secret.isBlank()) null else runCatching {
                totpBuildUri(
                    secret = secret,
                    account = account,
                    issuer = issuer.takeIf { it.isNotBlank() },
                    algorithm = algorithm,
                    digits = digits.toUInt(),
                    period = period.trim().toULongOrNull() ?: 0uL,
                )
            }
            else -> null
        }
    }
    val candidateUri = candidate?.getOrNull()
    val summary: TotpSummary? = remember(candidateUri) {
        candidateUri?.let { runCatching { totpDescribe(it) }.getOrNull() }
    }
    val problem = candidate?.exceptionOrNull()?.ponyMessage(context)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showScanner = true
        } else {
            cameraDenied = true
        }
    }
    val startScan: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            showScanner = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (showScanner) {
        QrScannerDialog(
            onDecoded = { text ->
                val failure = runCatching { totpDescribe(text) }.exceptionOrNull()
                if (failure == null) {
                    pasted = text
                    tab = TAB_PASTE
                    showScanner = false
                    null
                } else {
                    context.getString(R.string.otp_scan_rejected, failure.ponyMessage(context))
                }
            },
            onClose = { showScanner = false }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            Text(stringResource(R.string.otp_sheet_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == TAB_PASTE, onClick = { tab = TAB_PASTE },
                    text = { Text(stringResource(R.string.otp_tab_paste)) })
                Tab(selected = tab == TAB_KEY, onClick = { tab = TAB_KEY },
                    text = { Text(stringResource(R.string.otp_tab_key)) })
                if (hasCamera) {
                    Tab(selected = tab == TAB_SCAN, onClick = { tab = TAB_SCAN },
                        text = { Text(stringResource(R.string.otp_tab_scan)) })
                }
            }
            Spacer(Modifier.height(16.dp))

            when (tab) {
                TAB_PASTE -> {
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text(stringResource(R.string.otp_paste_hint)) },
                        minLines = 2,
                        isError = problem != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.otp_paste_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                TAB_KEY -> {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(stringResource(R.string.otp_key_secret)) },
                        singleLine = true,
                        isError = problem != null,
                        visualTransformation = if (secretRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { secretRevealed = !secretRevealed }) {
                                Icon(
                                    if (secretRevealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(
                                        if (secretRevealed) R.string.entry_detail_hide else R.string.entry_detail_reveal
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it },
                        label = { Text(stringResource(R.string.otp_key_account)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = issuer,
                        onValueChange = { issuer = it },
                        label = { Text(stringResource(R.string.otp_key_issuer)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    TextButton(onClick = { advanced = !advanced }) {
                        Text(stringResource(R.string.otp_key_advanced))
                    }
                    if (advanced) {
                        Text(stringResource(R.string.otp_key_algorithm), style = MaterialTheme.typography.labelMedium)
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val options = listOf(TotpAlgorithm.SHA1, TotpAlgorithm.SHA256, TotpAlgorithm.SHA512)
                            options.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = algorithm == option,
                                    onClick = { algorithm = option },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                                ) { Text(algorithmLabel(option)) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.otp_key_digits), style = MaterialTheme.typography.labelMedium)
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val options = listOf(6, 8)
                            options.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = digits == option,
                                    onClick = { digits = option },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                                ) { Text(option.toString()) }
                            }
                        }
                        OutlinedTextField(
                            value = period,
                            onValueChange = { period = it },
                            label = { Text(stringResource(R.string.otp_key_period)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }
                TAB_SCAN -> {
                    Text(
                        stringResource(R.string.otp_scan_help),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = startScan) {
                        Text(stringResource(R.string.otp_scan_open))
                    }
                    if (cameraDenied) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.otp_scan_denied),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                            )
                        }) { Text(stringResource(R.string.otp_scan_open_settings)) }
                    }
                }
            }

            if (problem != null) {
                Spacer(Modifier.height(8.dp))
                Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (summary != null && candidateUri != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.otp_preview),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(totpSummaryLine(summary), style = MaterialTheme.typography.bodyMedium)
                // A one-line entry whose first line is the URI is the shape
                // `pass otp insert` writes, and the ring reads it directly.
                TotpRing("$candidateUri\n".toByteArray(Charsets.UTF_8))
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { candidateUri?.let(onUse) },
                    enabled = candidateUri != null
                ) { Text(stringResource(R.string.otp_use)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** "Example:kevin · 6 digits · 30 s", the summary line every OTP surface shows. */
@Composable
fun totpSummaryLine(summary: TotpSummary): String =
    stringResource(
        R.string.otp_summary_format,
        summary.label,
        summary.digits.toInt(),
        summary.period.toLong()
    )

private fun algorithmLabel(algorithm: TotpAlgorithm): String = when (algorithm) {
    TotpAlgorithm.SHA1 -> "SHA-1"
    TotpAlgorithm.SHA256 -> "SHA-256"
    TotpAlgorithm.SHA512 -> "SHA-512"
}
