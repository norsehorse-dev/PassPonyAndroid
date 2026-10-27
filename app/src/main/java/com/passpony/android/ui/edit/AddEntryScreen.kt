package com.passpony.android.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.passpony.android.R
import com.passpony.android.ui.theme.SectionHeader
import com.passpony.android.ui.components.otpSummaryLine
import com.passpony.android.ui.AppViewModel

/**
 * Port of PassPony iOS's AddEntryView: name, password with a Generate
 * button, optional username/url fields, and (1.1) an optional one-time
 * code from the setup sheet. "username"/"url" are the literal on-disk
 * field keys the entry codec writes (see NewEntryContent) -- like iOS,
 * deliberately not localized, since a translated label would still
 * need to write the English key for pass CLI / other clients to read it
 * back correctly. A code with no password saves as an OTP-only entry,
 * the shape `pass otp insert` creates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEntryScreen(viewModel: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var otpauth by remember { mutableStateOf<String?>(null) }
    var showOtpSheet by remember { mutableStateOf(false) }
    var showGeneratorSheet by remember { mutableStateOf(false) }
    // Why the last Save failed (no .gpg-id yet, no usable key, ...).
    var saveError by remember { mutableStateOf<String?>(null) }

    if (showGeneratorSheet) {
        GeneratorSheet(
            onDismiss = { showGeneratorSheet = false },
            onUse = { generated ->
                password = generated
                showGeneratorSheet = false
            }
        )
    }
    if (showOtpSheet) {
        TotpSetupSheet(
            onDismiss = { showOtpSheet = false },
            onUse = { uri ->
                otpauth = uri
                showOtpSheet = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_entry_title)) },
                navigationIcon = {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val content = NewEntryContent.assemble(password, username, url, otpauth)
                            if (viewModel.saveEntry(name, content, isNew = true)) {
                                onDone()
                            } else {
                                saveError = viewModel.lastError.value
                            }
                        },
                        enabled = name.isNotEmpty() && (password.isNotEmpty() || otpauth != null)
                    ) { Text(stringResource(R.string.action_save)) }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            saveError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.add_entry_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.entry_detail_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { password = PasswordGenerator.generate(context) }) {
                    Text(stringResource(R.string.add_entry_generate))
                }
                IconButton(onClick = { showGeneratorSheet = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.generator_tune))
                }
            }
            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.add_entry_fields_optional))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.xc_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.xc_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.add_entry_otp_optional))
            val pendingUri = otpauth
            if (pendingUri == null) {
                TextButton(onClick = { showOtpSheet = true }) { Text(stringResource(R.string.otp_add)) }
            } else {
                otpSummaryLine(pendingUri)?.let { Text(it) }
                Row {
                    TextButton(onClick = { showOtpSheet = true }) { Text(stringResource(R.string.otp_change)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { otpauth = null }) { Text(stringResource(R.string.otp_remove)) }
                }
            }
        }
    }
}
