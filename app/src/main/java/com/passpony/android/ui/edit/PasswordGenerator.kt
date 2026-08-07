package com.passpony.android.ui.edit

import java.security.SecureRandom

/**
 * Charset generator matching pass's own defaults: 25 characters from the
 * full 94-character printable-ASCII-minus-space set (letters, digits,
 * symbols). Port of PassPony iOS's AddEntryView.generate(); iOS uses
 * SystemRandomNumberGenerator, Android's platform-secure equivalent is
 * SecureRandom. Diceware and per-store defaults come later (plan section 5).
 */
object PasswordGenerator {
    const val DEFAULT_LENGTH = 25
    const val CHARSET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    private val random = SecureRandom()

    fun generate(length: Int = DEFAULT_LENGTH): String {
        val chars = CharArray(length) { CHARSET[random.nextInt(CHARSET.length)] }
        return String(chars)
    }
}
