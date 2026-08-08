package com.passpony.android.ui.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import uniffi.pass_ffi.ConflictChoice
import uniffi.pass_ffi.ConflictResolver

/**
 * Bridges GitSync.sync()'s blocking FFI conflict callback to a Compose
 * dialog. [choose] is called by pass-core on whatever thread `sync()`
 * itself runs on -- this app always calls it from a Dispatchers.IO
 * coroutine (see AppViewModel.syncNow), never Main -- once per
 * conflicted file, and it must block that thread until the app supplies
 * a choice. [conflictPath] is the signal SyncScreen observes to show
 * the dialog; [resolve] runs on the main thread once the user picks,
 * unblocking [choose]. Mirrors iOS's UIResolver, which blocks a
 * DispatchSemaphore instead of a CompletableDeferred -- same contract,
 * Kotlin's coroutine-native equivalent.
 *
 * NEVER call [choose] from the main thread: [resolve] can only run
 * there, so the two would deadlock each other.
 */
class BlockingResolver : ConflictResolver {
    var conflictPath by mutableStateOf<String?>(null)
        private set

    private var pending: CompletableDeferred<ConflictChoice>? = null

    override fun choose(entryPath: String): ConflictChoice {
        val deferred = CompletableDeferred<ConflictChoice>()
        pending = deferred
        conflictPath = entryPath
        return runBlocking { deferred.await() }
    }

    /** Called from the main thread once the user picks in the dialog. */
    fun resolve(choice: ConflictChoice) {
        val deferred = pending ?: return
        pending = null
        conflictPath = null
        deferred.complete(choice)
    }
}
