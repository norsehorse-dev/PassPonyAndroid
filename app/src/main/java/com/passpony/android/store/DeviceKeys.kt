package com.passpony.android.store

import android.content.Context
import com.passpony.android.crypto.AgeIdentityStore
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.GitSyncInterface
import uniffi.pass_ffi.PassStoreInterface
import uniffi.pass_ffi.ReencryptReport
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.ageIdentitiesRecipients
import uniffi.pass_ffi.commitMessageRecipientsAdd
import uniffi.pass_ffi.commitMessageRecipientsRemove
import uniffi.pass_ffi.commitMessageReencrypt
import uniffi.pass_ffi.recipientsAdd
import uniffi.pass_ffi.recipientsIn
import uniffi.pass_ffi.recipientsRemove

/**
 * 2.0 rc4: devices sharing a passage store through its root
 * `.age-recipients`, so syncing is all it takes. No secret key leaves a
 * device; each one lists its own public key.
 *
 * - [join]: after a sync, clone or publish, this device's `age1...` keys
 *   are added to the file (created if the store has none) and committed,
 *   so everything saved from then on, here or anywhere, is readable by
 *   every listed device.
 * - [pending]: keys in the file this device has not been asked about.
 *   Older entries are only readable by whoever wrote them, so a device
 *   that can read them has to re-encrypt them for the new key. That
 *   step always asks the person first ([approve] or [remove]): anyone
 *   who can push to the repo can add a key, and silently re-encrypting
 *   to it would hand them the store.
 *
 * Which keys this device has already been asked about is kept here, on
 * the device, per store format; never in the repo. pass (OpenPGP) stores
 * are left alone: `.gpg-id` stays the person's to edit.
 */
object DeviceKeys {
    private const val RECIPIENTS_FILE = ".age-recipients"

    private fun prefs(context: Context) =
        context.getSharedPreferences("device_keys", Context.MODE_PRIVATE)

    private fun prefKey(format: StoreFormat) =
        if (format == StoreFormat.PASSAGE) "seen_passage" else "seen_pass"

    private fun seen(context: Context, format: StoreFormat): Set<String> =
        prefs(context).getStringSet(prefKey(format), emptySet()).orEmpty().toSet()

    private fun markSeen(context: Context, format: StoreFormat, keys: Collection<String>, replace: Boolean = false) {
        val next = if (replace) keys.toSet() else seen(context, format) + keys
        prefs(context).edit().putStringSet(prefKey(format), next).apply()
    }

    /** The keys the root `.age-recipients` lists; none when it can't be read. */
    private fun listed(store: PassStoreInterface): List<String> =
        runCatching { store.rootRecipients() }.getOrNull()?.let { recipientsIn(it) }.orEmpty()

    /** This device's own `age1...` keys, from its identities file. */
    fun ownKeys(context: Context): List<String> =
        AgeIdentityStore.exportText(context)?.let { ageIdentitiesRecipients(it) }.orEmpty()

    /**
     * List this device's keys in the root `.age-recipients` and commit.
     * Returns true when the file changed, so the caller pushes.
     */
    fun join(context: Context, format: StoreFormat, store: PassStoreInterface, git: GitSyncInterface?): Boolean {
        if (format != StoreFormat.PASSAGE) return false
        val own = ownKeys(context)
        if (own.isEmpty()) return false
        markSeen(context, format, own)
        val edit = recipientsAdd(store.rootRecipients() ?: "", own)
        if (edit.changed == 0u) return false
        store.setRootRecipients(edit.text)
        runCatching { git?.commitPaths(listOf(RECIPIENTS_FILE), commitMessageRecipientsAdd()) }
        return true
    }

    /**
     * A store this device just cloned: whoever set it up already chose
     * its recipients, and this device cannot read anything to re-encrypt
     * yet, so the keys there now are not asked about.
     */
    fun baseline(context: Context, format: StoreFormat, store: PassStoreInterface) {
        if (format != StoreFormat.PASSAGE) return
        markSeen(context, format, listed(store) + ownKeys(context), replace = true)
    }

    /** Keys in the file this device has not been asked about yet. */
    fun pending(context: Context, format: StoreFormat, store: PassStoreInterface?): List<String> {
        if (format != StoreFormat.PASSAGE || store == null) return emptyList()
        val known = seen(context, format) + ownKeys(context)
        return listed(store).filterNot { it in known }
    }

    /**
     * The person recognized [key]: re-encrypt every entry this device can
     * read to the current recipients, which include it, and commit.
     * Entries this device cannot read are left for a device that can.
     */
    fun approve(
        context: Context,
        format: StoreFormat,
        store: PassStoreInterface,
        engine: CryptoBackend,
        git: GitSyncInterface?,
        key: String,
    ): ReencryptReport {
        val report = store.reencryptReadable("", engine)
        if (report.rewritten.isNotEmpty()) {
            val files = report.rewritten.map { StorePaths.entryFileName(it, format) }
            runCatching { git?.commitPaths(files, commitMessageReencrypt("")) }
        }
        markSeen(context, format, listOf(key))
        return report
    }

    /**
     * The person did not recognize [key]: take it out of the file and
     * commit, so nothing saved from now on is encrypted to it. Not marked
     * as seen, so if it comes back it is asked about again.
     */
    fun remove(format: StoreFormat, store: PassStoreInterface, git: GitSyncInterface?, key: String) {
        if (format != StoreFormat.PASSAGE) return
        val edit = recipientsRemove(store.rootRecipients() ?: return, key)
        if (edit.changed == 0u) return
        store.setRootRecipients(edit.text)
        runCatching { git?.commitPaths(listOf(RECIPIENTS_FILE), commitMessageRecipientsRemove()) }
    }
}
