package com.passpony.android.autofill

import android.content.Intent
import android.os.Build
import android.os.Parcelable

/**
 * Intent.getParcelableExtra(String) alone is deprecated on API 33+ in
 * favor of the type-checked two-arg overload; this picks whichever is
 * available so every autofill call site (which all read an AutofillId
 * extra) doesn't repeat the version check.
 */
inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
