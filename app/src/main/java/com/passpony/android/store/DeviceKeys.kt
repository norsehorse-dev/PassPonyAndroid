package com.passpony.android.store

import android.content.Context
import com.passpony.android.crypto.AgeIdentityStore
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.GitSyncInterface
import uniffi.pass_ffi.PassStoreInterface
import uniffi.pass_ffi.ReencryptReport
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.StoreKeyType
import uniffi.pass_ffi.commitMessageRecipientsAdd
import uniffi.pass_ffi.commitMessageRecipientsRemove
import uniffi.pass_ffi.commitMessageReencrypt
import uniffi.pass_ffi.recipientsAdd
import uniffi.pass_ffi.recipientIsPostQuantum
import uniffi.pass_ffi.recipientsIn
import uniffi.pass_ffi.recipientsKeyType
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
 * 3.0: a store is post-quantum or classic, never both, because age will
 * not encrypt one file to both kinds. [postQuantum] says which a store
 * is, and a device only ever lists its key of that kind. A classic key
 * in a post-quantum store comes from a device on an older PassPony;
 * nothing can be saved while it is listed, so it is always asked about
 * and can only be removed ([outdated]).
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

    /** This device's own public keys of both kinds, from its identities file. */
    fun ownKeys(context: Context): List<String> =
        AgeIdentityStore.recipients(context, postQuantum = false) +
            AgeIdentityStore.recipients(context, postQuantum = true)

    /**
     * What the root `.age-recipients` says about the store's keys;
     * [StoreKeyType.EMPTY] when there is no file or it lists nothing.
     */
    fun listedType(store: PassStoreInterface): StoreKeyType =
        recipientsKeyType(runCatching { store.rootRecipients() }.getOrNull() ?: "")

    /**
     * True when this passage store uses post-quantum keys. Decided, in
     * order, by the recipients file (any post-quantum key in it makes it
     * a post-quantum store, see [outdated]); with no file, by what the
     * store's entries were encrypted with; and for an empty store by the
     * keys this device has, which is what the engine would encrypt to.
     * A store from before 3.0 always comes out classic.
     */
    fun postQuantum(context: Context, store: PassStoreInterface): Boolean =
        when (listedType(store)) {
            StoreKeyType.POST_QUANTUM, StoreKeyType.MIXED -> true
            StoreKeyType.CLASSIC -> false
            StoreKeyType.EMPTY ->
                AgeHeader.sniffStore(StorePaths.storeFs(context, StoreFormat.PASSAGE))
                    ?: AgeIdentityStore.recipients(context, postQuantum = false).isEmpty()
        }

    /**
     * True for a classic key listed in a post-quantum store: a device on
     * a PassPony older than 3.0 added it. Saving fails while it is there.
     */
    fun outdated(store: PassStoreInterface, key: String): Boolean =
        !recipientIsPostQuantum(key) && listed(store).any { recipientIsPostQuantum(it) }

    /**
     * List this device's keys of the store's kind in the root
     * `.age-recipients` and commit. A device with no identity of that
     * kind yet gets one first (the caller reloads its engine afterwards).
     * Returns true when the file changed, so the caller pushes.
     */
    fun join(context: Context, format: StoreFormat, store: PassStoreInterface, git: GitSyncInterface?): Boolean {
        if (format != StoreFormat.PASSAGE) return false
        val pq = postQuantum(context, store)
        AgeIdentityStore.ensureIdentity(context, pq)
        val own = AgeIdentityStore.recipients(context, pq)
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

    /**
     * Keys in the file this device has not been asked about yet. Outdated
     * keys come first and are asked about every time, seen or not, even
     * this device's own: the store cannot be saved to until they are gone.
     */
    fun pending(context: Context, format: StoreFormat, store: PassStoreInterface?): List<String> {
        if (format != StoreFormat.PASSAGE || store == null) return emptyList()
        val all = listed(store)
        val pq = all.any { recipientIsPostQuantum(it) }
        val stale = if (pq) all.filterNot { recipientIsPostQuantum(it) } else emptyList()
        val known = seen(context, format) + ownKeys(context)
        return stale + all.filterNot { it in known || it in stale }
    }

    /**
     * A post-quantum store always gets a recipients file. Without one the
     * engine encrypts to this device's classic identities as soon as it
     * has any (after an import or a restore, say), and the store would
     * quietly end up with entries of both kinds. Writes this device's
     * post-quantum keys when the store is post-quantum, has no recipients
     * listed, and the device has such a key. Never generates one, and
     * never touches a classic store. Returns true when it wrote the file.
     */
    fun pinPostQuantum(context: Context, store: PassStoreInterface, git: GitSyncInterface?): Boolean {
        if (listedType(store) != StoreKeyType.EMPTY) return false
        if (!postQuantum(context, store)) return false
        val own = AgeIdentityStore.recipients(context, postQuantum = true)
        if (own.isEmpty()) return false
        store.setRootRecipients(recipientsAdd("", own).text)
        markSeen(context, StoreFormat.PASSAGE, own)
        runCatching { git?.commitPaths(listOf(RECIPIENTS_FILE), commitMessageRecipientsAdd()) }
        return true
    }

    /**
     * True when the store's kind can still be picked: it lists no key
     * that is not this device's own. The caller also checks that the
     * store has no entries.
     */
    fun canChooseKind(context: Context, store: PassStoreInterface): Boolean {
        val own = ownKeys(context)
        return listed(store).all { it in own }
    }

    /**
     * Make an empty store post-quantum or classic by writing this
     * device's key of that kind as the only line of `.age-recipients`.
     * Only for a store with no entries, where nothing needs re-encrypting.
     */
    fun chooseKind(
        context: Context,
        store: PassStoreInterface,
        git: GitSyncInterface?,
        postQuantum: Boolean,
    ) {
        AgeIdentityStore.ensureIdentity(context, postQuantum)
        val own = AgeIdentityStore.recipients(context, postQuantum)
        if (own.isEmpty()) return
        store.setRootRecipients(recipientsAdd("", own).text)
        markSeen(context, StoreFormat.PASSAGE, own)
        runCatching { git?.commitPaths(listOf(RECIPIENTS_FILE), commitMessageRecipientsAdd()) }
    }

    /**
     * Folders with their own `.age-recipients` that lists a classic key.
     * Entries under one are encrypted to that file, not the root one, so
     * moving the store would leave them classic. The move is refused
     * while any exist; the person edits or removes those files first.
     */
    fun classicFolders(context: Context): List<String> {
        val fs = StorePaths.storeFs(context, StoreFormat.PASSAGE)
        return fs.walkFiles()
            .filter { it.endsWith("/$RECIPIENTS_FILE") }
            .filter { path ->
                val text = runCatching { fs.read(path).toString(Charsets.UTF_8) }.getOrNull()
                val type = text?.let { runCatching { recipientsKeyType(it) }.getOrNull() }
                type == StoreKeyType.CLASSIC || type == StoreKeyType.MIXED
            }
            .map { it.removeSuffix("/$RECIPIENTS_FILE") }
            .sorted()
            .toList()
    }

    /**
     * 3.0: move a classic passage store to post-quantum keys. The root
     * `.age-recipients` becomes this device's post-quantum key alone,
     * every entry this device can read is saved again to it, and both go
     * into one commit. Other devices are dropped from the file on
     * purpose: each lists its own post-quantum key at its next sync and
     * is asked about here like any new device.
     *
     * Safe to run again. A store whose file is already post-quantum
     * keeps its file, so a second run only saves the entries again; that
     * is how an interrupted move, or entries another device saved in
     * between, get finished. History is not rewritten.
     *
     * The caller makes sure this device has a post-quantum identity, that
     * its engine was loaded after that identity was made, and that
     * [classicFolders] is empty.
     */
    fun moveToPostQuantum(
        context: Context,
        store: PassStoreInterface,
        engine: CryptoBackend,
        git: GitSyncInterface?,
    ): ReencryptReport {
        val own = AgeIdentityStore.recipients(context, postQuantum = true)
        check(own.isNotEmpty()) { "no post-quantum identity" }
        val format = StoreFormat.PASSAGE
        val type = listedType(store)
        if (type == StoreKeyType.CLASSIC || type == StoreKeyType.EMPTY) {
            store.setRootRecipients(recipientsAdd("", own).text)
        }
        val report = store.reencryptReadable("", engine)
        val files = listOf(RECIPIENTS_FILE) + report.rewritten.map { StorePaths.entryFileName(it, format) }
        runCatching { git?.commitPaths(files, commitMessageReencrypt("")) }
        markSeen(context, format, own)
        return report
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
