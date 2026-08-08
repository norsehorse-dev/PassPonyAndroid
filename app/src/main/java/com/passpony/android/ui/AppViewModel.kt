package com.passpony.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.passpony.android.BuildConfig
import com.passpony.android.crypto.EngineProvider
import com.passpony.android.store.BrowseModel
import com.passpony.android.store.DemoSeed
import com.passpony.android.store.StorePaths
import com.passpony.android.store.ponyMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import uniffi.pass_ffi.ConflictResolver
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.EntryRef
import uniffi.pass_ffi.GitException
import uniffi.pass_ffi.GitSync
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.SyncOutcome
import uniffi.pass_ffi.SyncStatus
import uniffi.pass_ffi.commitMessageAdd
import uniffi.pass_ffi.commitMessageEdit
import uniffi.pass_ffi.commitMessageRemove
import uniffi.pass_ffi.commitMessageRename

/**
 * Same structure as PassPony iOS's AppModel: open/refresh/read/save/move/
 * delete plus the debug demo seed, StateFlow standing in for @Published.
 * P08 wires best-effort commit-on-write through [git], matching iOS.
 * P09 adds [syncStatus] (refreshed alongside entries, so the P03
 * unpushed-changes banner goes live) plus the git actions the Sync
 * screen drives directly: push, sync, remote management, publish, and
 * clone-then-swap. Those surface real GitExceptions to their caller
 * rather than swallowing them like [saveEntry]/[moveEntry]/[deleteEntry]
 * do -- Sync is where the user actually wants to know a push failed.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()

    var format: StoreFormat = StorePaths.currentFormatSnapshot(context)
        private set
    var engine: CryptoBackend = EngineProvider.engine(context, format)
        private set

    private val _entries = MutableStateFlow<List<EntryRef>>(emptyList())
    val entries: StateFlow<List<EntryRef>> = _entries.asStateFlow()

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus?>(null)

    /**
     * Null exactly when this store has no git repo (mirrors iOS's
     * `model.git == nil` check); refreshed alongside [entries] so the
     * P03 unpushed-changes banner and the Sync screen's status section
     * both stay live without a separate poll.
     */
    val syncStatus: StateFlow<SyncStatus?> = _syncStatus.asStateFlow()

    val visibleEntries: StateFlow<List<EntryRef>> =
        combine(_entries, _searchText) { all, query -> BrowseModel.visibleEntries(all, query) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var store: PassStore? = null

    /**
     * Best-effort, like iOS's `git: GitSync?` opened with `try?`: most
     * stores have no git repo yet (P09 adds the init/clone flow), and a
     * missing repo is not an error worth surfacing to lastError.
     */
    private var git: GitSync? = null

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    /**
     * P06 debug-only developer toggle for verifying the pass engine end
     * to end before P10 adds a real format switch in Settings. Wired to
     * the (currently otherwise inert) settings gear in StoreListScreen;
     * a real Settings screen replaces this entirely in P10. No-op in a
     * release build.
     */
    fun debugToggleFormat() {
        if (!BuildConfig.DEBUG) return
        format = if (format == StoreFormat.PASSAGE) StoreFormat.PASS else StoreFormat.PASSAGE
        engine = EngineProvider.engine(context, format)
        store = null
        openStore()
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
            git = runCatching { GitSync.open(root.absolutePath) }.getOrNull()

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
            // status() can throw GitException (e.g. a genuinely broken
            // repo); that's a Sync-screen concern, not a reason to
            // surface an error here or drop the freshly-read entries.
            _syncStatus.value = runCatching { git?.status() }.getOrNull()
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

    /**
     * Add (isNew) or edit an entry, then a best-effort commit through
     * the git engine -- `runCatching { git?...  }` mirrors iOS's
     * `try? git?.commitPaths(...)`: a store without git (or a git error)
     * never blocks the save, and the failure is not surfaced to
     * lastError since the save itself already succeeded.
     */
    fun saveEntry(name: String, content: ByteArray, isNew: Boolean) {
        val current = store ?: return
        try {
            current.writeEntry(name, content, engine)
            val message = if (isNew) commitMessageAdd(name) else commitMessageEdit(name)
            runCatching { git?.commitPaths(listOf(StorePaths.entryFileName(name, format)), message) }
            refresh()
        } catch (e: Exception) {
            _lastError.value = e.ponyMessage(context)
        }
    }

    /**
     * Move/rename. Core semantics: passage always re-encrypts the moved
     * entry; pass re-encrypts only when the resolved key set differs.
     * One commit covers both the old and new paths, matching iOS's
     * single commitPaths([from, to]) call.
     */
    fun moveEntry(from: String, to: String): Boolean {
        val current = store ?: return false
        return try {
            current.moveEntry(from, to, engine)
            runCatching {
                git?.commitPaths(
                    listOf(StorePaths.entryFileName(from, format), StorePaths.entryFileName(to, format)),
                    commitMessageRename(from, to)
                )
            }
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
            runCatching { git?.commitPaths(listOf(StorePaths.entryFileName(name, format)), commitMessageRemove(name)) }
            refresh()
        } catch (e: Exception) {
            _lastError.value = e.ponyMessage(context)
        }
    }

    /** The `origin` remote's URL, if configured. Local config read, no network. */
    fun remoteUrl(): String? = git?.remoteUrl()

    /** Push the current branch to origin. Network I/O -- always off Main. */
    suspend fun pushToRemote() {
        withContext(Dispatchers.IO) {
            git?.push() ?: throw GitException.NoRepository()
        }
        refresh()
    }

    /** Create or repoint `origin`. */
    suspend fun updateRemote(url: String) {
        withContext(Dispatchers.IO) {
            git?.setRemote(url) ?: throw GitException.NoRepository()
        }
        refresh()
    }

    /**
     * Fetch + rebase (or fast-forward, or nothing). [resolver] is asked
     * once per conflicted file, on this IO-dispatcher thread -- see
     * ui/sync/BlockingResolver, which bridges that blocking callback to
     * the Sync screen's conflict dialog.
     */
    suspend fun syncNow(resolver: ConflictResolver): SyncOutcome {
        val outcome = withContext(Dispatchers.IO) {
            git?.sync(resolver) ?: throw GitException.NoRepository()
        }
        refresh()
        return outcome
    }

    /**
     * Publish the current local store to an empty remote: init (commits
     * everything with CLI-style messages) if this store has no repo
     * yet, then set the remote and push. Matches iOS's
     * SyncView.publishSection.
     */
    suspend fun publishStore(url: String) {
        withContext(Dispatchers.IO) {
            val handle = git ?: GitSync.init(StorePaths.storeRoot(context, format).absolutePath, format)
            handle.setRemote(url)
            handle.push()
        }
        openStore()
    }

    /**
     * Replace the local store with a freshly cloned one. The clone
     * lands in a scratch directory first, so a failed clone never
     * touches the current store; only on success does it replace the
     * store for this format. Matches iOS's SyncView.cloneSection.
     */
    suspend fun cloneReplaceStore(url: String) {
        val root = StorePaths.storeRoot(context, format)
        val scratch = StorePaths.cloneScratchDir(context)
        withContext(Dispatchers.IO) {
            scratch.deleteRecursively()
            GitSync.cloneFrom(url, scratch.absolutePath, null).close()
            root.deleteRecursively()
            if (!scratch.renameTo(root)) {
                throw GitException.Other("couldn't move the cloned store into place")
            }
        }
        openStore()
    }

    private fun seedDemoStore(store: PassStore) {
        for ((name, content) in DemoSeed.ENTRIES) {
            store.writeEntry(name, content.toByteArray(Charsets.UTF_8), engine)
        }
    }
}
