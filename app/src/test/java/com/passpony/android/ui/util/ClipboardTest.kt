package com.passpony.android.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises Clipboard's clear-only-if-still-ours logic through the
 * [Clipboard.Sink] / [Clipboard.Scheduler] seams — no real ClipboardManager
 * or Handler involved, so this runs as a plain JVM unit test.
 */
class ClipboardTest {

    private class FakeSink : Clipboard.Sink {
        var currentLabel: String? = null
        var currentValue: String? = null
        var cleared = false

        override fun setPrimaryClip(label: String, value: String) {
            currentLabel = label
            currentValue = value
            cleared = false
        }

        override fun currentClipLabel(): String? = currentLabel

        override fun clearPrimaryClip() {
            cleared = true
            currentLabel = null
            currentValue = null
        }
    }

    /** Captures the scheduled action instead of running it, so the test
     * decides when the clear timer "fires". */
    private class CapturingScheduler : Clipboard.Scheduler {
        var lastDelayMillis: Long? = null
        private var pending: (() -> Unit)? = null

        override fun postDelayed(delayMillis: Long, action: () -> Unit) {
            lastDelayMillis = delayMillis
            pending = action
        }

        fun fire() {
            pending?.invoke()
            pending = null
        }
    }

    @Test
    fun copyEphemeral_setsTheValueOnTheSink() {
        val sink = FakeSink()
        val scheduler = CapturingScheduler()

        Clipboard.copyEphemeral(sink, scheduler, "hunter2")

        assertEquals("hunter2", sink.currentValue)
    }

    @Test
    fun copyEphemeral_schedulesClearAtFortyFiveSeconds() {
        val sink = FakeSink()
        val scheduler = CapturingScheduler()

        Clipboard.copyEphemeral(sink, scheduler, "hunter2")

        assertEquals(Clipboard.CLEAR_AFTER_SECONDS * 1000, scheduler.lastDelayMillis)
    }

    @Test
    fun scheduledClear_wipesTheClipboardWhenStillOurs() {
        val sink = FakeSink()
        val scheduler = CapturingScheduler()

        Clipboard.copyEphemeral(sink, scheduler, "hunter2")
        scheduler.fire()

        assertTrue(sink.cleared)
        assertNull(sink.currentValue)
    }

    @Test
    fun scheduledClear_leavesALaterCopyAlone() {
        val sink = FakeSink()
        val scheduler = CapturingScheduler()

        Clipboard.copyEphemeral(sink, scheduler, "hunter2")
        sink.setPrimaryClip("someone-elses-label", "other value") // a later copy replaced ours
        scheduler.fire()

        assertFalse(sink.cleared)
        assertEquals("other value", sink.currentValue)
    }

    @Test
    fun clearIfStillOurs_wipesOnMatchingLabel() {
        val sink = FakeSink()
        sink.setPrimaryClip("mine", "value")

        Clipboard.clearIfStillOurs(sink, "mine")

        assertTrue(sink.cleared)
    }

    @Test
    fun clearIfStillOurs_ignoresMismatchedLabel() {
        val sink = FakeSink()
        sink.setPrimaryClip("mine", "value")

        Clipboard.clearIfStillOurs(sink, "not-mine")

        assertFalse(sink.cleared)
        assertEquals("value", sink.currentValue)
    }
}
