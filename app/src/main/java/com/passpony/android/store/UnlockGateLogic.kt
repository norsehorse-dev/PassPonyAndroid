package com.passpony.android.store

import androidx.lifecycle.Lifecycle

/**
 * Pure grace-window and relock decisions for UnlockGate, split out so
 * the boundary math is unit-testable on the plain JVM without a
 * Context or DataStore -- mirrors how P10 split ReencryptOps/KeySummary
 * out from their Context/FFI-touching callers.
 */
object UnlockGateLogic {
    /**
     * True while [lastUnlockMillis] (the last unlock, or since 2.0 the
     * last time the app left the foreground unlocked) is still within [graceMillis] of
     * [nowMillis]. Never unlocked (null) is never fresh. The boundary
     * itself (elapsed == graceMillis exactly) reads as lapsed, not
     * fresh -- matches iOS's strict "<" in UnlockGate.isFresh.
     */
    fun isFresh(lastUnlockMillis: Long?, nowMillis: Long, graceMillis: Long): Boolean {
        if (lastUnlockMillis == null) return false
        return nowMillis - lastUnlockMillis < graceMillis
    }

    /**
     * MainActivity's ON_START lifecycle observer relocks exactly when
     * the app is starting back up (fresh process or returning from
     * background) and the grace window has already lapsed. Every
     * other lifecycle event, and a still-fresh window, leave the
     * current lock state alone -- a panic-lock triggered moments
     * before backgrounding must not get silently reverted by a
     * still-fresh ON_START a beat later, since it never has one
     * (locking clears the stamp, so isFreshNow is false right after).
     */
    fun shouldRelock(event: Lifecycle.Event, isFreshNow: Boolean): Boolean =
        event == Lifecycle.Event.ON_START && !isFreshNow

    /**
     * 2.0: the grace window counts from when the app was last in front
     * and unlocked, not from the last unlock. Leaving the app (ON_STOP)
     * while it is unlocked restamps the window, so a user who stayed in
     * the app for ten minutes and then opened the system file picker
     * comes back to where they were instead of the lock screen. Leaving
     * while locked never stamps: a stale stamp must not turn into a free
     * unlock just because the lock screen was put in the background.
     */
    fun shouldStampOnLeave(event: Lifecycle.Event, lockedNow: Boolean): Boolean =
        event == Lifecycle.Event.ON_STOP && !lockedNow
}
