package com.passpony.android.passkey

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.Action
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.passpony.android.R
import java.util.concurrent.atomic.AtomicInteger
import uniffi.pass_ffi.webauthnParseGetOptions

/**
 * PassPony's passkey provider (2.0 rc3), enabled only on Android 14+ (see
 * the manifest's @bool/passkeys_enabled); older devices never see it.
 *
 * The begin calls answer from the passkey index alone, without unlocking
 * or decrypting: one entry per stored passkey for the site, labelled with
 * the entry's name. The activity behind each entry does the unlock (the
 * user verification step) and the signing. A request from an app that is
 * not a known browser gets a single action that explains PassPony
 * passkeys work in the browser for now.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PassPonyCredentialProviderService : CredentialProviderService() {

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            callback.onError(CreateCredentialUnknownException())
            return
        }
        val isBrowser = request.callingAppInfo?.let { PasskeyCaller.browserOrigin(this, it) != null } ?: true
        val target = if (isBrowser) PasskeyCreateActivity::class.java else PasskeyDeclineActivity::class.java
        val entry = CreateEntry.Builder(getString(R.string.app_name), pendingIntent(target) {})
            .setDescription(
                getString(if (isBrowser) R.string.passkey_create_description else R.string.passkey_browser_only)
            )
            .build()
        callback.onResult(BeginCreateCredentialResponse.Builder().addCreateEntry(entry).build())
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val response = BeginGetCredentialResponse.Builder()
        val options = request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>()
        if (options.isEmpty()) {
            callback.onResult(response.build())
            return
        }
        val info = request.callingAppInfo
        val origin = PasskeyCaller.browserOrigin(this, info)
        if (info != null && origin == null) {
            response.addAction(
                Action.Builder(getString(R.string.app_name), pendingIntent(PasskeyDeclineActivity::class.java) {})
                    .setSubtitle(getString(R.string.passkey_browser_only))
                    .build()
            )
            callback.onResult(response.build())
            return
        }
        val format = PasskeyVault.format(this)
        for (option in options) {
            val parsed = runCatching { webauthnParseGetOptions(option.requestJson) }.getOrNull() ?: continue
            val rpId = parsed.rpId.ifEmpty { origin?.let(PasskeyCaller::host).orEmpty() }
            if (rpId.isEmpty()) continue
            val refs = PasskeyIndex.forRpId(this, format, rpId).filter { ref ->
                parsed.allowCredentials.isEmpty() || parsed.allowCredentials.any { it.contentEquals(ref.credentialId) }
            }
            for (ref in refs) {
                val intent = pendingIntent(PasskeyGetActivity::class.java) {
                    putExtra(PasskeyGetActivity.EXTRA_ENTRY_NAME, ref.entryName)
                    putExtra(PasskeyGetActivity.EXTRA_RP_ID, ref.rpId)
                    putExtra(PasskeyGetActivity.EXTRA_CREDENTIAL_ID, ref.credentialId)
                }
                response.addCredentialEntry(
                    PublicKeyCredentialEntry.Builder(this, ref.entryName, intent, option)
                        .setDisplayName(ref.rpId)
                        .build()
                )
            }
        }
        callback.onResult(response.build())
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null)
    }

    /** Each entry needs its own request code; the framework fills the intent in. */
    private fun pendingIntent(target: Class<*>, configure: Intent.() -> Unit): PendingIntent {
        val intent = Intent(this, target).setPackage(packageName).apply(configure)
        return PendingIntent.getActivity(
            this,
            requestCodes.incrementAndGet(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private companion object {
        val requestCodes = AtomicInteger(0)
    }
}
