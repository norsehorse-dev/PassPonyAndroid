package com.passpony.android.store

import android.content.Context
import com.passpony.android.R
import uniffi.pass_ffi.CryptoException
import uniffi.pass_ffi.GitException
import uniffi.pass_ffi.StoreException

/**
 * User-facing messages for errors surfaced from the core. FFI error text
 * originates in Rust and stays English (it mirrors the CLI and is the
 * log/fallback form); localization happens here by mapping each error
 * case to a string resource. Reasons that arrive pre-formed from Rust
 * (Crypto, Io, Other, Unavailable) pass through untranslated inside a
 * localized frame. Ports PassPony iOS's ErrorMessages.swift.
 */
fun Throwable.ponyMessage(context: Context): String = when (this) {
    is CryptoException -> ponyMessage(context)
    is GitException -> ponyMessage(context)
    is StoreException -> ponyMessage(context)
    // Unlike iOS's fallback, which shows the raw Swift description, this
    // never surfaces raw exception text: the house rule is no raw
    // exception text with entry names or paths in UI toasts, and an
    // unclassified exception could carry either.
    else -> context.getString(R.string.error_unknown)
}

fun CryptoException.ponyMessage(context: Context): String = when (this) {
    is CryptoException.DecryptionFailed -> context.getString(R.string.error_decryption_failed)
    is CryptoException.EncryptionFailed -> context.getString(R.string.error_encryption_failed)
    is CryptoException.NoUsableKey -> context.getString(R.string.error_no_usable_key)
    is CryptoException.Cancelled -> context.getString(R.string.error_cancelled)
    is CryptoException.Unavailable -> reason
}

fun GitException.ponyMessage(context: Context): String = when (this) {
    is GitException.NoRepository -> context.getString(R.string.error_no_git_repository)
    is GitException.NoRemote -> context.getString(R.string.error_no_remote)
    is GitException.NonFastForward -> context.getString(R.string.error_non_fast_forward)
    is GitException.UpstreamRewritten -> context.getString(R.string.error_upstream_rewritten)
    is GitException.DirtyWorkdir -> context.getString(R.string.error_dirty_workdir)
    is GitException.Other -> context.getString(R.string.error_git_other, reason)
}

fun StoreException.ponyMessage(context: Context): String = when (this) {
    is StoreException.NoStore -> context.getString(R.string.error_no_store)
    is StoreException.NotInStore -> context.getString(R.string.error_not_in_store)
    is StoreException.SneakyPath -> context.getString(R.string.error_invalid_path)
    is StoreException.Crypto -> context.getString(R.string.error_store_crypto, reason)
    is StoreException.Io -> context.getString(R.string.error_store_io, reason)
}
