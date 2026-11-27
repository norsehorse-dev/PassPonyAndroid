package com.passpony.android.store

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.StoreFs

// Shared with GeneratorPrefs: DataStore allows exactly one instance per
// file, so every preference in passpony_prefs goes through this delegate.
internal val Context.passPonyDataStore by preferencesDataStore(name = "passpony_prefs")

/**
 * One place for every path decision, mirroring PassPony iOS's
 * StorePaths.swift. Android has no app group; the container is this
 * app's private files directory instead.
 */
object StorePaths {
    private val FORMAT_KEY = stringPreferencesKey("store_format")

    /**
     * Real onboarding-completed flag (P14): false until the carousel's
     * Skip/Get Started finishes it, true forever after (Settings' "Show
     * welcome tour again" is the only way back to false). Demo seeding
     * gates on this same key so the import/try-pass slides see a
     * genuinely empty store -- see AppViewModel.openStore().
     */
    private val DEMO_SEED_GATE_KEY = booleanPreferencesKey("onboarding_completed")

    /** Each format keeps its own store directory so switching is non-destructive. */
    fun storeRoot(context: Context, format: StoreFormat): File {
        val dirName = if (format == StoreFormat.PASSAGE) "store" else "pass-store"
        return File(context.filesDir, dirName)
    }

    private fun folderKey(format: StoreFormat) =
        stringPreferencesKey(if (format == StoreFormat.PASSAGE) "store_folder_passage" else "store_folder_pass")

    /**
     * 3.0: where this format's store lives. App storage unless the
     * person picked a folder for it. A blocking one-key read, like
     * [currentFormatSnapshot].
     */
    fun location(context: Context, format: StoreFormat): StoreLocation = runBlocking {
        val uri = context.passPonyDataStore.data.map { it[folderKey(format)] }.first()
        if (uri.isNullOrEmpty()) StoreLocation.AppStorage else StoreLocation.Folder(Uri.parse(uri))
    }

    suspend fun setLocation(context: Context, format: StoreFormat, location: StoreLocation) {
        context.passPonyDataStore.edit { prefs ->
            when (location) {
                is StoreLocation.AppStorage -> prefs.remove(folderKey(format))
                is StoreLocation.Folder -> prefs[folderKey(format)] = location.treeUri.toString()
            }
        }
    }

    /**
     * 3.0: open this format's store wherever it lives. Every caller that
     * needs the store goes through here (the app, autofill, the passkey
     * provider), so a folder store is a folder store to all of them.
     */
    fun openStore(context: Context, format: StoreFormat): PassStore =
        when (val location = location(context, format)) {
            is StoreLocation.AppStorage -> {
                val root = storeRoot(context, format)
                root.mkdirs()
                PassStore.open(root.absolutePath, format)
            }
            is StoreLocation.Folder -> PassStore.openWithFs(SafStoreFs(context, location.treeUri), format)
        }

    /**
     * 3.0: the store's files, for Kotlin code that looks at them
     * directly instead of through [PassStore]. See [FileStoreFs].
     */
    fun storeFs(context: Context, format: StoreFormat): StoreFs =
        when (val location = location(context, format)) {
            is StoreLocation.AppStorage -> FileStoreFs(storeRoot(context, format))
            is StoreLocation.Folder -> SafStoreFs(context, location.treeUri)
        }

    suspend fun currentFormat(context: Context): StoreFormat {
        val stored = context.passPonyDataStore.data.map { it[FORMAT_KEY] }.first()
        return if (stored == "pass") StoreFormat.PASS else StoreFormat.PASSAGE
    }

    suspend fun setFormat(context: Context, format: StoreFormat) {
        context.passPonyDataStore.edit { prefs ->
            prefs[FORMAT_KEY] = if (format == StoreFormat.PASS) "pass" else "passage"
        }
    }

    /**
     * Blocking snapshot for startup, where a suspend read would complicate
     * call sites for no real benefit: this is a single one-key preference
     * read on local storage, not a network call.
     */
    fun currentFormatSnapshot(context: Context): StoreFormat = runBlocking {
        currentFormat(context)
    }

    fun onboardingCompletedSnapshot(context: Context): Boolean = runBlocking {
        val stored = context.passPonyDataStore.data.map { it[DEMO_SEED_GATE_KEY] }.first()
        stored ?: false
    }

    /** Also called by MainActivity when the onboarding carousel finishes,
     * and by Settings' "Show welcome tour again" to reset it. */
    suspend fun setOnboardingCompleted(context: Context, completed: Boolean) {
        context.passPonyDataStore.edit { prefs -> prefs[DEMO_SEED_GATE_KEY] = completed }
    }

    fun storeRoot(context: Context): File = storeRoot(context, currentFormatSnapshot(context))

    /**
     * Store-relative filename for a git commitPaths call: `name` plus
     * the format's extension. pass-core exposes StoreFormat.entry_extension()
     * on the Rust side only; this is the Kotlin-side equivalent used
     * wherever a path (not just a display name) is needed.
     */
    fun entryFileName(name: String, format: StoreFormat): String {
        val extension = if (format == StoreFormat.PASSAGE) "age" else "gpg"
        return "$name.$extension"
    }

    /**
     * Scratch landing spot for Sync's clone-then-swap flow (P09): a
     * fresh clone lands here first, so a failed clone never touches the
     * real store -- only on success does it replace [storeRoot]. One
     * scratch dir shared by both formats is fine; a clone always
     * finishes (success and swap, or failure and cleanup) before
     * another can start.
     */
    fun cloneScratchDir(context: Context): File = File(context.filesDir, "clone-tmp")
}
