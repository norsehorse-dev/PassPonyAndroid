package com.passpony.android.ui.sync

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.passpony.android.R
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.AppViewModel
import kotlinx.coroutines.launch
import uniffi.pass_ffi.ConflictChoice

/**
 * The Sync screen's busy/message/conflict state. A real ViewModel --
 * not local Compose `remember` state like Add/Edit/Move use -- because
 * the resolver's blocked IO thread must survive a configuration change:
 * if the user rotates the device mid-conflict-dialog, the coroutine
 * driving syncNow (and the background thread parked inside
 * BlockingResolver.choose, waiting on [resolver]) has to still be
 * there when the screen recomposes, and only viewModelScope guarantees
 * that -- a `remember`ed object would be replaced, orphaning the
 * blocked thread forever.
 *
 * All the actual git/file work happens on [AppViewModel], passed in
 * per call rather than held as a field here: it's the one shared
 * instance the whole nav graph already uses, and SyncViewModel doesn't
 * need its own copy.
 */
class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    var busy by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    val resolver = BlockingResolver()

    fun push(appViewModel: AppViewModel) = run { appViewModel.pushToRemote() }

    fun syncNow(appViewModel: AppViewModel) = run {
        val outcome = appViewModel.syncNow(resolver)
        message = SyncMessages.describe(outcome)
    }

    fun updateRemote(appViewModel: AppViewModel, url: String) = run {
        appViewModel.updateRemote(url)
        message = context.getString(R.string.sync_remote_updated)
    }

    fun publish(appViewModel: AppViewModel, url: String) = run {
        appViewModel.publishStore(url)
    }

    fun clone(appViewModel: AppViewModel, url: String) = run {
        appViewModel.cloneReplaceStore(url)
    }

    fun resolveConflict(choice: ConflictChoice) {
        resolver.resolve(choice)
    }

    /** busy/message bookkeeping shared by every action above. */
    private fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try {
                work()
            } catch (e: Exception) {
                message = e.ponyMessage(context)
            } finally {
                busy = false
            }
        }
    }
}
