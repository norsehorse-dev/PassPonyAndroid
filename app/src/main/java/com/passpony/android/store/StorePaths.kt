package com.passpony.android.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.passpony.android.BuildConfig
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import uniffi.pass_ffi.StoreFormat

private val Context.passPonyDataStore by preferencesDataStore(name = "passpony_prefs")

/**
 * One place for every path decision, mirroring PassPony iOS's
 * StorePaths.swift. Android has no app group; the container is this
 * app's private files directory instead.
 */
object StorePaths {
    private val FORMAT_KEY = stringPreferencesKey("store_format")

    /**
     * P14 will replace this with the real onboarding-completed flag. Until
     * then, demo seeding gates on this same key, defaulting to true in
     * debug builds (so there is something to seed against before
     * onboarding exists) and false in release (where seeding never runs
     * anyway, since it is also gated on BuildConfig.DEBUG at the call
     * site).
     */
    private val DEMO_SEED_GATE_KEY = booleanPreferencesKey("onboarding_completed")

    /** Each format keeps its own store directory so switching is non-destructive. */
    fun storeRoot(context: Context, format: StoreFormat): File {
        val dirName = if (format == StoreFormat.PASSAGE) "store" else "pass-store"
        return File(context.filesDir, dirName)
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
        stored ?: BuildConfig.DEBUG
    }

    fun storeRoot(context: Context): File = storeRoot(context, currentFormatSnapshot(context))
}
