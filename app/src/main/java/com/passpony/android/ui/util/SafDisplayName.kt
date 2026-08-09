package com.passpony.android.ui.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * A SAF (Storage Access Framework) document URI's display name, for
 * showing/using the picked file's original name after copying its bytes
 * out of the picker. Shared between Settings' OpenPGP key import (P10)
 * and the onboarding "bring your store" slide's key import (P14) --
 * previously duplicated as a private fun in SettingsScreen.kt, extracted
 * here rather than copy-pasted a second time.
 */
fun safDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return cursor.getString(idx)
        }
    }
    return uri.lastPathSegment
}
