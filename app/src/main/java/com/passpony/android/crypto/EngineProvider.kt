package com.passpony.android.crypto

import android.content.Context
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.StoreFormat

/**
 * Resolves the real crypto engine for a store format. Both formats now
 * route to real crypto in the Rust core: PASSAGE to age, PASS to rpgp
 * (the pgp-engine feature, since 3.0). DevCryptoEngine (the reversible byte flip)
 * stays in the tree as a debug-only stand-in but is no longer wired here.
 */
object EngineProvider {
    fun engine(context: Context, format: StoreFormat): CryptoBackend = when (format) {
        StoreFormat.PASSAGE -> AgeIdentityStore.loadOrCreateEngine(context)
        // 3.0: built-in key files, or a provider app that keeps the keys.
        StoreFormat.PASS -> when (val source = PgpKeySourceStore.current(context)) {
            is PgpKeySource.BuiltIn -> PgpEngine.load(context)
            is PgpKeySource.Provider -> ProviderPgpEngine(context, source)
        }
    }
}
