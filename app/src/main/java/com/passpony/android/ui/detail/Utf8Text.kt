package com.passpony.android.ui.detail

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Strict UTF-8 decoding for revealing a password or field value: `null`
 * on anything that isn't valid UTF-8, rather than `String(bytes)`'s
 * silent replacement-character mangling. Mirrors PassPony iOS's
 * `String(data:encoding:.utf8)`, a failable initializer that returns nil
 * on invalid input so the caller can fall back to a "binary" placeholder
 * instead of showing corrupted text.
 */
object Utf8Text {
    fun decodeStrict(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
