package com.passpony.android.ui.edit

/**
 * Assembles a brand-new entry's plaintext exactly as PassPony iOS's
 * AddEntryView.save() does: password line, then an optional
 * `username: ...` line, then an optional `url: ...` line, each only
 * when non-blank. Pure string assembly -- the byte-faithful codec
 * (entrySetField/entrySetPassword) isn't involved in building brand-new
 * content, only in editing existing bytes (see EntryEditState).
 */
object NewEntryContent {
    fun assemble(password: String, username: String, url: String): ByteArray {
        val builder = StringBuilder()
        builder.append(password).append('\n')
        if (username.isNotEmpty()) builder.append("username: ").append(username).append('\n')
        if (url.isNotEmpty()) builder.append("url: ").append(url).append('\n')
        return builder.toString().toByteArray(Charsets.UTF_8)
    }
}
