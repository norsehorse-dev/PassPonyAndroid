package com.passpony.android.ui.edit

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.passpony.android.R

/**
 * Confirm-before-delete: the one deliberate departure from iOS, whose
 * List rows use SwiftUI's `.onDelete` swipe gesture with no separate
 * confirmation step. Android's row affordance here is a persistent
 * delete icon rather than a swipe reveal (plan section 4 permits either
 * "swipe or overflow action"), so a confirm step matters more -- a
 * swipe has some built-in intentionality a single tap doesn't.
 */
@Composable
fun DeleteEntryDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_entry_title)) },
        text = { Text(stringResource(R.string.delete_entry_message, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete_entry_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.delete_entry_cancel)) }
        }
    )
}
