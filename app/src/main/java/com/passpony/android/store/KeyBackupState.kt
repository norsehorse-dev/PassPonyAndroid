package com.passpony.android.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Whether this device's age identities have been backed up (2.0): an
 * encrypted `.passpony` backup or a plain identities export written
 * since the identities file last gained an identity. Per-device UI state,
 * never synced and never in the core.
 *
 * Absent means "not backed up", which is also the right answer for
 * someone upgrading from 1.x: the app never offered an export before, so
 * nobody has one made by PassPony.
 */
object KeyBackupState {
    private val BACKED_UP_KEY = booleanPreferencesKey("age_identities_backed_up")

    fun backedUp(context: Context): Flow<Boolean> =
        context.passPonyDataStore.data.map { it[BACKED_UP_KEY] ?: false }

    fun backedUpSnapshot(context: Context): Boolean = runBlocking { backedUp(context).first() }

    suspend fun markBackedUp(context: Context) {
        context.passPonyDataStore.edit { it[BACKED_UP_KEY] = true }
    }

    /** The identities file gained an identity; the last backup no longer covers it. */
    fun markChanged(context: Context) {
        runBlocking { context.passPonyDataStore.edit { it[BACKED_UP_KEY] = false } }
    }
}
