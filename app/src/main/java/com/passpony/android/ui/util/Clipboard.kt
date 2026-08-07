package com.passpony.android.ui.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.util.concurrent.atomic.AtomicLong

/**
 * Ephemeral clipboard copy, port of PassPony iOS's Clipboard.swift. iOS
 * gets self-expiry for free from UIPasteboard's `.expirationDate` option
 * (and opts out of Handoff with `.localOnly`, which Android has no
 * equivalent concept of — there is no cross-device clipboard to opt out
 * of). ClipboardManager has neither, so this schedules its own clear on
 * the main thread after [CLEAR_AFTER_SECONDS], and — since a bare timer
 * would just as happily wipe a *later* copy — only fires if the
 * clipboard still holds the exact clip this call made.
 *
 * [Sink] and [Scheduler] narrow ClipboardManager and Handler down to
 * what the clear-only-if-still-ours decision actually needs, so that
 * logic is a plain JVM unit test rather than an instrumentation test:
 * only [SystemSink] below touches real ClipData, which (like most of the
 * Android framework) throws "not mocked" outside Robolectric or a real
 * device/emulator.
 */
object Clipboard {
    const val CLEAR_AFTER_SECONDS = 45L

    internal interface Sink {
        fun setPrimaryClip(label: String, value: String)
        fun currentClipLabel(): String?
        fun clearPrimaryClip()
    }

    internal fun interface Scheduler {
        fun postDelayed(delayMillis: Long, action: () -> Unit)
    }

    private class SystemSink(private val manager: ClipboardManager) : Sink {
        override fun setPrimaryClip(label: String, value: String) {
            val clip = ClipData.newPlainText(label, value)
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            manager.setPrimaryClip(clip)
        }

        override fun currentClipLabel(): String? =
            manager.primaryClip?.description?.label?.toString()

        override fun clearPrimaryClip() = manager.clearPrimaryClip()
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val systemScheduler = Scheduler { delayMillis, action ->
        mainHandler.postDelayed(action, delayMillis)
    }
    private val labelCounter = AtomicLong(0)

    /**
     * Copy [value] as a sensitive-flagged clip (hidden from the system
     * clipboard preview on Android 13+; harmlessly ignored on older
     * versions) and schedule a clear in [CLEAR_AFTER_SECONDS].
     */
    fun copyEphemeral(context: Context, value: String) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        copyEphemeral(SystemSink(manager), systemScheduler, value)
    }

    internal fun copyEphemeral(sink: Sink, scheduler: Scheduler, value: String) {
        val label = "passpony-clip-${labelCounter.incrementAndGet()}"
        sink.setPrimaryClip(label, value)
        scheduler.postDelayed(CLEAR_AFTER_SECONDS * 1000) { clearIfStillOurs(sink, label) }
    }

    /** Only wipes the clipboard if it still holds the exact clip [ownLabel]
     * names — a later copy, ours or another app's, is left alone. */
    internal fun clearIfStillOurs(sink: Sink, ownLabel: String) {
        if (sink.currentClipLabel() == ownLabel) {
            sink.clearPrimaryClip()
        }
    }
}
