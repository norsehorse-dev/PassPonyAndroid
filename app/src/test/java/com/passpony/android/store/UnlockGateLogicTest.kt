package com.passpony.android.store

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Context/DataStore-touching parts of UnlockGate (isFresh, lock,
 * authenticate) aren't unit-tested here -- matching the project's
 * established split where Context/Keystore-touching code (PassphraseCache,
 * PgpKeyStore, PgpEngine) is verified manually on-device, not on the plain
 * JVM. Only the pure grace-window and relock decisions UnlockGateLogic
 * makes are exercised, with a fake clock passed in explicitly.
 */
class UnlockGateLogicTest {

    @Test
    fun isFresh_neverUnlocked_isNotFresh() {
        assertFalse(UnlockGateLogic.isFresh(null, nowMillis = 1_000_000L, graceMillis = 300_000L))
    }

    @Test
    fun isFresh_wellWithinWindow_isFresh() {
        assertTrue(
            UnlockGateLogic.isFresh(lastUnlockMillis = 1_000_000L, nowMillis = 1_100_000L, graceMillis = 300_000L)
        )
    }

    @Test
    fun isFresh_justBeforeBoundary_isFresh() {
        assertTrue(UnlockGateLogic.isFresh(lastUnlockMillis = 0L, nowMillis = 299_999L, graceMillis = 300_000L))
    }

    @Test
    fun isFresh_exactlyAtBoundary_isNotFresh() {
        // elapsed == graceMillis is the lapsed side, not the fresh side --
        // matches iOS's strict "<" in UnlockGate.isFresh.
        assertFalse(UnlockGateLogic.isFresh(lastUnlockMillis = 0L, nowMillis = 300_000L, graceMillis = 300_000L))
    }

    @Test
    fun isFresh_justPastBoundary_isNotFresh() {
        assertFalse(UnlockGateLogic.isFresh(lastUnlockMillis = 0L, nowMillis = 300_001L, graceMillis = 300_000L))
    }

    @Test
    fun isFresh_futureLastUnlock_isStillFresh() {
        // Clock skew / a stamp written a moment ahead of `now` shouldn't
        // read as lapsed -- the elapsed delta just goes negative, which is
        // still less than graceMillis.
        assertTrue(UnlockGateLogic.isFresh(lastUnlockMillis = 1_000L, nowMillis = 500L, graceMillis = 300_000L))
    }

    @Test
    fun shouldRelock_onStartWithLapsedWindow_relocks() {
        assertTrue(UnlockGateLogic.shouldRelock(Lifecycle.Event.ON_START, isFreshNow = false))
    }

    @Test
    fun shouldRelock_onStartWithFreshWindow_doesNotRelock() {
        assertFalse(UnlockGateLogic.shouldRelock(Lifecycle.Event.ON_START, isFreshNow = true))
    }

    @Test
    fun shouldRelock_otherEventsNeverRelock_evenWhenLapsed() {
        assertFalse(UnlockGateLogic.shouldRelock(Lifecycle.Event.ON_RESUME, isFreshNow = false))
        assertFalse(UnlockGateLogic.shouldRelock(Lifecycle.Event.ON_STOP, isFreshNow = false))
        assertFalse(UnlockGateLogic.shouldRelock(Lifecycle.Event.ON_CREATE, isFreshNow = false))
        assertFalse(UnlockGateLogic.shouldRelock(Lifecycle.Event.ON_PAUSE, isFreshNow = false))
        assertFalse(UnlockGateLogic.shouldRelock(Lifecycle.Event.ON_DESTROY, isFreshNow = false))
    }
}
