package com.passpony.android.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.passpony.android.R
import com.passpony.android.ui.AppViewModel

/**
 * Port of PassPony iOS's MoveEntryView: a single prefilled name field,
 * `pass mv` semantics via PassStore.moveEntry (a rename into another
 * folder is just a move to a different path). On success the caller
 * pops both this screen and the now-stale detail screen underneath it,
 * matching iOS's dismiss() + onMoved { dismiss() }.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveEntryScreen(currentName: String, viewModel: AppViewModel, onMoved: () -> Unit, onCancel: () -> Unit) {
    var newName by remember(currentName) { mutableStateOf(currentName) }
    var error by remember(currentName) { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.move_entry_title)) },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (viewModel.moveEntry(currentName, newName)) {
                                onMoved()
                            } else {
                                error = viewModel.lastError.value
                            }
                        },
                        enabled = newName.isNotEmpty() && newName != currentName
                    ) { Text(stringResource(R.string.move_entry_move)) }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text(stringResource(R.string.move_entry_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
