package com.passpony.android.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.passpony.android.R
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.detail.Utf8Text
import uniffi.pass_ffi.entryFields
import uniffi.pass_ffi.entryPassword
import uniffi.pass_ffi.entrySetField
import uniffi.pass_ffi.entrySetPassword

/**
 * Port of PassPony iOS's EditEntryView: field-level editing over the
 * byte-faithful codec helpers only (entrySetField/entrySetPassword) --
 * unknown lines, ordering, spacing, and trailing-newline style survive
 * untouched (verified against a real fixture by
 * PgpEditByteFidelityTest). Removing a field is deliberately absent:
 * the codec has no delete-with-fidelity primitive yet.
 *
 * Re-decrypts via [AppViewModel.readEntry] the same way
 * EntryDetailScreen does, rather than receiving plaintext through
 * navigation arguments -- plaintext never travels through the back
 * stack's saved state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntryScreen(name: String, viewModel: AppViewModel, onDone: () -> Unit) {
    var original by remember(name) { mutableStateOf<ByteArray?>(null) }
    var originalPassword by remember(name) { mutableStateOf("") }
    var originalValues by remember(name) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var fieldKeys by remember(name) { mutableStateOf<List<String>>(emptyList()) }

    var password by remember(name) { mutableStateOf("") }
    var values by remember(name) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var newKey by remember(name) { mutableStateOf("") }
    var newValue by remember(name) { mutableStateOf("") }

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
    )

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
                                }
                            }
                            viewModel.saveEntry(name, bytes, isNew = false)
                            onDone()
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.entry_detail_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { password = PasswordGenerator.generate() }) {
                    Text(stringResource(R.string.add_entry_generate))
                }
            }
            if (fieldKeys.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.entry_detail_fields),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
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
            Text(
                stringResource(R.string.edit_entry_add_field),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
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
