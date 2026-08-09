package com.passpony.android.autofill

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.fragment.app.FragmentActivity

/**
 * Translucent activity Android starts for a result when the user picks
 * an authenticated dataset built by PassPonyAutofillService -- the
 * PendingIntent behind Dataset.Builder.setAuthentication(). Fully
 * transparent theme (see AndroidManifest), so a fresh grace window
 * reads as an instant fill with no visible flash.
 *
 * Port of iOS's prepareInterfaceToProvideCredential, via
 * AutofillCredential.resolveGated: skip straight to decrypt when
 * UnlockGate.isFresh(), else run the system auth sheet first. Never
 * decrypts before that gate passes, and never surfaces plaintext or a
 * raw exception in any failure path -- a cancel is the only signal the
 * caller (Chrome, the target app) ever sees.
 */
class AutofillAuthActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entryName = intent.getStringExtra(EXTRA_ENTRY_NAME)
        val passwordId: AutofillId? = intent.getParcelableExtraCompat(EXTRA_PASSWORD_ID)
        val usernameId: AutofillId? = intent.getParcelableExtraCompat(EXTRA_USERNAME_ID)

        // Not "passwordId == null": Chrome's AssistStructure is sometimes
        // scoped to just the focused field (e.g. a username-only structure
        // while the password field isn't focused yet), so a request with
        // only a username field is legitimate -- only bail if there is
        // nothing at all to fill.
        if (entryName.isNullOrEmpty() || (passwordId == null && usernameId == null)) {
            finishCanceled()
            return
        }

        AutofillCredential.resolveGated(
            activity = this,
            name = entryName,
            onSuccess = { credential ->
                val dataset = Dataset.Builder().apply {
                    usernameId?.let { setValue(it, AutofillValue.forText(credential.username)) }
                    passwordId?.let { setValue(it, AutofillValue.forText(credential.password)) }
                }.build()
                val result = Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
                setResult(Activity.RESULT_OK, result)
                finish()
            },
            onFailure = { finishCanceled() }
        )
    }

    private fun finishCanceled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_ENTRY_NAME = "com.passpony.android.autofill.EXTRA_ENTRY_NAME"
        const val EXTRA_USERNAME_ID = "com.passpony.android.autofill.EXTRA_USERNAME_ID"
        const val EXTRA_PASSWORD_ID = "com.passpony.android.autofill.EXTRA_PASSWORD_ID"
    }
}
