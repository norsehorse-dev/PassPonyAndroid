package com.passpony.android.crypto

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.passpony.android.store.passPonyDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * 3.0: where a pass store's OpenPGP keys live. Either PassPony's own
 * imported key files, or one provider app (PGPony, OpenKeychain) that
 * keeps the keys and does the decrypting and encrypting. Never a mix.
 */
sealed class PgpKeySource {
    /** Key files in `filesDir/pgp-keys/`, the core's Rust engine. */
    data object BuiltIn : PgpKeySource()

    /**
     * A provider app, by package, pinned to the signing certificate it had
     * when the person chose it (lowercase hex SHA-256). A different
     * certificate under the same package name is refused.
     */
    data class Provider(val packageName: String, val certSha256: String) : PgpKeySource()
}

object PgpKeySourceStore {
    private val PACKAGE_KEY = stringPreferencesKey("pgp_provider_package")
    private val CERT_KEY = stringPreferencesKey("pgp_provider_cert")

    /** A blocking one-read snapshot, like StorePaths.currentFormatSnapshot. */
    fun current(context: Context): PgpKeySource = runBlocking {
        val (pkg, cert) = context.passPonyDataStore.data.map { it[PACKAGE_KEY] to it[CERT_KEY] }.first()
        if (pkg.isNullOrEmpty() || cert.isNullOrEmpty()) PgpKeySource.BuiltIn else PgpKeySource.Provider(pkg, cert)
    }

    suspend fun set(context: Context, source: PgpKeySource) {
        context.passPonyDataStore.edit { prefs ->
            when (source) {
                is PgpKeySource.BuiltIn -> {
                    prefs.remove(PACKAGE_KEY)
                    prefs.remove(CERT_KEY)
                }
                is PgpKeySource.Provider -> {
                    prefs[PACKAGE_KEY] = source.packageName
                    prefs[CERT_KEY] = source.certSha256
                }
            }
        }
    }
}
