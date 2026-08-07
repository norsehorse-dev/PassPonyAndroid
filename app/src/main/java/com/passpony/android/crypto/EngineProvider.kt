package com.passpony.android.crypto

import android.content.Context
import com.passpony.android.BuildConfig
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.StoreFormat

/**
 * Resolves the real crypto engine for a store format. PASSAGE gets the
 * real age engine as of this packet; PASS stays on the debug flip
 * engine until P06 lands the pass/OpenPGP engine, and traps in release
 * builds rather than silently running without real crypto, the same
 * rule DevCryptoEngine itself follows.
 */
object EngineProvider {
    fun engine(context: Context, format: StoreFormat): CryptoBackend = when (format) {
        StoreFormat.PASSAGE -> AgeIdentityStore.loadOrCreateEngine(context)
        StoreFormat.PASS -> {
            check(BuildConfig.DEBUG) {
                "No pass engine yet (P06); DevCryptoEngine must never run in a release build"
            }
            DevCryptoEngine
        }
    }
}
