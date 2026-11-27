package com.passpony.android.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.passpony.android.BuildConfig
import com.passpony.android.R
import com.passpony.android.crypto.AgeIdentityStore
import com.passpony.android.crypto.EngineProvider
import com.passpony.android.crypto.PgpKeySource
import com.passpony.android.crypto.PgpKeySourceStore
import com.passpony.android.crypto.ProviderConnection
import com.passpony.android.crypto.ProviderPrompt
import com.passpony.android.crypto.PgpKeyStore
import com.passpony.android.crypto.SshKeyStore
import com.passpony.android.passkey.PasskeyIndex
import com.passpony.android.store.KeyBackupState
import java.io.File
import com.passpony.android.store.AgeHeader
import com.passpony.android.store.BrowseModel
import com.passpony.android.store.DemoSeed
import com.passpony.android.store.DeviceKeys
import com.passpony.android.store.FileStoreFs
import com.passpony.android.store.FolderProbe
import com.passpony.android.store.SafStoreFs
import com.passpony.android.store.StoreMove
import com.passpony.android.store.StoreLocation
import com.passpony.android.store.StorePaths
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.settings.ReencryptOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.pass_ffi.ConflictCopy
import uniffi.pass_ffi.ConflictResolver
import uniffi.pass_ffi.conflictOf
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.EntryRef
import uniffi.pass_ffi.GitException
import uniffi.pass_ffi.GitSync
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.ReencryptReport
import uniffi.pass_ffi.StoreException
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.StoreKeyType
import uniffi.pass_ffi.SyncOutcome
import uniffi.pass_ffi.SyncStatus
import uniffi.pass_ffi.commitMessageAdd
import uniffi.pass_ffi.commitMessageEdit
import uniffi.pass_ffi.commitMessageReencrypt
import uniffi.pass_ffi.commitMessageRemove
import uniffi.pass_ffi.commitMessageRename
import uniffi.pass_ffi.BackupSection
import uniffi.pass_ffi.HotpAdvance
import uniffi.pass_ffi.ageDecryptWithPassphrase
import uniffi.pass_ffi.ageEncryptWithPassphrase
import uniffi.pass_ffi.backupDecode
import uniffi.pass_ffi.backupEncode
import uniffi.pass_ffi.backupKindAgeIdentities
import uniffi.pass_ffi.backupKindPgpSecretKey
import uniffi.pass_ffi.commitMessageHotpIncrement
import uniffi.pass_ffi.commitMessageOtpAdd
import uniffi.pass_ffi.entryHotpNext
import uniffi.pass_ffi.HostKey
import uniffi.pass_ffi.SshKey
import uniffi.pass_ffi.backupKindSshPrivateKey
import uniffi.pass_ffi.sshImportKey
import uniffi.pass_ffi.commitMessagePasskeyRemove
import uniffi.pass_ffi.entryRemovePasskey

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

    /**
     * UI state for the store list: false until the first entry read
     * after an open, so the list shows "loading" instead of "no entries".
     */
    private val _storeLoaded = MutableStateFlow(false)
    val storeLoaded: StateFlow<Boolean> = _storeLoaded.asStateFlow()

    /** Why the store couldn't be opened, for the store list's error state. */
    private val _openError = MutableStateFlow<String?>(null)
    val openError: StateFlow<String?> = _openError.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus?>(null)

    /**
     * Null exactly when this store has no git repo (mirrors iOS's
     * `model.git == nil` check); refreshed alongside [entries] so the
     * P03 unpushed-changes banner and the Sync screen's status section
     * both stay live without a separate poll.
     */
    val syncStatus: StateFlow<SyncStatus?> = _syncStatus.asStateFlow()

    private val _storeLocation = MutableStateFlow<StoreLocation>(StoreLocation.AppStorage)

    /** 3.0: where the open store lives; see [StoreLocation]. */
    val storeLocation: StateFlow<StoreLocation> = _storeLocation.asStateFlow()

    private val _folderLost = MutableStateFlow(false)

    /**
     * 3.0: the folder store's folder cannot be reached (deleted, moved,
     * or the permission was taken away). The list shows its own screen
     * for this, never an empty store.
     */
    val folderLost: StateFlow<Boolean> = _folderLost.asStateFlow()

    private val _folderName = MutableStateFlow<String?>(null)

    /** The folder's own name when the store is a folder store, for display. */
    val folderName: StateFlow<String?> = _folderName.asStateFlow()

    private val _storeKeys = MutableStateFlow<StoreKeys?>(null)

    /**
     * 3.0: what kind of age keys the open passage store uses, and whether
     * that can still be changed. Null for a pass store or no store.
     */
    val storeKeys: StateFlow<StoreKeys?> = _storeKeys.asStateFlow()

    /** See [storeKeys]. */
    data class StoreKeys(
        val postQuantum: Boolean,
        /** A classic key sits in a post-quantum store; saving is blocked. */
        val blocked: Boolean,
        /** Empty store listing only this device: the kind is still a choice. */
        val canChoose: Boolean,
    )

    private val _pendingDeviceKeys = MutableStateFlow<List<String>>(emptyList())

    /**
     * 2.0 rc4: keys another device added to `.age-recipients` that this
     * one has not been asked about. The Sync screen asks, one at a time.
     */
    val pendingDeviceKeys: StateFlow<List<String>> = _pendingDeviceKeys.asStateFlow()

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
     * Switch the store format and reopen. Each format keeps its own
     * store directory (see StorePaths.storeRoot), so this never touches
     * the other format's data -- matches iOS's SettingsView format
     * picker. Supersedes the P06 debug-only toggle this replaced.
     */
    suspend fun switchFormat(newFormat: StoreFormat) {
        if (newFormat == format) return
        StorePaths.setFormat(context, newFormat)
        format = newFormat
        engine = EngineProvider.engine(context, newFormat)
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
            // The pass engine reads the key files and the cached
            // passphrase when it is built, so build it again here: every
            // key import, delete and unlock ends in openStore, and an
            // engine from before would not know the new key.
            if (format == StoreFormat.PASS) engine = EngineProvider.engine(context, format)
            val location = StorePaths.location(context, format)
            _storeLocation.value = location
            _folderName.value = (location as? StoreLocation.Folder)
                ?.let { SafStoreFs.folderName(context, it.treeUri) }
            val opened = StorePaths.openStore(context, format)
            store = opened
            // 3.0: git only for app storage. A folder store syncs through
            // its folder, and libgit2 cannot work on a document tree.
            git = if (location is StoreLocation.AppStorage) {
                runCatching { GitSync.open(StorePaths.storeRoot(context, format).absolutePath) }.getOrNull()
            } else {
                null
            }

            // Demo seeding is passage-only; pass stores start from
            // imported keys and a real .gpg-id (P06). Held back until
            // onboarding completes so the onboarding import/try slides
            // see a genuinely empty store, once P14 adds them.
            if (BuildConfig.DEBUG &&
                location is StoreLocation.AppStorage &&
                format == StoreFormat.PASSAGE &&
                opened.entries().isEmpty() &&
                StorePaths.onboardingCompletedSnapshot(context)
            ) {
                seedDemoStore(opened)
            }

            // 3.0: see DeviceKeys.pinPostQuantum. Best effort; a store it
            // could not pin still opens.
            if (format == StoreFormat.PASSAGE) {
                runCatching { DeviceKeys.pinPostQuantum(context, opened, git) }
            }

            _openError.value = null
            _folderLost.value = false
            refresh()
            rebuildPasskeyIndex()
            // 3.0: a folder store has no sync step to hang this on, so
            // this phone lists its key, and looks for keys other devices
            // added, whenever the store is opened.
            if (location is StoreLocation.Folder && format == StoreFormat.PASSAGE) {
                viewModelScope.launch { runCatching { joinRecipients() } }
            }
        } catch (e: Exception) {
            _folderLost.value = e is StoreException.FolderUnavailable
            // Never keep showing the store that was open before: after a
            // failed switch its entries would look like this store's.
            store = null
            git = null
            _entries.value = emptyList()
            _syncStatus.value = null
            _storeKeys.value = null
            _lastError.value = e.ponyMessage(context)
            _openError.value = e.ponyMessage(context)
            _storeLoaded.value = true
        }
    }

    /**
     * 3.0: keep this format's store in a folder the person picked. The
     * caller has already taken the persistable permission for [treeUri].
     * The app-storage store is left where it is; switching back shows it
     * again.
     */
    suspend fun useFolder(treeUri: Uri, asFormat: StoreFormat = format) {
        // A folder that already holds a store of the other format is
        // opened as what it is.
        if (asFormat != format) {
            StorePaths.setFormat(context, asFormat)
            format = asFormat
            engine = EngineProvider.engine(context, asFormat)
        }
        val previous = StorePaths.location(context, format)
        StorePaths.setLocation(context, format, StoreLocation.Folder(treeUri))
        if (previous is StoreLocation.Folder && previous.treeUri != treeUri) release(previous.treeUri)
        store = null
        openStore()
    }

    /** What a picked folder holds; see [FolderProbe]. Throws when it cannot be read. */
    suspend fun probeFolder(treeUri: Uri): FolderProbe.Kind = withContext(Dispatchers.IO) {
        FolderProbe.inspect(SafStoreFs(context, treeUri))
    }

    /**
     * How many entries [forFormat]'s store holds in app storage, when app
     * storage is where it lives now: what a move into a folder would
     * carry. Zero when that format is already a folder store.
     */
    suspend fun movableEntryCount(forFormat: StoreFormat): Int = withContext(Dispatchers.IO) {
        if (StorePaths.location(context, forFormat) !is StoreLocation.AppStorage) return@withContext 0
        runCatching {
            val root = StorePaths.storeRoot(context, forFormat)
            if (root.isDirectory) PassStore.open(root.absolutePath, forFormat).entries().size else 0
        }.getOrDefault(0)
    }

    /**
     * 3.0: move [asFormat]'s store from app storage into the folder. The
     * files are copied and checked ([StoreMove.copy]); only then does the
     * store switch to the folder, and only after it opened there is the
     * app-storage copy removed, `.git` included: a folder store has no
     * git, so the history and the remote setting do not come along.
     */
    suspend fun moveIntoFolder(treeUri: Uri, asFormat: StoreFormat): StoreMove.Outcome {
        val root = StorePaths.storeRoot(context, asFormat)
        val outcome = withContext(Dispatchers.IO) {
            StoreMove.copy(FileStoreFs(root), SafStoreFs(context, treeUri))
        }
        if (outcome is StoreMove.Outcome.Done) {
            useFolder(treeUri, asFormat)
            if (store != null) withContext(Dispatchers.IO) { root.deleteRecursively() }
        }
        return outcome
    }

    /**
     * 3.0: copy the folder store into app storage and use that from now
     * on. The folder keeps its copy: deleting it here would delete it on
     * every device the folder syncs to.
     */
    suspend fun moveToAppStorage(): StoreMove.Outcome {
        val folder = _storeLocation.value as? StoreLocation.Folder
            ?: return StoreMove.Outcome.Failed("this store is not in a folder")
        val root = StorePaths.storeRoot(context, format)
        val outcome = withContext(Dispatchers.IO) {
            root.mkdirs()
            StoreMove.copy(SafStoreFs(context, folder.treeUri), FileStoreFs(root))
        }
        if (outcome is StoreMove.Outcome.Done) useAppStorage()
        return outcome
    }

    /** Give back a picked folder's permission when it was not used after all. */
    fun forgetFolder(treeUri: Uri) {
        val inUse = listOf(StoreFormat.PASS, StoreFormat.PASSAGE).any {
            (StorePaths.location(context, it) as? StoreLocation.Folder)?.treeUri == treeUri
        }
        if (!inUse) release(treeUri)
    }

    /**
     * 3.0: stop using the folder and go back to the store in app storage.
     * The permission is given up and the folder's files are not touched.
     */
    suspend fun useAppStorage() {
        val previous = _storeLocation.value
        StorePaths.setLocation(context, format, StoreLocation.AppStorage)
        if (previous is StoreLocation.Folder) release(previous.treeUri)
        store = null
        openStore()
    }

    private fun release(treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** The pass store's `.gpg-id` text, or null when there is none. */
    fun gpgIdText(): String? =
        if (format == StoreFormat.PASS) runCatching { store?.rootRecipients() }.getOrNull() else null

    /**
     * 3.0: change where the pass store's OpenPGP keys are kept, and open
     * the store again with the engine for that source.
     */
    suspend fun useKeySource(source: PgpKeySource) {
        ProviderConnection.disconnect(context)
        PgpKeySourceStore.set(context, source)
        openStore()
    }

    /** Where the pass store's OpenPGP keys are kept right now. */
    fun keySource(): PgpKeySource = PgpKeySourceStore.current(context)

    /** [readEntry] without the catch, for callers that handle the failure themselves. */
    fun readEntryOrThrow(name: String): ByteArray {
        val current = store ?: throw StoreException.NoStore()
        return current.readEntry(name, engine)
    }

    /**
     * Write the pass store's first `.gpg-id`, wherever that store lives.
     * False, with nothing written, when it already has one.
     */
    fun initializePassStore(gpgIdText: String): Boolean {
        val passStore = StorePaths.openStore(context, StoreFormat.PASS)
        if (passStore.rootRecipients() != null) return false
        passStore.setRootRecipients(gpgIdText)
        openStore()
        return true
    }

    /**
     * 2.0 rc3: re-read which entries hold passkeys (the provider lists
     * candidates from this index before anything is unlocked). Runs on
     * every store open, which covers a clone, a format switch and a key
     * import (entries that only just became readable), and after a sync
     * brings in changes. Only Android 14+ has a passkey provider, so older
     * devices skip the decrypt work entirely.
     */
    private fun rebuildPasskeyIndex() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val current = store ?: return
        val currentFormat = format
        val currentEngine = engine
        viewModelScope.launch(Dispatchers.IO) {
            // 3.0: with a provider, never pop its screens up from here.
            runCatching {
                ProviderPrompt.quietly { PasskeyIndex.rebuild(context, currentFormat, current, currentEngine) }
            }
        }
    }

    /**
     * Delete one passkey from an entry, with one commit; returns the new
     * content for the screen showing it.
     */
    suspend fun removePasskey(name: String, content: ByteArray, credentialId: ByteArray): ByteArray {
        val current = store ?: throw StoreException.NoStore()
        val updated = withContext(Dispatchers.IO) {
            val next = entryRemovePasskey(content, credentialId)
            current.writeEntry(name, next, engine)
            runCatching {
                git?.commitPaths(listOf(StorePaths.entryFileName(name, format)), commitMessagePasskeyRemove(name))
            }
            PasskeyIndex.updateEntry(context, format, name, next)
            next
        }
        refresh()
        return updated
    }

    fun refresh() {
        val current = store ?: return
        try {
            _entries.value = current.entries()
            _storeLoaded.value = true
            // status() can throw GitException (e.g. a genuinely broken
            // repo); that's a Sync-screen concern, not a reason to
            // surface an error here or drop the freshly-read entries.
            _syncStatus.value = runCatching { git?.status() }.getOrNull()
            _storeKeys.value = if (format != StoreFormat.PASSAGE) null else runCatching {
                StoreKeys(
                    postQuantum = DeviceKeys.postQuantum(context, current),
                    blocked = DeviceKeys.listedType(current) == StoreKeyType.MIXED,
                    canChoose = _entries.value.isEmpty() && DeviceKeys.canChooseKind(context, current),
                )
            }.getOrNull()
        } catch (e: Exception) {
            _lastError.value = e.ponyMessage(context)
            // Couldn't list the store: show it as an error, not "loading".
            if (!_storeLoaded.value) _openError.value = e.ponyMessage(context)
            // 3.0: the folder went away while the store was open. Stop
            // showing entries that can no longer be read.
            if (e is StoreException.FolderUnavailable) {
                _entries.value = emptyList()
                _folderLost.value = true
                _openError.value = e.ponyMessage(context)
            }
            _storeLoaded.value = true
        }
    }

    /**
     * 3.0: look at a folder store again, because whatever syncs the
     * folder may have changed it while the app was away. Called when the
     * app comes back to the front and from the list's Reload. Nothing
     * watches the folder; the Storage Access Framework has no dependable
     * change notice. A no-op for a store in app storage.
     */
    suspend fun reloadFolderStore() {
        if (_storeLocation.value !is StoreLocation.Folder) return
        // The first open has not finished; it does all of this itself.
        if (!_storeLoaded.value) return
        if (store == null) {
            openStore()
            return
        }
        refresh()
        if (_folderLost.value) return
        rebuildPasskeyIndex()
        if (format == StoreFormat.PASSAGE) runCatching { joinRecipients() }
    }

    /** 3.0: the entry this one is a sync tool's conflict copy of, or null. */
    fun conflictCopyOf(name: String): ConflictCopy? = runCatching { conflictOf(name) }.getOrNull()

    /**
     * 3.0: keep a conflict copy as the entry it is a copy of. The copy is
     * read first, so an unreadable one changes nothing; then it is saved
     * under the original name and the copy is removed.
     */
    fun keepConflictCopy(copy: String): Boolean {
        val current = store ?: return false
        val original = conflictCopyOf(copy)?.original ?: return false
        return try {
            val content = current.readEntry(copy, engine)
            current.writeEntry(original, content, engine)
            current.removeEntry(copy)
            runCatching {
                git?.commitPaths(
                    listOf(StorePaths.entryFileName(original, format), StorePaths.entryFileName(copy, format)),
                    commitMessageEdit(original)
                )
            }
            runCatching {
                PasskeyIndex.updateEntry(context, format, original, content)
                PasskeyIndex.updateEntry(context, format, copy, null)
            }
            content.fill(0)
            refresh()
            true
        } catch (e: Exception) {
            _lastError.value = e.ponyMessage(context)
            false
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
    fun saveEntry(name: String, content: ByteArray, isNew: Boolean): Boolean {
        // False (with lastError set) when nothing was written, so the Add
        // and Edit screens stay open and say why instead of closing on a
        // save that never happened (no .gpg-id yet, no usable key, ...).
        val current = store ?: run {
            _lastError.value = StoreException.NoStore().ponyMessage(context)
            return false
        }
        return try {
            // 3.0: a new entry goes through the core's addEntry, which
            // refuses a name that is taken instead of replacing what is
            // there. Replacing is an edit, and is committed as one.
            if (isNew) current.addEntry(name, content, engine) else current.writeEntry(name, content, engine)
            val message = if (isNew) commitMessageAdd(name) else commitMessageEdit(name)
            runCatching { git?.commitPaths(listOf(StorePaths.entryFileName(name, format)), message) }
            runCatching { PasskeyIndex.updateEntry(context, format, name, content) }
            refresh()
            true
        } catch (e: Exception) {
            _lastError.value = when (e) {
                is StoreException.EntryExists -> context.getString(R.string.error_entry_exists, name)
                else -> e.ponyMessage(context)
            }
            false
        }
    }

    /** What [addEntry] did; the Add screen asks before replacing on [EXISTS]. */
    enum class AddResult { SAVED, EXISTS, FAILED }

    /**
     * 3.0: save a new entry, telling a taken name apart from a failure so
     * the Add screen can ask "Replace or Cancel" with everything typed
     * still in place. On [AddResult.EXISTS] nothing was written.
     */
    fun addEntry(name: String, content: ByteArray): AddResult {
        // Looked up first only to pick the answer; the core checks again
        // inside addEntry, so a name taken in between still cannot be
        // replaced.
        if (hasEntry(name)) return AddResult.EXISTS
        return if (saveEntry(name, content, isNew = true)) AddResult.SAVED else AddResult.FAILED
    }

    /**
     * Move/rename. Core semantics: passage always re-encrypts the moved
     * entry; pass re-encrypts only when the resolved key set differs.
     * One commit covers both the old and new paths, matching iOS's
     * single commitPaths([from, to]) call.
     */
    fun moveEntry(from: String, to: String): Boolean {
        val current = store ?: return false
        // 3.0: a move onto a name that is taken would replace that entry.
        if (current.hasEntry(to)) {
            _lastError.value = context.getString(R.string.error_entry_exists, to)
            return false
        }
        return try {
            current.moveEntry(from, to, engine)
            runCatching {
                git?.commitPaths(
                    listOf(StorePaths.entryFileName(from, format), StorePaths.entryFileName(to, format)),
                    commitMessageRename(from, to)
                )
            }
            runCatching { PasskeyIndex.renameEntry(context, format, from, to) }
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
            runCatching { PasskeyIndex.updateEntry(context, format, name, null) }
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
            val handle = git ?: throw GitException.NoRepository()
            handle.setSsh(SshKeyStore.config(context))
            handle.push()
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
     * Pin an SSH host's key after the user confirmed its fingerprint
     * (from `GitException.UnknownHostKey`); the caller then retries.
     */
    fun trustHost(host: String, key: HostKey) {
        SshKeyStore.trust(context, host, key)
    }

    /**
     * Fetch + rebase (or fast-forward, or nothing). [resolver] is asked
     * once per conflicted file, on this IO-dispatcher thread -- see
     * ui/sync/BlockingResolver, which bridges that blocking callback to
     * the Sync screen's conflict dialog.
     */
    suspend fun syncNow(resolver: ConflictResolver): SyncOutcome {
        val outcome = withContext(Dispatchers.IO) {
            val handle = git ?: throw GitException.NoRepository()
            handle.setSsh(SshKeyStore.config(context))
            handle.sync(resolver)
        }
        joinRecipients()
        refresh()
        if (outcome !is SyncOutcome.UpToDate) rebuildPasskeyIndex()
        return outcome
    }

    /**
     * 2.0 rc4: list this device in `.age-recipients` (committing and
     * pushing when that changed anything), then look for keys other
     * devices added. A failed push leaves the commit for the next Push;
     * the sync itself already succeeded.
     */
    private suspend fun joinRecipients() {
        val current = store ?: return
        withContext(Dispatchers.IO) {
            if (runCatching { DeviceKeys.join(context, format, current, git) }.getOrDefault(false)) {
                runCatching {
                    git?.setSsh(SshKeyStore.config(context))
                    git?.push()
                }
            }
            reloadAgeEngine()
            _pendingDeviceKeys.value = DeviceKeys.pending(context, format, current)
        }
    }

    /**
     * 3.0: joining a store can generate an identity of the store's kind,
     * so the engine is re-read from the identities file afterwards.
     */
    private fun reloadAgeEngine() {
        if (format == StoreFormat.PASSAGE) engine = EngineProvider.engine(context, format)
    }

    /**
     * 3.0: make the empty passage store post-quantum or classic. A no-op
     * once the store has entries or lists another device's key; changing
     * an existing store is a migration, which is its own flow.
     */
    suspend fun chooseStoreKeys(postQuantum: Boolean) {
        val current = store ?: return
        if (format != StoreFormat.PASSAGE || _storeKeys.value?.canChoose != true) return
        withContext(Dispatchers.IO) {
            DeviceKeys.chooseKind(context, current, git, postQuantum)
            reloadAgeEngine()
        }
        refresh()
    }

    /** 3.0: true when this device has a post-quantum age identity. */
    fun hasPostQuantumKey(): Boolean =
        AgeIdentityStore.recipients(context, postQuantum = true).isNotEmpty()

    /**
     * 3.0: give this device a post-quantum identity ahead of moving the
     * store, so the keys backup made before the move already holds it.
     * The store itself is not touched.
     */
    suspend fun createPostQuantumKey() {
        withContext(Dispatchers.IO) {
            AgeIdentityStore.ensureIdentity(context, postQuantum = true)
            reloadAgeEngine()
        }
    }

    /** 3.0: folders that stop a move; see [DeviceKeys.classicFolders]. */
    suspend fun postQuantumBlockers(): List<String> = withContext(Dispatchers.IO) {
        if (format != StoreFormat.PASSAGE) emptyList()
        else runCatching { DeviceKeys.classicFolders(context) }.getOrDefault(emptyList())
    }

    /** 3.0: entries not yet on post-quantum keys; see [AgeHeader.classicEntries]. */
    suspend fun classicEntryCount(): Int = withContext(Dispatchers.IO) {
        if (format != StoreFormat.PASSAGE) 0
        else runCatching {
            AgeHeader.classicEntries(StorePaths.storeFs(context, StoreFormat.PASSAGE))
        }.getOrDefault(0)
    }

    /**
     * 3.0: move the open passage store to post-quantum keys, or finish a
     * move; see [DeviceKeys.moveToPostQuantum]. Pushes when it can; a
     * failed push leaves the commit for the next Push.
     */
    suspend fun moveToPostQuantum(): ReencryptReport {
        val current = store ?: throw StoreException.NoStore()
        if (format != StoreFormat.PASSAGE) throw StoreException.NoStore()
        val report = withContext(Dispatchers.IO) {
            reloadAgeEngine()
            val r = DeviceKeys.moveToPostQuantum(context, current, engine, git)
            runCatching {
                git?.setSsh(SshKeyStore.config(context))
                git?.push()
            }
            _pendingDeviceKeys.value = DeviceKeys.pending(context, format, current)
            r
        }
        refresh()
        return report
    }

    /** True for a pending key that can only be removed; see [DeviceKeys.outdated]. */
    fun isOutdatedDeviceKey(key: String): Boolean =
        store?.let { runCatching { DeviceKeys.outdated(it, key) }.getOrDefault(false) } ?: false

    /** The person recognized [key]: re-encrypt what this device can read, push. */
    suspend fun approveDeviceKey(key: String): ReencryptReport {
        val current = store ?: throw StoreException.NoStore()
        val report = withContext(Dispatchers.IO) {
            val r = DeviceKeys.approve(context, format, current, engine, git, key)
            if (r.rewritten.isNotEmpty()) {
                git?.setSsh(SshKeyStore.config(context))
                git?.push()
            }
            _pendingDeviceKeys.value = DeviceKeys.pending(context, format, current)
            r
        }
        refresh()
        return report
    }

    /** The person did not recognize [key]: take it out of the file, push. */
    suspend fun removeDeviceKey(key: String) {
        val current = store ?: throw StoreException.NoStore()
        withContext(Dispatchers.IO) {
            DeviceKeys.remove(format, current, git, key)
            git?.setSsh(SshKeyStore.config(context))
            git?.push()
            _pendingDeviceKeys.value = DeviceKeys.pending(context, format, current)
        }
        refresh()
    }

    /** "Later": stop asking until the next sync. */
    fun deferDeviceKeys() {
        _pendingDeviceKeys.value = emptyList()
    }

    /** Git works on app storage only; the Sync screen hides it for a folder store. */
    private fun requireAppStorage() {
        if (_storeLocation.value !is StoreLocation.AppStorage) {
            throw GitException.Other("a folder store syncs through its folder, not git")
        }
    }

    /**
     * Publish the current local store to an empty remote: init (commits
     * everything with CLI-style messages) if this store has no repo
     * yet, then set the remote and push. Matches iOS's
     * SyncView.publishSection.
     */
    suspend fun publishStore(url: String) {
        requireAppStorage()
        withContext(Dispatchers.IO) {
            val handle = git ?: GitSync.init(StorePaths.storeRoot(context, format).absolutePath, format)
            store?.let { DeviceKeys.join(context, format, it, handle) }
            reloadAgeEngine()
            handle.setRemote(url)
            handle.setSsh(SshKeyStore.config(context))
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
        requireAppStorage()
        val root = StorePaths.storeRoot(context, format)
        val scratch = StorePaths.cloneScratchDir(context)
        withContext(Dispatchers.IO) {
            scratch.deleteRecursively()
            GitSync.cloneWithSsh(url, scratch.absolutePath, null, SshKeyStore.config(context)).close()
            root.deleteRecursively()
            if (!scratch.renameTo(root)) {
                throw GitException.Other("couldn't move the cloned store into place")
            }
        }
        openStore()
        store?.let { cloned ->
            withContext(Dispatchers.IO) { DeviceKeys.baseline(context, format, cloned) }
        }
        joinRecipients()
        refresh()
    }

    /**
     * Import an age identities file's bytes for the passage store, then
     * rebuild the engine and reopen so entries that were waiting on the
     * right key decrypt. [passphrase] is only needed for a
     * passphrase-protected file (see [AgeIdentityStore.isPassphraseProtected]).
     * Surfaces a bad import (no usable identity, wrong passphrase) to the
     * caller rather than swallowing it, like the Sync actions -- the user
     * asked to add a key and wants to know if it didn't take.
     */
    suspend fun importAgeIdentitiesFile(bytes: ByteArray, passphrase: String?): AgeIdentityStore.ImportResult {
        val result = withContext(Dispatchers.IO) {
            AgeIdentityStore.importIdentitiesFile(context, bytes, passphrase)
        }
        engine = EngineProvider.engine(context, format)
        openStore()
        return result
    }

    /**
     * Preview of what a re-encrypt of [subpath] ("" = entire store)
     * would rewrite. Read-only, and -- like Sync's actions -- surfaces
     * failures to the caller rather than swallowing them into
     * lastError: this is a deliberate maintenance action the user wants
     * to know failed. Matches iOS's ReencryptView.preview().
     */
    fun reencryptPreview(subpath: String): List<String> = ReencryptOps.preview(store, subpath)

    /**
     * Re-encrypt [subpath] to its currently-resolved recipients and
     * best-effort commit the rewritten files -- matches iOS: a failed
     * commit here does not undo or fail the re-encrypt itself, which by
     * this point already succeeded. Returns the rewritten entry names.
     */
    suspend fun reencryptNow(subpath: String): List<String> {
        val current = store ?: throw StoreException.NoStore()
        val result = withContext(Dispatchers.IO) {
            ReencryptOps.run(current, subpath, engine, format)
        }
        if (result.files.isNotEmpty()) {
            runCatching { git?.commitPaths(result.files, commitMessageReencrypt(subpath)) }
        }
        refresh()
        return result.entries
    }

    /** True when an entry with exactly this name exists in the open store. */
    fun hasEntry(name: String): Boolean = store?.hasEntry(name) ?: false

    /**
     * Next HOTP code for an entry, pass-otp style: the counter is advanced
     * and the entry written and committed ("Increment HOTP counter for
     * <name>.") before the code is returned, so a code is never shown
     * whose counter was not saved. Throws on any failure; the caller shows
     * it and shows no code.
     */
    suspend fun advanceHotp(name: String, content: ByteArray): HotpAdvance {
        val current = store ?: throw StoreException.NoStore()
        val advance = withContext(Dispatchers.IO) {
            val next = entryHotpNext(content)
            current.writeEntry(name, next.content, engine)
            runCatching {
                git?.commitPaths(listOf(StorePaths.entryFileName(name, format)), commitMessageHotpIncrement(name))
            }
            next
        }
        refresh()
        return advance
    }

    /**
     * Write OTP-only entries (the URI as the whole entry, what
     * `pass otp insert` writes), one commit each with pass-otp's message.
     * Returns the names written; stops at the first failure and rethrows
     * it after refreshing, so the caller can say how far it got.
     */
    suspend fun addOtpEntries(entries: List<Pair<String, String>>): List<String> {
        val current = store ?: throw StoreException.NoStore()
        val written = mutableListOf<String>()
        try {
            withContext(Dispatchers.IO) {
                for ((name, uri) in entries) {
                    current.writeEntry(name, "$uri\n".toByteArray(Charsets.UTF_8), engine)
                    runCatching {
                        git?.commitPaths(listOf(StorePaths.entryFileName(name, format)), commitMessageOtpAdd(name))
                    }
                    written += name
                }
            }
        } finally {
            refresh()
        }
        return written
    }

    /**
     * An encrypted `.passpony` backup of every key this device holds: the
     * age identities file and each file under pgp-keys/, encrypted to
     * [recoveryCode] (the canonical code; see docs/BACKUP.md in
     * PassPonyCore). Returns the age file bytes for the caller to write.
     */
    suspend fun buildBackup(recoveryCode: String): ByteArray = withContext(Dispatchers.IO) {
        val sections = mutableListOf<BackupSection>()
        AgeIdentityStore.exportText(context)?.let { text ->
            sections += BackupSection(backupKindAgeIdentities(), "", text.toByteArray(Charsets.UTF_8))
        }
        SshKeyStore.load(context)?.let { key ->
            sections += BackupSection(backupKindSshPrivateKey(), "", key.privateKey.toByteArray(Charsets.UTF_8))
        }
        PgpKeyStore.keyDirectory(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                sections += BackupSection(backupKindPgpSecretKey(), file.name, file.readBytes())
            }
        val plaintext = backupEncode(sections)
        try {
            ageEncryptWithPassphrase(plaintext, recoveryCode)
        } finally {
            plaintext.fill(0)
            sections.forEach { it.data.fill(0) }
        }
    }

    /** What a restore changed. */
    data class RestoreResult(
        val identitiesAdded: Int,
        val identitiesPresent: Int,
        val pgpKeysAdded: Int,
        val pgpKeysPresent: Int,
        val unknownSections: Int,
        /** The backup's SSH key became this app's key (it had none). */
        val sshKeyRestored: Boolean = false,
        /**
         * The backup's SSH key differs from the one this app has; the
         * caller asks before [replaceSshKey]. Nothing was changed yet.
         */
        val sshKeyToConfirm: SshKey? = null,
    )

    /**
     * Restore a `.passpony` backup with [recoveryCode] (canonical). Age
     * identities are merged (never replacing what is here); PGP key files
     * are added unless an identical file is already present, and a
     * different file under the same name is kept beside it rather than
     * overwritten. Wrong code: `CryptoException.DecryptionFailed`, nothing
     * written. A damaged container: `BackupException`, nothing written.
     */
    suspend fun restoreBackup(bytes: ByteArray, recoveryCode: String): RestoreResult {
        val result = withContext(Dispatchers.IO) {
            val plaintext = ageDecryptWithPassphrase(bytes, recoveryCode)
            val sections = try {
                backupDecode(plaintext)
            } finally {
                plaintext.fill(0)
            }
            var idAdded = 0
            var idPresent = 0
            var pgpAdded = 0
            var pgpPresent = 0
            var unknown = 0
            var sshRestored = false
            var sshToConfirm: SshKey? = null
            try {
                for (section in sections) {
                    when (section.kind) {
                        backupKindAgeIdentities() -> {
                            val r = AgeIdentityStore.importIdentities(context, section.data.toString(Charsets.UTF_8))
                            idAdded += r.added
                            idPresent += r.alreadyPresent
                        }
                        backupKindPgpSecretKey() -> {
                            if (restorePgpKey(section.name, section.data)) pgpAdded++ else pgpPresent++
                        }
                        backupKindSshPrivateKey() -> {
                            val restored = runCatching { sshImportKey(section.data, null) }.getOrNull()
                            val current = SshKeyStore.load(context)
                            when {
                                restored == null -> unknown++
                                current == null && !SshKeyStore.hasKey(context) -> {
                                    SshKeyStore.save(context, restored)
                                    sshRestored = true
                                }
                                current?.fingerprint == restored.fingerprint -> {}
                                else -> sshToConfirm = restored
                            }
                        }
                        else -> unknown++
                    }
                }
            } finally {
                sections.forEach { it.data.fill(0) }
            }
            RestoreResult(idAdded, idPresent, pgpAdded, pgpPresent, unknown, sshRestored, sshToConfirm)
        }
        if (result.identitiesAdded == 0 &&
            result.identitiesPresent > 0 &&
            result.identitiesPresent == AgeIdentityStore.identityCount(context)
        ) {
            // The backup holds exactly the identities this device has, so
            // a backup covering this device exists.
            KeyBackupState.markBackedUp(context)
        }
        engine = EngineProvider.engine(context, format)
        openStore()
        return result
    }

    /** The user chose the backup's SSH key over the one this app had. */
    fun replaceSshKey(key: SshKey) {
        SshKeyStore.save(context, key)
    }

    /** True when written, false when an identical file was already there. */
    private fun restorePgpKey(name: String, data: ByteArray): Boolean {
        val dir = PgpKeyStore.keyDirectory(context)
        val safeName = File(name.ifBlank { "restored-key.asc" }).name
        var target = File(dir, safeName)
        var n = 2
        while (target.exists()) {
            if (target.readBytes().contentEquals(data)) return false
            target = File(dir, "${safeName.substringBeforeLast('.')}-restored-$n" +
                (if (safeName.contains('.')) ".${safeName.substringAfterLast('.')}" else ""))
            n++
        }
        target.writeBytes(data)
        return true
    }

    /** The keys were written out (backup or plain export) by the user. */
    suspend fun markKeysBackedUp() {
        KeyBackupState.markBackedUp(context)
    }

    private fun seedDemoStore(store: PassStore) {
        for ((name, content) in DemoSeed.ENTRIES) {
            store.writeEntry(name, content.toByteArray(Charsets.UTF_8), engine)
        }
    }
}
