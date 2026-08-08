package com.passpony.android.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.passpony.android.R
import com.passpony.android.store.UnlockGate
import com.passpony.android.ui.util.SecureScreenEffect

/**
 * Full-screen unlock gate, shown by MainActivity whenever
 * UnlockGate.isFresh() is false (fresh launch, or a lapsed grace
 * window caught on ON_START). Port of iOS's implicit lock flow in
 * PassPonyApp.swift, visually modeled on PGPonyAndroid's LockScreen.kt.
 *
 * Auto-prompts the system auth sheet on every ON_RESUME rather than a
 * fixed post-appear delay: this screen can enter composition while the
 * activity is still backgrounded (MainActivity decides to lock before
 * the activity is back in the foreground), and BiometricPrompt throws
 * if invoked from a STOPPED activity -- a delay-based trigger would
 * wedge on "Authenticating..." with no way to recover. Tying it to
 * ON_RESUME also re-prompts every time the user returns from another
 * app without dismissing this screen. The Unlock button stays as a
 * manual fallback either way.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }

    SecureScreenEffect()

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isAuthenticating by remember { mutableStateOf(false) }
    val reason = stringResource(R.string.lock_reason)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activity, reason) {
        fun prompt() {
            isAuthenticating = false
            triggerAuthenticate(
                activity = activity,
                reason = reason,
                onStart = { isAuthenticating = true; errorMessage = null },
                onSuccess = { isAuthenticating = false; onUnlock() },
                onError = { message -> isAuthenticating = false; errorMessage = message }
            )
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) prompt()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // addObserver doesn't replay a past ON_RESUME, so if this screen
        // appears while already resumed (relocked in the foreground),
        // prompt right away instead of waiting for the next resume.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) prompt()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Scrollable so a large font scale never pushes the Unlock button
        // below the viewport -- the whole app is sealed behind this
        // screen, so that failure mode is worse here than almost anywhere
        // else (the PGPony 4.1.1 lesson this ports).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(32.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                stringResource(R.string.app_name),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.lock_screen_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = {
                    triggerAuthenticate(
                        activity = activity,
                        reason = reason,
                        onStart = { isAuthenticating = true; errorMessage = null },
                        onSuccess = { isAuthenticating = false; onUnlock() },
                        onError = { message -> isAuthenticating = false; errorMessage = message }
                    )
                },
                enabled = !isAuthenticating && activity != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (isAuthenticating) {
                        stringResource(R.string.lock_screen_authenticating)
                    } else {
                        stringResource(R.string.lock_screen_unlock_button)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            errorMessage?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Hands off to UnlockGate.authenticate. Lives outside the @Composable
 * body so the ON_RESUME auto-prompt and the manual Unlock tap share a
 * single call site. A null activity (the LocalContext unwrap failed --
 * should not happen in production) surfaces as an inline error rather
 * than crashing.
 */
private fun triggerAuthenticate(
    activity: FragmentActivity?,
    reason: String,
    onStart: () -> Unit,
    onSuccess: () -> Unit,
    onError: (String?) -> Unit,
) {
    if (activity == null) {
        onError("Activity context unavailable.")
        return
    }
    onStart()
    UnlockGate.authenticate(
        activity = activity,
        reason = reason,
        onSuccess = onSuccess,
        onError = { errorCode, message ->
            // A user dismissing the prompt (back gesture, its own Cancel/
            // "Use PIN" fallback control) shouldn't read as an alarming
            // error -- just fall back to the manual Unlock button in
            // silence, the same way tapping outside a normal dialog would.
            val isDismissal = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                errorCode == BiometricPrompt.ERROR_CANCELED
            onError(if (isDismissal) null else message)
        }
    )
}

/** Compose's LocalContext is often a ContextWrapper (theming, etc.), not
 * the Activity itself; unwrap to find the FragmentActivity BiometricPrompt
 * needs. */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
