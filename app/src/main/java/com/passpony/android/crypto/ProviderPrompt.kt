package com.passpony.android.crypto

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 3.0: the provider's own screens (first-use consent, passphrase, card
 * tap). The provider engine runs inside a synchronous callback from the
 * core and cannot show anything, so when the provider answers "show this
 * first" the engine parks the PendingIntent here and fails the call.
 * Whoever has an activity (the main app, autofill, the passkey
 * activities) launches it and, when it comes back OK, runs the store
 * operation again.
 *
 * Kotlin only on purpose: an "interaction required" error in the core
 * would change the FFI for iOS and Desktop, which can never raise it.
 */
object ProviderPrompt {
    private const val RESUME_VALID_MS = 60_000L

    private val _pending = MutableStateFlow<PendingIntent?>(null)

    /** A provider screen waiting to be shown. */
    val pending: StateFlow<PendingIntent?> = _pending

    private val _generation = MutableStateFlow(0)

    /** Goes up each time a provider screen came back OK: run it again. */
    val generation: StateFlow<Int> = _generation

    private val lock = Any()
    private var resume: Intent? = null
    private var resumeAction: String? = null
    private var resumeAt = 0L

    /** The action of the call that last asked for a screen. */
    @Volatile
    private var parkedAction: String? = null

    /** What a parked screen on this thread should do, when not the default. */
    private sealed class Mode {
        data object Quiet : Mode()
        class Capture(var prompt: PendingIntent? = null) : Mode()
    }

    // The core calls the engine back on the thread that called the store,
    // so a thread-local set around a store call reaches the engine.
    private val mode = ThreadLocal<Mode?>()

    /**
     * Run [block] without ever asking for a provider screen: background
     * work (the passkey index) must not pop the key app up on its own.
     */
    fun <T> quietly(block: () -> T): T {
        val before = mode.get()
        mode.set(Mode.Quiet)
        try {
            return block()
        } finally {
            mode.set(before)
        }
    }

    /** What [capturing] hands back: the outcome, and a screen to show if one was asked for. */
    class Captured<T>(val result: Result<T>, val prompt: PendingIntent?)

    /**
     * Run [block], keeping any provider screen it asks for to the caller
     * and away from MainActivity. For the autofill and passkey
     * activities, which show the screen themselves with [ProviderRetry].
     * [block] must not switch threads.
     */
    fun <T> capturing(block: () -> T): Captured<T> {
        val before = mode.get()
        val capture = Mode.Capture()
        mode.set(capture)
        try {
            val result = runCatching(block)
            return Captured(result, capture.prompt)
        } finally {
            mode.set(before)
        }
    }

    /**
     * Called by the engine, with the action of the call that needs the
     * screen. False when this thread must not prompt.
     */
    fun park(intent: PendingIntent, action: String): Boolean {
        if (mode.get() !is Mode.Quiet) parkedAction = action
        when (val current = mode.get()) {
            is Mode.Quiet -> return false
            is Mode.Capture -> current.prompt = intent
            null -> _pending.value = intent
        }
        return true
    }

    /** Called by the host once it has handed the screen to the system. */
    fun launched() {
        _pending.value = null
    }

    /**
     * Called by the host with the screen's result. On OK the intent the
     * provider handed back is what the next call of that action sends, as
     * the API specifies; it is used once and only for a minute.
     */
    fun finished(ok: Boolean, data: Intent?) {
        _pending.value = null
        if (!ok) return
        synchronized(lock) {
            resume = data
            // Matched by the action of the call that asked, not by what
            // the returned intent says: a provider may hand back an intent
            // with no action of its own (OpenKeychain does for some
            // screens), and it still has to be the one that is sent.
            resumeAction = parkedAction
            resumeAt = SystemClock.elapsedRealtime()
        }
        _generation.value += 1
    }

    /** The intent to re-execute for [action], if a screen just returned one. */
    fun takeResume(action: String): Intent? = synchronized(lock) {
        val intent = resume ?: return null
        if (resumeAction != action) return null
        resume = null
        if (SystemClock.elapsedRealtime() - resumeAt > RESUME_VALID_MS) return null
        if (intent.action == null) intent.action = action
        intent
    }
}
