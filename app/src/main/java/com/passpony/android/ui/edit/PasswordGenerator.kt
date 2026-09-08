package com.passpony.android.ui.edit

import android.content.Context
import com.passpony.android.store.GeneratorPrefs
import com.passpony.android.store.GeneratorSettings
import com.passpony.android.store.toSpec
import uniffi.pass_ffi.generatePassword

/**
 * The Generate button, over the core's generator (pass-core generate.rs)
 * since 1.1: one implementation shared with iOS and the desktop, OS
 * entropy, uniform draws, every enabled class present at least once.
 * The 94-character constant and the SecureRandom loop that lived here
 * before are gone; CoreSmokeTest pins the core's default alphabet to
 * that old constant, character for character.
 *
 * One-tap Generate uses the settings remembered by the generator sheet,
 * or pass's defaults (25 characters, every class) on a fresh install.
 */
object PasswordGenerator {
    fun generate(context: Context): String = generate(GeneratorPrefs.snapshot(context))

    fun generate(settings: GeneratorSettings): String = generatePassword(settings.toSpec())
}
