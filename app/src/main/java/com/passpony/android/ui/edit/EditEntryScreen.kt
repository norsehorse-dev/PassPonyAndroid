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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.passpony.android.ui.components.entryOtpSummaryLine
import com.passpony.android.ui.components.otpSummaryLine
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.detail.Utf8Text
import uniffi.pass_ffi.entryFields
import uniffi.pass_ffi.entryIsOtpOnly
import uniffi.pass_ffi.entryPassword
import uniffi.pass_ffi.entryRemoveOtpauth
import uniffi.pass_ffi.entrySetField
import uniffi.pass_ffi.entrySetOtpauth
import uniffi.pass_ffi.entrySetPassword
import uniffi.pass_ffi.entryOtpKind

/**
 * Port of PassPony iOS's EditEntryView: field-level editing over the
 * byte-faithful codec helpers only (entrySetField/entrySetPassword, and
 * since 1.1 entrySetOtpauth/entryRemoveOtpauth) -- unknown lines,
 * ordering, spacing, and trailing-newline style survive untouched
 * (verified against a real fixture by PgpEditByteFidelityTest).
 * Removing a field is deliberately absent: the codec has no
 * delete-with-fidelity primitive for fields, only for the one
 * `otpauth://` line.
 *
 * Re-decrypts via [AppViewModel.readEntry] the same way
 * EntryDetailScreen does, rather than receiving plaintext through
 * navigation arguments -- plaintext never travels through the back
 * stack's saved state. The one-time code the setup sheet produces is
 * held the same way: in this screen's Compose state, until Save.
 *
 * [openOtpSetup] opens the setup sheet on arrival (the detail screen's
 * "Set up one-time code" shortcut); it is a flag in the route, not a
 * secret. [onDeleted] is for the OTP-only case: the codec refuses to
 * remove a URI that is the entry's first line, so the screen offers to
 * delete the entry instead and then pops past the stale detail screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntryScreen(
    name: String,
    viewModel: AppViewModel,
    onDone: () -> Unit,
    openOtpSetup: Boolean = false,
    onDeleted: () -> Unit = onDone,
) {
    val context = LocalContext.current
    var original by remember(name) { mutableStateOf<ByteArray?>(null) }
    var originalPassword by remember(name) { mutableStateOf("") }
    var originalValues by remember(name) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var fieldKeys by remember(name) { mutableStateOf<List<String>>(emptyList()) }
    // Whether the entry already carries an otpauth line (TOTP or HOTP).
    var hasOriginalOtp by remember(name) { mutableStateOf(false) }
    var otpOnly by remember(name) { mutableStateOf(false) }

    var password by remember(name) { mutableStateOf("") }
    var values by remember(name) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var newKey by remember(name) { mutableStateOf("") }
    var newValue by remember(name) { mutableStateOf("") }
    var otpauthEdit by remember(name) { mutableStateOf<OtpauthEdit>(OtpauthEdit.Unchanged) }
    var showOtpSheet by remember(name) { mutableStateOf(openOtpSetup) }
    var showGeneratorSheet by remember(name) { mutableStateOf(false) }
    var confirmDelete by remember(name) { mutableStateOf(false) }
    // Why the last Save failed; the screen stays open to show it.
    var saveError by remember(name) { mutableStateOf<String?>(null) }

    LaunchedEffect(name) {
        val content = viewModel.readEntry(name) ?: return@LaunchedEffect
        original = content
        // A password that isn't valid UTF-8 falls back to "" on both
        // sides of the diff (original and current start identical), so
        // a binary password never looks "changed" just from loading it
        // -- same limitation iOS's `String(data:encoding:.utf8) ?? ""`
        // has; editing a binary password through this text form isn't
        // supported by either app.
        val decodedPassword = Utf8Text.decodeStrict(entryPassword(content)) ?: ""
        val fields = entryFields(content)
        val pairs = LinkedHashMap<String, String>()
        val keys = mutableListOf<String>()
        for (field in fields) {
            // Passkey lines (2.0) carry a private key and are managed from
            // the entry view; the form leaves them untouched.
            if (field.key == "passkey") continue
            // First occurrence wins, matching the codec's set-field semantics.
            if (!pairs.containsKey(field.key)) {
                pairs[field.key] = field.value
                keys += field.key
            }
        }
        originalPassword = decodedPassword
        originalValues = pairs
        fieldKeys = keys
        password = decodedPassword
        values = pairs
        hasOriginalOtp = entryOtpKind(content) != null
        otpOnly = entryIsOtpOnly(content)
    }
    DisposableEffect(name) {
        onDispose { original = null }
    }

    val state = EntryEditState(
        originalPassword = originalPassword,
        originalValues = originalValues,
        fieldKeys = fieldKeys,
        password = password,
        values = values,
        newKey = newKey,
        newValue = newValue,
        hasOriginalOtpauth = hasOriginalOtp,
        otpauthEdit = otpauthEdit,
    )

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
                otpauthEdit = OtpauthEdit.Set(uri)
                showOtpSheet = false
            }
        )
    }
    if (confirmDelete) {
        DeleteEntryDialog(
            name = name,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteEntry(name)
                onDeleted()
            },
            onDismiss = { confirmDelete = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_entry_title, name.substringAfterLast('/'))) },
                navigationIcon = {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val content = original ?: return@TextButton
                            var bytes = content
                            for (step in state.editSteps()) {
                                bytes = when (step) {
                                    is EditStep.SetPassword ->
                                        entrySetPassword(bytes, step.password.toByteArray(Charsets.UTF_8))
                                    is EditStep.SetField ->
                                        entrySetField(bytes, step.key, step.value)
                                    is EditStep.SetOtpauth ->
                                        entrySetOtpauth(bytes, step.uri)
                                    EditStep.RemoveOtpauth ->
                                        entryRemoveOtpauth(bytes)
                                }
                            }
                            if (viewModel.saveEntry(name, bytes, isNew = false)) {
                                onDone()
                            } else {
                                saveError = viewModel.lastError.value
                            }
                        },
                        enabled = state.hasChanges
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
            if (otpOnly) {
                // Line 1 is the URI, so there is no password to edit; the
                // code section below owns that line.
                Text(
                    stringResource(R.string.otp_only_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
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
            }
            if (fieldKeys.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SectionHeader(stringResource(R.string.entry_detail_fields))
                // Field key labels are literal on-disk field names (from
                // entryFields(), whatever this entry's own bytes contain)
                // -- not localized, same reasoning as AddEntryScreen's
                // username/url labels.
                for (key in fieldKeys) {
                    OutlinedTextField(
                        value = values[key] ?: "",
                        onValueChange = { values = values + (key to it) },
                        label = { Text(key) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            OtpauthSection(
                original = original?.takeIf { hasOriginalOtp }?.let { entryOtpSummaryLine(it) ?: "" },
                otpOnly = otpOnly,
                edit = otpauthEdit,
                onAddOrReplace = { showOtpSheet = true },
                onRemove = { otpauthEdit = OtpauthEdit.Remove },
                onKeepCurrent = { otpauthEdit = OtpauthEdit.Unchanged },
                onDeleteEntry = { confirmDelete = true },
            )

            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.edit_entry_add_field))
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                label = { Text(stringResource(R.string.edit_entry_new_key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                label = { Text(stringResource(R.string.edit_entry_new_value)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
    }
}

/**
 * The one-time code section's five states: no code, a pending new code,
 * an existing code, a pending replacement, and a pending removal. Each
 * shows the summary that applies and the actions that make sense from
 * there; nothing is written until Save.
 */
@Composable
private fun OtpauthSection(
    original: String?,
    otpOnly: Boolean,
    edit: OtpauthEdit,
    onAddOrReplace: () -> Unit,
    onRemove: () -> Unit,
    onKeepCurrent: () -> Unit,
    onDeleteEntry: () -> Unit,
) {
    SectionHeader(stringResource(R.string.entry_detail_totp))
    Spacer(Modifier.height(4.dp))
    when (edit) {
        is OtpauthEdit.Set -> {
            otpSummaryLine(edit.uri)?.let { Text(it) }
            Text(
                stringResource(if (original == null) R.string.otp_added_on_save else R.string.otp_replaces_on_save),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                TextButton(onClick = onAddOrReplace) { Text(stringResource(R.string.otp_change)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onKeepCurrent) {
                    Text(stringResource(if (original == null) R.string.otp_remove else R.string.otp_keep_current))
                }
            }
        }
        OtpauthEdit.Remove -> {
            Text(
                stringResource(R.string.otp_removed_on_save),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onKeepCurrent) { Text(stringResource(R.string.otp_undo_remove)) }
        }
        OtpauthEdit.Unchanged -> {
            if (original == null) {
                Text(
                    stringResource(R.string.otp_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onAddOrReplace) { Text(stringResource(R.string.otp_add)) }
            } else {
                if (original.isNotEmpty()) Text(original)
                Row {
                    TextButton(onClick = onAddOrReplace) { Text(stringResource(R.string.otp_replace)) }
                    Spacer(Modifier.width(8.dp))
                    if (otpOnly) {
                        TextButton(onClick = onDeleteEntry) { Text(stringResource(R.string.store_list_delete_entry)) }
                    } else {
                        TextButton(onClick = onRemove) { Text(stringResource(R.string.otp_remove)) }
                    }
                }
                if (otpOnly) {
                    Text(
                        stringResource(R.string.otp_only_delete_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
