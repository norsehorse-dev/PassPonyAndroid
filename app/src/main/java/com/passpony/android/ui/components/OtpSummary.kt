package com.passpony.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.passpony.android.R
import com.passpony.android.ui.edit.totpSummaryLine
import uniffi.pass_ffi.HotpSummary
import uniffi.pass_ffi.OtpKind
import uniffi.pass_ffi.entryHotpSummary
import uniffi.pass_ffi.entryOtpKind
import uniffi.pass_ffi.entryTotpSummary
import uniffi.pass_ffi.hotpDescribe
import uniffi.pass_ffi.otpUriKind
import uniffi.pass_ffi.totpDescribe

/**
 * One summary line for either kind of one-time code: TOTP reads
 * "Example:kevin · 6 digits · 30 s", HOTP "Example:kevin · 6 digits ·
 * counter 4". Null when the URI or entry carries no code the core
 * accepts.
 */
@Composable
fun otpSummaryLine(uri: String): String? {
    val kind = remember(uri) { otpUriKind(uri) }
    return when (kind) {
        OtpKind.TOTP -> remember(uri) { runCatching { totpDescribe(uri) }.getOrNull() }?.let { totpSummaryLine(it) }
        OtpKind.HOTP -> remember(uri) { runCatching { hotpDescribe(uri) }.getOrNull() }?.let { hotpSummaryLine(it) }
        null -> null
    }
}

/** The same line for the code an entry already carries. */
@Composable
fun entryOtpSummaryLine(content: ByteArray): String? {
    val kind = remember(content) { entryOtpKind(content) }
    return when (kind) {
        OtpKind.TOTP -> remember(content) { entryTotpSummary(content) }?.let { totpSummaryLine(it) }
        OtpKind.HOTP -> remember(content) { entryHotpSummary(content) }?.let { hotpSummaryLine(it) }
        null -> null
    }
}

@Composable
fun hotpSummaryLine(summary: HotpSummary): String =
    stringResource(
        R.string.otp_hotp_summary_format,
        summary.label,
        summary.digits.toInt(),
        summary.counter.toLong()
    )

/**
 * Validate a pasted or scanned code link of either kind; throws the
 * core's TotpException when it isn't one.
 */
fun validateOtpUri(uri: String) {
    when (otpUriKind(uri)) {
        OtpKind.TOTP -> totpDescribe(uri)
        OtpKind.HOTP -> hotpDescribe(uri)
        null -> totpDescribe(uri) // throws NotTotpUri with the right message
    }
}
