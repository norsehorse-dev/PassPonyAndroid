package com.passpony.android.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.passpony.android.R
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.Routes
import com.passpony.android.ui.components.TotpRing
import com.passpony.android.ui.util.Clipboard
import com.passpony.android.ui.util.SecureScreenEffect
import uniffi.pass_ffi.entryFields
import uniffi.pass_ffi.entryPassword
import uniffi.pass_ffi.entryTotp

/**
 * Port of PassPony iOS's EntryDetailView: masked password with reveal
 * and copy, key/value fields, live TOTP ring. Decrypts once when the
 * screen is reached and holds plaintext only in local Compose state —
 * never in [AppViewModel], which outlives this screen — clearing the
 * reference when the screen leaves composition (structural parity with
 * iOS's `.onDisappear { content = nil }`; a config change also leaves
 * and re-enters composition, so it simply re-decrypts rather than
 * risking plaintext surviving in a Bundle).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(name: String, viewModel: AppViewModel, navController: NavHostController) {
    var content by remember(name) { mutableStateOf<ByteArray?>(null) }
    var loadAttempted by remember(name) { mutableStateOf(false) }
    var revealed by remember(name) { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    LaunchedEffect(name) {
        content = viewModel.readEntry(name)
        loadAttempted = true
    }
    DisposableEffect(name) {
        onDispose { content = null }
    }

    SecureScreenEffect()

    val lastError by viewModel.lastError.collectAsState()
    val current = content

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            enabled = current != null
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.entry_detail_more))
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.entry_detail_edit_fields)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    navController.navigate(Routes.editEntry(name))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.entry_detail_move_rename)) },
                                leadingIcon = { Icon(Icons.Filled.DriveFileMove, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    navController.navigate(Routes.moveEntry(name))
                                }
                            )
                        }
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
        ) {
            when {
                current != null -> {
                    PasswordSection(current, revealed, onRevealToggle = { revealed = !revealed })
                    FieldsSection(current)
                    TotpSection(current)
                }
                loadAttempted -> {
                    Text(
                        lastError ?: stringResource(R.string.error_unknown),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordSection(content: ByteArray, revealed: Boolean, onRevealToggle: () -> Unit) {
    val context = LocalContext.current
    val passwordBytes = remember(content) { entryPassword(content) }
    val decoded = remember(passwordBytes) { Utf8Text.decodeStrict(passwordBytes) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SectionHeader(stringResource(R.string.entry_detail_password))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    !revealed -> PasswordMask.mask(passwordBytes.size)
                    decoded != null -> decoded
                    else -> stringResource(R.string.entry_detail_binary_password)
                },
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRevealToggle) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (revealed) R.string.entry_detail_hide else R.string.entry_detail_reveal
                    )
                )
            }
            IconButton(onClick = {
                decoded?.let { Clipboard.copyEphemeral(context, it) }
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.entry_detail_copy))
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun FieldsSection(content: ByteArray) {
    val fields = remember(content) { entryFields(content) }
    if (fields.isEmpty()) return

    val context = LocalContext.current
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SectionHeader(stringResource(R.string.entry_detail_fields))
        for (field in fields) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    field.key,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(field.value, modifier = Modifier.weight(1f))
                IconButton(onClick = { Clipboard.copyEphemeral(context, field.value) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.entry_detail_copy))
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun TotpSection(content: ByteArray) {
    // entryTotp is cheap (no re-decryption) and only needs a presence
    // check here; TotpRing owns its own 1 s clock for the live value.
    val hasTotp = remember(content) { entryTotp(content, 0uL) != null }
    if (!hasTotp) return

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SectionHeader(stringResource(R.string.entry_detail_totp))
        TotpRing(content)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}
