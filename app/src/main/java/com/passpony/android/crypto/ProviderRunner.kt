package com.passpony.android.crypto

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import uniffi.pass_ffi.CryptoException

/**
 * 3.0: for a screen that starts something through the provider itself
 * (choosing a key, the Test row): runs a block off the main thread, shows
 * each provider screen it asks for, and runs it again, up to four rounds.
 * Opening an entry does not need this; MainActivity covers that.
 */
class ProviderRunner internal constructor(private val show: suspend (PendingIntent) -> Boolean) {
    /**
     * The block's outcome once no provider screen is waiting. A screen
     * the person dismissed ends it with [CryptoException.Cancelled].
     */
    suspend fun <T> run(block: () -> T): Result<T> {
        repeat(MAX_ROUNDS) {
            val attempt = withContext(Dispatchers.IO) { ProviderPrompt.capturing(block) }
            val prompt = attempt.prompt ?: return attempt.result
            if (!show(prompt)) return Result.failure(CryptoException.Cancelled())
        }
        return Result.failure(CryptoException.Unavailable(reason = "Your key app kept asking. Try again."))
    }

    private companion object {
        const val MAX_ROUNDS = 4
    }
}

@Composable
fun rememberProviderRunner(): ProviderRunner {
    val waiting = remember { arrayOfNulls<CancellableContinuation<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val ok = result.resultCode == Activity.RESULT_OK
        ProviderPrompt.finished(ok, result.data)
        val continuation = waiting[0]
        waiting[0] = null
        continuation?.resume(ok)
    }
    return remember {
        ProviderRunner { prompt ->
            suspendCancellableCoroutine { continuation ->
                waiting[0] = continuation
                try {
                    launcher.launch(IntentSenderRequest.Builder(prompt.intentSender).build())
                } catch (e: Exception) {
                    waiting[0] = null
                    continuation.resume(false)
                }
            }
        }
    }
}
