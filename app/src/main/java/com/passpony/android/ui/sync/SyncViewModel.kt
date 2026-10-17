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
import uniffi.pass_ffi.GitException
import uniffi.pass_ffi.HostKey

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

    /** An SSH host to confirm, and the action to run again once trusted. */
    class HostPrompt(val host: String, val key: HostKey, val retry: suspend () -> Unit)

    var hostPrompt by mutableStateOf<HostPrompt?>(null)
        private set

    /** The user compared the fingerprint and trusts the host: pin, retry. */
    fun trustHost(appViewModel: AppViewModel) {
        val prompt = hostPrompt ?: return
        hostPrompt = null
        appViewModel.trustHost(prompt.host, prompt.key)
        run(prompt.retry)
    }

    fun declineHost() {
        hostPrompt = null
        message = context.getString(R.string.ssh_host_not_trusted)
    }

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

    /** 2.0 rc4: the new device key is one of the person's devices. */
    fun approveDeviceKey(appViewModel: AppViewModel, key: String) = run {
        val report = appViewModel.approveDeviceKey(key)
        val done = context.getString(R.string.device_key_reencrypted, report.rewritten.size)
        message = if (report.skipped > 0u) {
            done + " " + context.getString(R.string.device_key_skipped, report.skipped.toInt())
        } else {
            done
        }
    }

    /** 2.0 rc4: the person doesn't recognize the key; take it out. */
    fun removeDeviceKey(appViewModel: AppViewModel, key: String) = run {
        appViewModel.removeDeviceKey(key)
        message = context.getString(R.string.device_key_removed)
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
            } catch (e: GitException.UnknownHostKey) {
                hostPrompt = HostPrompt(e.host, e.key, work)
            } catch (e: Exception) {
                message = e.ponyMessage(context)
            } finally {
                busy = false
            }
        }
    }
}
