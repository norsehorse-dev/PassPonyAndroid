package com.passpony.android.store

import android.content.Context
import com.passpony.android.R
import uniffi.pass_ffi.BackupException
import uniffi.pass_ffi.CryptoException
import uniffi.pass_ffi.GenerateException
import uniffi.pass_ffi.GitException
import uniffi.pass_ffi.MigrationException
import uniffi.pass_ffi.PasskeyException
import uniffi.pass_ffi.SshKeyException
import uniffi.pass_ffi.StoreException
import uniffi.pass_ffi.TotpException

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
    is TotpException -> ponyMessage(context)
    is GenerateException -> ponyMessage(context)
    is BackupException -> ponyMessage(context)
    is MigrationException -> ponyMessage(context)
    is SshKeyException -> ponyMessage(context)
    is PasskeyException -> context.getString(R.string.error_passkey)
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
    is GitException.UnknownHostKey -> context.getString(R.string.error_ssh_unknown_host, host)
    is GitException.HostKeyChanged -> context.getString(R.string.error_ssh_host_changed, host)
    is GitException.NoHostKey -> context.getString(R.string.error_ssh_no_host_key)
    is GitException.SshKeyMissing -> context.getString(R.string.error_ssh_key_missing)
    is GitException.SshAuthFailed -> context.getString(R.string.error_ssh_auth_failed)
    is GitException.Other -> context.getString(R.string.error_git_other, reason)
}

/** An SSH key file that could not be imported, or a key that could not be made. */
fun SshKeyException.ponyMessage(context: Context): String = when (this) {
    is SshKeyException.NeedsPassphrase -> context.getString(R.string.error_sshkey_needs_passphrase)
    is SshKeyException.WrongPassphrase -> context.getString(R.string.ssh_passphrase_wrong)
    is SshKeyException.LegacyPem -> context.getString(R.string.error_sshkey_legacy_pem)
    is SshKeyException.PublicKey -> context.getString(R.string.error_sshkey_public)
    is SshKeyException.NotAKey -> context.getString(R.string.error_sshkey_not_a_key)
    is SshKeyException.Unsupported -> context.getString(R.string.error_sshkey_unsupported, algorithm)
    is SshKeyException.Random -> context.getString(R.string.error_sshkey_random)
}

fun StoreException.ponyMessage(context: Context): String = when (this) {
    is StoreException.NoStore -> context.getString(R.string.error_no_store)
    is StoreException.NotInStore -> context.getString(R.string.error_not_in_store)
    is StoreException.SneakyPath -> context.getString(R.string.error_invalid_path)
    is StoreException.Crypto -> context.getString(R.string.error_store_crypto, reason)
    is StoreException.Io -> context.getString(R.string.error_store_io, reason)
}

/**
 * Why a pasted, scanned, or typed one-time code was rejected. The
 * variants never carry the secret; BadAlgorithm's name and BadNumber's
 * detail are the offending parameter, which is safe to show.
 */
fun TotpException.ponyMessage(context: Context): String = when (this) {
    is TotpException.NotTotpUri -> context.getString(R.string.error_totp_not_totp_uri)
    is TotpException.MissingSecret -> context.getString(R.string.error_totp_missing_secret)
    is TotpException.BadSecret -> context.getString(R.string.error_totp_bad_secret)
    is TotpException.BadAlgorithm -> context.getString(R.string.error_totp_bad_algorithm, name)
    is TotpException.BadNumber -> context.getString(R.string.error_totp_bad_number, detail)
    is TotpException.NotHotpUri -> context.getString(R.string.error_totp_not_totp_uri)
    is TotpException.MissingCounter -> context.getString(R.string.error_hotp_missing_counter)
}

/** Restoring a `.passpony` backup, or checking a typed recovery code. */
fun BackupException.ponyMessage(context: Context): String = when (this) {
    is BackupException.NotABackup -> context.getString(R.string.error_backup_not_a_backup)
    is BackupException.NewerVersion -> context.getString(R.string.error_backup_newer_version)
    is BackupException.Damaged -> context.getString(R.string.error_backup_damaged)
    is BackupException.CodeLength -> context.getString(R.string.error_backup_code_length)
    is BackupException.CodeCharacter -> context.getString(R.string.error_backup_code_character)
    is BackupException.CodeChecksum -> context.getString(R.string.error_backup_code_checksum)
    is BackupException.RandomUnavailable -> context.getString(R.string.error_backup_random)
}

/** A scanned or pasted Google Authenticator export that could not be read. */
fun MigrationException.ponyMessage(context: Context): String = when (this) {
    is MigrationException.NotMigrationUri -> context.getString(R.string.error_migration_not_export)
    is MigrationException.MissingData,
    is MigrationException.BadBase64,
    is MigrationException.BadPayload -> context.getString(R.string.error_migration_damaged)
}

/** The generator sheet keeps these unreachable; they exist for completeness. */
fun GenerateException.ponyMessage(context: Context): String = when (this) {
    is GenerateException.BadLength -> context.getString(R.string.error_generate_bad_length, min.toInt(), max.toInt())
    is GenerateException.NoClasses -> context.getString(R.string.error_generate_no_classes)
    is GenerateException.Entropy -> context.getString(R.string.error_generate_entropy, reason)
}
