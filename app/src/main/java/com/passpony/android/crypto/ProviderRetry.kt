package com.passpony.android.crypto

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 3.0: shows a provider app's screen from one of the short-lived
 * activities (autofill, passkeys) and says whether it came back OK, so
 * the activity can run its store operation again. The main app does not
 * use this; MainActivity hosts provider screens itself.
 *
 * Make one as a property of the activity, so the result callback is
 * registered before the activity starts:
 *
 *     private val providerRetry = ProviderRetry(this)
 *
 * A first call can need several screens in a row (OpenKeychain asked for
 * two in the rc7 spike), so up to [MAX_ROUNDS] are shown per activity.
 */
class ProviderRetry(activity: ComponentActivity) {
    private var rounds = 0
    private var then: ((Boolean) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val ok = result.resultCode == Activity.RESULT_OK
        ProviderPrompt.finished(ok, result.data)
        val callback = then
        then = null
        callback?.invoke(ok)
    }

    /**
     * Show [prompt], then call [onResult] with true when the person went
     * through with it. False at once when the rounds are used up or the
     * screen cannot be started.
     */
    fun show(prompt: PendingIntent, onResult: (Boolean) -> Unit) {
        if (rounds >= MAX_ROUNDS) {
            onResult(false)
            return
        }
        rounds++
        then = onResult
        try {
            launcher.launch(IntentSenderRequest.Builder(prompt.intentSender).build())
        } catch (e: Exception) {
            then = null
            onResult(false)
        }
    }

    private companion object {
        const val MAX_ROUNDS = 4
    }
}
