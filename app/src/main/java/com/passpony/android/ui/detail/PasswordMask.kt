package com.passpony.android.ui.detail

/**
 * Bullet-mask length for a password's raw bytes: at least 8 dots, or the
 * true byte count if longer. Mirrors PassPony iOS's
 * `String(repeating: "\u2022", count: max(8, password.count))` — masking on
 * byte count rather than decoded character count keeps a binary password
 * from needing to be decoded just to hide it.
 */
object PasswordMask {
    private const val MIN_LENGTH = 8
    private const val BULLET = "\u2022"

    fun maskLength(passwordByteCount: Int): Int = maxOf(MIN_LENGTH, passwordByteCount)

    fun mask(passwordByteCount: Int): String = BULLET.repeat(maskLength(passwordByteCount))
}
