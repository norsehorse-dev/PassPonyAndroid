package com.passpony.android.passkey

import android.content.Context
import androidx.credentials.provider.CallingAppInfo
import com.passpony.android.R

/**
 * Who is asking for a passkey (2.0 rc3). PassPony serves browsers only:
 * a request counts as a browser request when the calling app is on the
 * bundled privileged-browser list (res/raw/passkey_browsers.json, Google's
 * published list, shipped with the app rather than fetched), which is what
 * makes CallingAppInfo hand over the web origin.
 *
 * A native app's request is declined with an explanation. Confirming that
 * an app may use a site's passkeys means fetching that site's
 * assetlinks.json, which would be a network request to something other
 * than the user's own git remote.
 *
 * There is deliberately no extra origin-to-rpId check here: the browser
 * has already applied the WebAuthn rules, and Android's provider guidance
 * is that a provider-side check breaks Related Origin Requests.
 */
object PasskeyCaller {
    @Volatile
    private var allowlist: String? = null

    private fun allowlist(context: Context): String =
        allowlist ?: context.resources.openRawResource(R.raw.passkey_browsers)
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .also { allowlist = it }

    /** The web origin when [info] is a known browser, else null. */
    fun browserOrigin(context: Context, info: CallingAppInfo?): String? {
        info ?: return null
        return try {
            info.getOrigin(allowlist(context))?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** The origin's host, for a request that left rpId out. */
    fun host(origin: String): String =
        origin.substringAfter("://").substringBefore('/').substringBefore(':')
}
