package com.passpony.android.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import uniffi.pass_ffi.GeneratorSpec
import uniffi.pass_ffi.SymbolSet

/**
 * The remembered generator settings, per device (not per store: per-store
 * defaults and diceware stay on the later list, see PLAN.md). Lives in the
 * same `passpony_prefs` DataStore as the format and onboarding flags; a
 * second DataStore over the same file would throw at runtime, which is why
 * StorePaths shares its delegate.
 *
 * [snapshot] is the one-tap Generate path (a local, five-key read, same
 * reasoning as StorePaths.currentFormatSnapshot); [save] runs from the
 * sheet's Use button.
 */
object GeneratorPrefs {
    private val LENGTH = intPreferencesKey("generator_length")
    private val LOWERCASE = booleanPreferencesKey("generator_lowercase")
    private val UPPERCASE = booleanPreferencesKey("generator_uppercase")
    private val DIGITS = booleanPreferencesKey("generator_digits")
    private val SYMBOLS = stringPreferencesKey("generator_symbols")

    suspend fun load(context: Context): GeneratorSettings {
        val prefs = context.passPonyDataStore.data.first()
        return GeneratorSettings.fromStored(
            length = prefs[LENGTH],
            lowercase = prefs[LOWERCASE],
            uppercase = prefs[UPPERCASE],
            digits = prefs[DIGITS],
            symbols = prefs[SYMBOLS],
        )
    }

    fun snapshot(context: Context): GeneratorSettings = runBlocking { load(context) }

    suspend fun save(context: Context, settings: GeneratorSettings) {
        context.passPonyDataStore.edit { prefs ->
            prefs[LENGTH] = settings.length
            prefs[LOWERCASE] = settings.lowercase
            prefs[UPPERCASE] = settings.uppercase
            prefs[DIGITS] = settings.digits
            prefs[SYMBOLS] = settings.symbols.stored
        }
    }
}

/** The core's spec for these settings; the only place the two types meet. */
fun GeneratorSettings.toSpec(): GeneratorSpec = GeneratorSpec(
    length = length.toUInt(),
    lowercase = lowercase,
    uppercase = uppercase,
    digits = digits,
    symbols = when (symbols) {
        GeneratorSettings.Symbols.NONE -> SymbolSet.NO_SYMBOLS
        GeneratorSettings.Symbols.BASIC -> SymbolSet.BASIC
        GeneratorSettings.Symbols.FULL -> SymbolSet.FULL
    },
)
