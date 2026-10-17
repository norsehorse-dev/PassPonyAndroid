package com.passpony.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.passpony.android.BuildConfig
import com.passpony.android.crypto.AgeIdentityStore
import com.passpony.android.crypto.EngineProvider
import com.passpony.android.crypto.PgpKeyStore
import com.passpony.android.store.KeyBackupState
import java.io.File
import com.passpony.android.store.BrowseModel
import com.passpony.android.store.DemoSeed
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
import kotlinx.coroutines.withContext
import uniffi.pass_ffi.ConflictResolver
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.EntryRef
import uniffi.pass_ffi.GitException
import uniffi.pass_ffi.GitSync
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreException
import uniffi.pass_ffi.StoreFormat
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
                        else -> unknown++
                    }
                }
            } finally {
                sections.forEach { it.data.fill(0) }
            }
            RestoreResult(idAdded, idPresent, pgpAdded, pgpPresent, unknown)
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
