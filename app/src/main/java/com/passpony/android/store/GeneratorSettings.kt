package com.passpony.android.store

/**
 * The password generator's user-facing settings, as the sheet edits them
 * and as DataStore stores them. Pure and FFI-free so the codec below is a
 * plain JVM unit test; GeneratorPrefs turns a value into the core's
 * GeneratorSpec at the one place the FFI is called.
 *
 * The bounds and the default mirror pass-core's generate.rs (MIN_LENGTH,
 * MAX_LENGTH, GeneratorSpec::pass_default): 25 characters from every
 * class with the full symbol set is exactly what pass's own `generate`
 * does and what every PassPony build before 1.1 did. GeneratorDefaultsTest
 * (instrumented) holds [DEFAULT] to the core's own value.
 */
data class GeneratorSettings(
    val length: Int,
    val lowercase: Boolean,
    val uppercase: Boolean,
    val digits: Boolean,
    val symbols: Symbols,
) {
    /** Which punctuation goes in; [stored] is the DataStore spelling. */
    enum class Symbols(val stored: String) {
        NONE("none"),
        BASIC("basic"),
        FULL("full");

        companion object {
            fun fromStored(value: String?): Symbols? = entries.firstOrNull { it.stored == value }
        }
    }

    /** At least one class, or there is nothing to draw from. */
    val hasAnyClass: Boolean
        get() = lowercase || uppercase || digits || symbols != Symbols.NONE

    val isValid: Boolean
        get() = hasAnyClass && length in MIN_LENGTH..MAX_LENGTH

    companion object {
        const val MIN_LENGTH = 8
        const val MAX_LENGTH = 128
        const val DEFAULT_LENGTH = 25

        /** pass's `generate` defaults, unchanged from 1.0. */
        val DEFAULT = GeneratorSettings(
            length = DEFAULT_LENGTH,
            lowercase = true,
            uppercase = true,
            digits = true,
            symbols = Symbols.FULL,
        )

        fun clampLength(length: Int): Int = length.coerceIn(MIN_LENGTH, MAX_LENGTH)

        /**
         * Rebuild settings from stored values, any of which may be
         * missing (a fresh install, or a key added after the user last
         * saved). A missing value takes the default for that one field;
         * an out-of-range length is clamped; a combination with no class
         * at all (only possible through a corrupted store) falls back to
         * [DEFAULT] whole, so generation never has nothing to draw from.
         */
        fun fromStored(
            length: Int?,
            lowercase: Boolean?,
            uppercase: Boolean?,
            digits: Boolean?,
            symbols: String?,
        ): GeneratorSettings {
            val settings = GeneratorSettings(
                length = clampLength(length ?: DEFAULT.length),
                lowercase = lowercase ?: DEFAULT.lowercase,
                uppercase = uppercase ?: DEFAULT.uppercase,
                digits = digits ?: DEFAULT.digits,
                symbols = Symbols.fromStored(symbols) ?: DEFAULT.symbols,
            )
            return if (settings.hasAnyClass) settings else DEFAULT
        }
    }
}
