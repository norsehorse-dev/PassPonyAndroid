package com.passpony.android.store

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.fragment.app.FragmentActivity
import com.passpony.android.crypto.PassphraseCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.unlockGateDataStore by preferencesDataStore(name = "passpony_unlock_gate_prefs")

/**
 * App-open unlock policy, port of iOS's UnlockGate.swift: a 5-minute
 * grace window between required device auths (biometric or device
 * credential), backed here by a DataStore timestamp instead of iOS's
 * app-group UserDefaults double. GRACE_PERIOD_MILLIS is the single
 * source of truth also read by PassphraseCache and Settings' locked-
 * keys footer -- matches iOS, where PassphraseCache.swift reads
 * UnlockGate.gracePeriod directly rather than keeping its own copy.
 *
 * This gates *access* to the app content; it does not itself encrypt
 * anything.
 */
object UnlockGate {
    const val GRACE_PERIOD_MILLIS: Long = 5 * 60 * 1000

    private val LAST_UNLOCK_KEY = longPreferencesKey("last_unlock")
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** True while a prior auth is still within the grace window. */
    fun isFresh(context: Context): Boolean {
        val last = runBlocking {
            context.unlockGateDataStore.data.map { it[LAST_UNLOCK_KEY] }.first()
        }
        return UnlockGateLogic.isFresh(last, System.currentTimeMillis(), GRACE_PERIOD_MILLIS)
    }

    fun markUnlocked(context: Context) {
        runBlocking {
            context.unlockGateDataStore.edit { it[LAST_UNLOCK_KEY] = System.currentTimeMillis() }
        }
    }

    /**
     * The panic-lock action and background-expiry both land here --
     * also clears the cached pass (OpenPGP) key passphrase, matching
     * iOS: a relock means every cached secret is gone, not just the
     * store gate itself.
     */
    fun lock(context: Context) {
        runBlocking {
            context.unlockGateDataStore.edit { it.remove(LAST_UNLOCK_KEY) }
        }
        PassphraseCache.clear(context)
    }

    /**
     * Whether the device can present *any* device-owner auth right
     * now (biometric or credential), using the same authenticator set
     * [authenticate] requests. False means no screen lock is
     * configured on the device at all -- [authenticate] treats that as
     * auth-passed rather than stranding the user (iOS's fresh-
     * simulator rule: real devices always have at least a passcode
     * path; this is a development convenience only).
     */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Present the system auth sheet. Must be called from a
     * FragmentActivity (MainActivity, bumped from ComponentActivity
     * this packet since BiometricPrompt needs a FragmentManager host).
     * On a device with no biometric/credential configured at all,
     * marks unlocked and calls back success immediately without ever
     * invoking BiometricPrompt.
     */
    fun authenticate(
        activity: FragmentActivity,
        reason: String,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, message: String) -> Unit,
    ) {
        if (!canAuthenticate(activity)) {
            markUnlocked(activity)
            onSuccess()
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    markUnlocked(activity)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errorCode, errString.toString())
                }

                // onAuthenticationFailed left at default no-op: BiometricPrompt
                // already renders its own "not recognized" state and lets the
                // user retry without us intervening.
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(reason)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            // BiometricPrompt can throw (e.g. committing its fragment at a
            // bad lifecycle moment) if invoked at the wrong time. Surface
            // it as a normal error rather than crashing -- the caller's
            // retry path (LockScreen's Unlock button / next ON_RESUME)
            // handles it the same as any other failed attempt.
            onError(-1, e.message ?: "Authentication unavailable.")
        }
    }
}
