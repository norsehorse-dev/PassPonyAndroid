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
     * True while [lastUnlockMillis] is still within [graceMillis] of
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
}
