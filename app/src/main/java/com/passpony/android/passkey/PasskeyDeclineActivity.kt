package com.passpony.android.passkey

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import com.passpony.android.R
import com.passpony.android.ui.theme.PassPonyTheme

/**
 * What an app that is not a browser gets when it asks PassPony for a
 * passkey (2.0 rc3): a plain explanation, then a cancel back to the
 * caller. No crash, no silent failure.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyDeclineActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PassPonyTheme {
                AlertDialog(
                    onDismissRequest = { done() },
                    title = { Text(stringResource(R.string.app_name)) },
                    text = { Text(stringResource(R.string.passkey_browser_only_detail)) },
                    confirmButton = { TextButton(onClick = { done() }) { Text(stringResource(R.string.sync_done)) } }
                )
            }
        }
    }

    private fun done() {
        val result = Intent()
        if (PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) != null) {
            PendingIntentHandler.setCreateCredentialException(result, CreateCredentialCancellationException())
        } else {
            PendingIntentHandler.setGetCredentialException(result, GetCredentialCancellationException())
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
