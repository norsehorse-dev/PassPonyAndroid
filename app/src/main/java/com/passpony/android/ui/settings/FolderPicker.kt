package com.passpony.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.passpony.android.R
import com.passpony.android.store.FolderProbe
import com.passpony.android.store.StoreMove
import com.passpony.android.ui.AppViewModel
import kotlinx.coroutines.launch
import uniffi.pass_ffi.StoreFormat

/** A folder was picked and something has to be asked before it is used. */
private sealed class FolderQuestion {
    /** No store PassPony recognizes, or signs of both formats. */
    data class WhichFormat(val uri: Uri) : FolderQuestion()

    /** The store in app storage has entries: move them in, or leave them. */
    data class MoveOrOpen(val uri: Uri, val format: StoreFormat, val entries: Int) : FolderQuestion()
}

/**
 * 3.0: pick a folder for the store. Returns the function that opens the
 * system picker; Settings and onboarding both use it.
 *
 * After a pick the folder is looked at ([FolderProbe]). A folder holding
 * a pass or passage store is opened as that; an empty one takes the
 * current format. If the store in app storage has entries, the person
 * chooses between moving them into the folder and opening the folder as
 * it is. [onChanged] is called whenever the open store may have changed,
 * with a message to show or null.
 */
@Composable
fun rememberFolderPicker(appViewModel: AppViewModel, onChanged: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf<FolderQuestion?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun open(uri: Uri, format: StoreFormat) {
        scope.launch {
            appViewModel.useFolder(uri, format)
            onChanged(null)
        }
    }

    fun proceed(uri: Uri, format: StoreFormat) {
        scope.launch {
            val entries = appViewModel.movableEntryCount(format)
            if (entries == 0) {
                appViewModel.useFolder(uri, format)
                onChanged(null)
            } else {
                question = FolderQuestion.MoveOrOpen(uri, format, entries)
            }
        }
    }

    fun move(uri: Uri, format: StoreFormat) {
        busy = true
        scope.launch {
            val outcome = appViewModel.moveIntoFolder(uri, format)
            busy = false
            if (outcome !is StoreMove.Outcome.Done) appViewModel.forgetFolder(uri)
            onChanged(
                when (outcome) {
                    is StoreMove.Outcome.Done -> context.getString(R.string.storage_moved_in)
                    is StoreMove.Outcome.Clash -> context.getString(
                        R.string.storage_move_clash, outcome.names.take(5).joinToString(", ")
                    )
                    is StoreMove.Outcome.Failed -> context.getString(R.string.storage_move_failed, outcome.reason)
                }
            )
        }
    }

    fun cancel(uri: Uri) {
        question = null
        appViewModel.forgetFolder(uri)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            // Persisted, so the folder is still reachable after a restart.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            onChanged(context.getString(R.string.storage_folder_refused))
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val kind = try {
                appViewModel.probeFolder(uri)
            } catch (e: Exception) {
                appViewModel.forgetFolder(uri)
                onChanged(context.getString(R.string.storage_folder_refused))
                return@launch
            }
            when (kind) {
                FolderProbe.Kind.PASS -> proceed(uri, StoreFormat.PASS)
                FolderProbe.Kind.PASSAGE -> proceed(uri, StoreFormat.PASSAGE)
                FolderProbe.Kind.EMPTY -> proceed(uri, appViewModel.format)
                FolderProbe.Kind.BOTH, FolderProbe.Kind.UNKNOWN -> question = FolderQuestion.WhichFormat(uri)
            }
        }
    }

    when (val asked = question) {
        null -> {}
        is FolderQuestion.WhichFormat -> AlertDialog(
            onDismissRequest = { cancel(asked.uri) },
            title = { Text(stringResource(R.string.storage_which_format_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.storage_which_format_body))
                    TextButton(onClick = {
                        question = null
                        proceed(asked.uri, StoreFormat.PASSAGE)
                    }) { Text(stringResource(R.string.settings_format_passage)) }
                    TextButton(onClick = {
                        question = null
                        proceed(asked.uri, StoreFormat.PASS)
                    }) { Text(stringResource(R.string.settings_format_pass)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { cancel(asked.uri) }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        is FolderQuestion.MoveOrOpen -> AlertDialog(
            onDismissRequest = { cancel(asked.uri) },
            title = { Text(stringResource(R.string.storage_move_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.storage_move_body, asked.entries))
                    TextButton(onClick = {
                        question = null
                        move(asked.uri, asked.format)
                    }) { Text(stringResource(R.string.storage_move_in)) }
                    TextButton(onClick = {
                        question = null
                        open(asked.uri, asked.format)
                    }) { Text(stringResource(R.string.storage_open_without_moving)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { cancel(asked.uri) }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.storage_moving)) },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
    }

    return { launcher.launch(null) }
}
