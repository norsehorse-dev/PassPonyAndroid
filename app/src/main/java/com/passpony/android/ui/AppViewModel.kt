package com.passpony.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.passpony.android.BuildConfig
import com.passpony.android.crypto.DevCryptoEngine
import com.passpony.android.store.BrowseModel
import com.passpony.android.store.DemoSeed
import com.passpony.android.store.StorePaths
import com.passpony.android.store.ponyMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.EntryRef
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreFormat

/**
 * Same structure as PassPony iOS's AppModel: open/refresh/read/save/move/
 * delete plus the debug demo seed, StateFlow standing in for @Published.
 * Git status and commit-on-write are out of scope here (P09 wires
 * GitSync into this app); syncStatusAhead stays at zero until then, so
 * the unpushed-changes banner exists but never shows in this packet.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()

    val format: StoreFormat = StorePaths.currentFormatSnapshot(context)
    val engine: CryptoBackend = DevCryptoEngine

    private val _entries = MutableStateFlow<List<EntryRef>>(emptyList())
    val entries: StateFlow<List<EntryRef>> = _entries.asStateFlow()

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** P09 replaces this with the real GitSync status; the banner reads it. */
    val syncStatusAhead: StateFlow<Int> = MutableStateFlow(0)

    val visibleEntries: StateFlow<List<EntryRef>> =
        combine(_entries, _searchText) { all, query -> BrowseModel.visibleEntries(all, query) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var store: PassStore? = null

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    /**
     * Local file I/O plus a handful of FFI calls; fast enough to call
     * directly from a first-composition LaunchedEffect without a
     * background dispatcher, the same way iOS calls the equivalent
     * synchronous Swift FFI calls from .task.
     */
    fun openStore() {
        try {
            val root = StorePaths.storeRoot(context, format)
            root.mkdirs()
            val opened = PassStore.open(root.absolutePath, format)
            store = opened

            // Demo seeding is passage-only; pass stores start from
            // imported keys and a real .gpg-id (P06). Held back until
            // onboarding completes so the onboarding import/try slides
            // see a genuinely empty store, once P14 adds them.
            if (BuildConfig.DEBUG &&
                format == StoreFormat.PASSAGE &&
                opened.entries().isEmpty() &&
                StorePaths.onboardingCompletedSnapshot(context)
            ) {
                seedDemoStore(opened)
            }

            refresh()
        } catch (e: Exception) {
            _lastError.value = e.ponyMessage(context)
        }
    }

    fun refresh() {
        val current = store ?: return
        try {
            _entries.value = current.entries()
        } catch (e: Exception) {
            _lastError.value = e.ponyMessage(context)
        }
    }

    fun readEntry(name: String): ByteArray? {
        val current = store ?: return null
        return try {
            current.readEntry(name, engine)
        } catch (e: Exception) {
            _lastError.value = e.ponyMessage(context)
            null
        }
    }

    fun saveEntry(name: String, content: ByteArray) {
        val current = store ?: return
        try {
            current.writeEntry(name, content, engine)
            refresh()
        } catch (e: Exception) {
            _lastError.value = e.ponyMessage(context)
        }
    }

    /**
     * Move/rename. Core semantics: passage always re-encrypts the moved
     * entry; pass re-encrypts only when the resolved key set differs.
     */
    fun moveEntry(from: String, to: String): Boolean {
        val current = store ?: return false
        return try {
            current.moveEntry(from, to, engine)
            refresh()
            true
        } catch (e: Exception) {
            _lastError.value = e.ponyMessage(context)
            false
        }
    }

    fun deleteEntry(name: String) {
        val current = store ?: return
        try {
            current.removeEntry(name)
            refresh()
        } catch (e: Exception) {
            _lastError.value = e.ponyMessage(context)
        }
    }

    private fun seedDemoStore(store: PassStore) {
        for ((name, content) in DemoSeed.ENTRIES) {
            store.writeEntry(name, content.toByteArray(Charsets.UTF_8), engine)
        }
    }
}
