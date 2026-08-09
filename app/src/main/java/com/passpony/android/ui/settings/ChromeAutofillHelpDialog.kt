package com.passpony.android.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.passpony.android.R

/**
 * P12 follow-up: Chrome on Android has its own "Autofill Services" setting
 * that overrides the system-level default autofill service picked in
 * Android Settings. Selecting PassPony there is necessary but not
 * sufficient -- Chrome keeps using its own built-in password UI until this
 * separate, non-obvious Chrome setting is also switched and Chrome is fully
 * restarted. This dialog walks the user through that second step; it can't
 * be automated or detected from PassPony's side, so a short explanation is
 * the best available fix.
 */
@Composable
fun ChromeAutofillHelpDialog(onOpenChrome: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chrome_autofill_help_title)) },
        text = { Text(stringResource(R.string.chrome_autofill_help_body)) },
        confirmButton = {
            TextButton(onClick = onOpenChrome) { Text(stringResource(R.string.chrome_autofill_help_open_chrome)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chrome_autofill_help_close)) }
        }
    )
}
