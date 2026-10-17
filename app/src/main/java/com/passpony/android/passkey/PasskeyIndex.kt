package com.passpony.android.passkey

import android.content.Context
import android.util.Base64
import java.io.File
import uniffi.pass_ffi.CryptoBackend
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.entryPasskeys

/**
 * Which entries hold passkeys for which sites, so the passkey picker can
 * list candidates before anything is unlocked or decrypted (2.0 rc3).
 * Matches how autofill indexes passwords: by entry name and relying party
 * id only. The usernames and keys stay inside the encrypted entries.
 *
 * One app-private file per store format, one line per passkey:
 * `rpId<TAB>entryName<TAB>credentialId` (credential id as base64url). It
 * is rebuilt from the store after a sync or clone brings in changes, and
 * kept current by the app's own passkey writes, deletes, moves and edits.
 */
object PasskeyIndex {
    data class Ref(val rpId: String, val entryName: String, val credentialId: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Ref && rpId == other.rpId && entryName == other.entryName &&
                credentialId.contentEquals(other.credentialId)

        override fun hashCode(): Int =
            (rpId.hashCode() * 31 + entryName.hashCode()) * 31 + credentialId.contentHashCode()
    }

    private fun file(context: Context, format: StoreFormat): File {
        val suffix = if (format == StoreFormat.PASSAGE) "passage" else "pass"
        return File(context.filesDir, "passkeys-$suffix.idx")
    }

    fun exists(context: Context, format: StoreFormat): Boolean = file(context, format).exists()

    @Synchronized
    fun all(context: Context, format: StoreFormat): List<Ref> {
        val f = file(context, format)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@mapNotNull null
            val id = runCatching { decode(parts[2]) }.getOrNull() ?: return@mapNotNull null
            Ref(parts[0], parts[1], id)
        }
    }

    fun forRpId(context: Context, format: StoreFormat, rpId: String): List<Ref> =
        all(context, format).filter { it.rpId == rpId }

    /** Replace every line for [entryName] with what its content holds now. */
    @Synchronized
    fun updateEntry(context: Context, format: StoreFormat, entryName: String, content: ByteArray?) {
        val kept = all(context, format).filterNot { it.entryName == entryName }
        val fresh = content?.let { refsIn(entryName, it) } ?: emptyList()
        write(context, format, kept + fresh)
    }

    @Synchronized
    fun renameEntry(context: Context, format: StoreFormat, from: String, to: String) {
        write(context, format, all(context, format).map { if (it.entryName == from) it.copy(entryName = to) else it })
    }

    /**
     * Rebuild from the whole store. Entries that can't be decrypted right
     * now (a locked pass key) are skipped rather than failing the rebuild.
     */
    @Synchronized
    fun rebuild(context: Context, format: StoreFormat, store: PassStore, engine: CryptoBackend) {
        val refs = mutableListOf<Ref>()
        for (entry in store.entries()) {
            val content = runCatching { store.readEntry(entry.name, engine) }.getOrNull() ?: continue
            try {
                refs += refsIn(entry.name, content)
            } finally {
                content.fill(0)
            }
        }
        write(context, format, refs)
    }

    private fun refsIn(entryName: String, content: ByteArray): List<Ref> =
        entryPasskeys(content).map { Ref(it.rpId, entryName, it.credentialId) }

    private fun write(context: Context, format: StoreFormat, refs: List<Ref>) {
        val f = file(context, format)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(
            refs.distinct().joinToString("") { "${clean(it.rpId)}\t${clean(it.entryName)}\t${encode(it.credentialId)}\n" }
        )
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ')

    fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun decode(text: String): ByteArray = Base64.decode(text, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
