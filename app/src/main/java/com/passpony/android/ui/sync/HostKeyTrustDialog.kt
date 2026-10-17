package com.passpony.android.ui.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.passpony.android.R
import uniffi.pass_ffi.HostKey

/**
 * Trust on first use for an SSH host (2.0 rc2): shown when a clone, sync
 * or push stops with `GitException.UnknownHostKey`. The fingerprint is
 * the `SHA256:` form git hosts publish, so the user can compare it before
 * saying yes. Trusting pins the key and the caller retries; a different
 * key for the same host later is refused outright, with no dialog.
 */
@Composable
fun HostKeyTrustDialog(
    host: String,
    key: HostKey,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.ssh_host_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ssh_host_body, host))
                SelectionContainer {
                    Text(
                        key.fingerprint,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                Text(
                    key.keyType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    stringResource(R.string.ssh_host_check),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) { Text(stringResource(R.string.ssh_host_trust)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
