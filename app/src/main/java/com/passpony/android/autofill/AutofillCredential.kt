package com.passpony.android.autofill

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.passpony.android.R
import com.passpony.android.crypto.EngineProvider
import com.passpony.android.store.StorePaths
import com.passpony.android.store.UnlockGate
import com.passpony.android.ui.detail.Utf8Text
import uniffi.pass_ffi.EntryRef
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.entryFields
import uniffi.pass_ffi.entryPassword

/**
 * Open-store-and-decrypt, shared by AutofillAuthActivity (dataset pick)
 * and AutofillPickerActivity (picker pick) -- port of iOS's
 * CredentialProviderViewController.credential(for:), which both of its
 * fill paths call through too. Autofill has no long-lived AppViewModel
 * to reuse (the service and both activities can each be the first thing
 * to run in a fresh process), so this opens the store fresh every call --
 * PassStore.open is cheap, matches AppViewModel.openStore()'s own doc
 * comment on that point.
 */
object AutofillCredential {
    data class Credential(val username: String, val password: String)

    /** Every entry name PassPonyAutofillService matches requests
     * against. Read-only, no decrypt. */
    fun entries(context: Context): List<EntryRef> {
        val format = StorePaths.currentFormatSnapshot(context)
        val root = StorePaths.storeRoot(context, format)
        return PassStore.open(root.absolutePath, format).entries()
    }

    /**
     * Decrypt [name] and extract the two fields autofill fills: the
     * password proper, and the first of "username"/"login" (same field
     * keys iOS's extension reads) if present. Propagates any FFI/IO
     * exception to the caller -- both call sites are deliberate,
     * user-initiated actions (a dataset/picker pick) that must know a
     * fill failed, not swallow it silently the way a background refresh
     * would.
     */
    fun resolve(context: Context, name: String): Credential {
        val format = StorePaths.currentFormatSnapshot(context)
        val root = StorePaths.storeRoot(context, format)
        val store = PassStore.open(root.absolutePath, format)
        val engine = EngineProvider.engine(context, format)
        val content = store.readEntry(name, engine)
        val password = Utf8Text.decodeStrict(entryPassword(content)) ?: ""
        val username = entryFields(content).firstOrNull { it.key == "username" || it.key == "login" }?.value ?: ""
        return Credential(username, password)
    }

    /**
     * The full pick flow both AutofillAuthActivity (a direct dataset
     * pick) and AutofillPickerActivity (a picker pick) share: skip
     * straight to decrypt when the grace window is fresh, else
     * authenticate first -- port of iOS's
     * prepareInterfaceToProvideCredential gate, deliberately applied to
     * the picker path too (plan item 3), unlike iOS's picker which
     * relies on the system's own pre-gating of the credential sheet --
     * Android's picker has no equivalent, so it re-checks here.
     */
    fun resolveGated(
        activity: FragmentActivity,
        name: String,
        onSuccess: (Credential) -> Unit,
        onFailure: () -> Unit,
    ) {
        fun decryptNow() {
            try {
                onSuccess(resolve(activity, name))
            } catch (e: Exception) {
                onFailure()
            }
        }
        if (UnlockGate.isFresh(activity)) {
            decryptNow()
        } else {
            UnlockGate.authenticate(
                activity = activity,
                reason = activity.getString(R.string.autofill_unlock_reason),
                onSuccess = { decryptNow() },
                onError = { _, _ -> onFailure() }
            )
        }
    }
}
