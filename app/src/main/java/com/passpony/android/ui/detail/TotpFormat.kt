package com.passpony.android.ui.detail

/**
 * Splits a TOTP code into two space-separated halves for display: "123
 * 456" for 6 digits, "1234 5678" for 8 — pass-otp's own convention, and
 * what PassPony iOS's TOTPRingView.formatted(_:) does. entryTotp() only
 * ever returns digit strings, but a code of some other length (e.g. a
 * URI with a `digits` value pass-otp itself wouldn't produce) is shown
 * ungrouped rather than mis-split down the middle.
 */
object TotpFormat {
    fun group(code: String): String {
        if (code.length != 6 && code.length != 8) return code
        val mid = code.length / 2
        return "${code.substring(0, mid)} ${code.substring(mid)}"
    }
}
