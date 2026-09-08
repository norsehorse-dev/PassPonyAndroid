package com.passpony.android.ui.edit

/**
 * Assembles a brand-new entry's plaintext exactly as PassPony iOS's
 * AddEntryView.save() does: password line, then an optional
 * `username: ...` line, then an optional `url: ...` line, each only
 * when non-blank, then (1.1) the optional `otpauth://` line last, the
 * position `pass otp append` gives it. With no password and a code, the
 * URI is the first line instead, which is exactly the entry
 * `pass otp insert` creates. Pure string assembly -- the byte-faithful
 * codec (entrySetField/entrySetPassword/entrySetOtpauth) isn't involved
 * in building brand-new content, only in editing existing bytes (see
 * EntryEditState).
 */
object NewEntryContent {
    fun assemble(password: String, username: String, url: String, otpauth: String? = null): ByteArray {
        val builder = StringBuilder()
        val otpOnly = password.isEmpty() && otpauth != null
        builder.append(if (otpOnly) otpauth else password).append('\n')
        if (username.isNotEmpty()) builder.append("username: ").append(username).append('\n')
        if (url.isNotEmpty()) builder.append("url: ").append(url).append('\n')
        if (!otpOnly && otpauth != null) builder.append(otpauth).append('\n')
        return builder.toString().toByteArray(Charsets.UTF_8)
    }
}
