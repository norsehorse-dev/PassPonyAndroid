package com.passpony.android.store

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import uniffi.pass_ffi.FsEntry
import uniffi.pass_ffi.FsException
import uniffi.pass_ffi.StoreFs

/**
 * 3.0: a folder store's files, through the Storage Access Framework.
 *
 * The folder is one the person picked with the system picker; the app
 * holds a persisted permission for that tree and nothing else. Built on
 * [DocumentsContract] directly: `DocumentFile` runs a query per property
 * and is too slow for walking a store.
 *
 * - **Lookups.** A document is found by listing its parent, one query
 *   per directory. Directory ids are remembered for the life of this
 *   object; file ids are not, because a sync tool replaces files
 *   underneath us. If a remembered directory id stops working the cache
 *   is dropped and the path is walked again from the root.
 * - **Writes.** A provider has no "replace this file atomically". So the
 *   bytes go to `.<name>.passpony-tmp`, which is renamed to
 *   `.<name>.passpony-tmp-ready` once it is complete and synced; then the
 *   old document is deleted and the ready file renamed to `<name>`. A
 *   reader sees the old file, no file for an instant, or the new file,
 *   never half of one.
 * - **Recovery.** If the app dies between the delete and the last
 *   rename, the ready file is all that is left. The next listing of that
 *   directory renames it into place. A `-tmp` file was never finished and
 *   is deleted. Both names start with a dot, so the core never takes
 *   them for entries, and [list] leaves them out.
 * - **Errors.** A `SecurityException`, or anything failing while the
 *   tree root itself cannot be read, is [FsException.AccessLost]: the
 *   folder is gone or the permission was taken away. The core turns that
 *   into `StoreException.FolderUnavailable`.
 *
 * `ContentResolver` is thread-safe and the core calls back on whatever
 * thread called it. Writes and recovery take one process-wide lock, so
 * the app, autofill and the passkey provider cannot trip over each
 * other's temp files.
 */
class SafStoreFs(context: Context, private val treeUri: Uri) : StoreFs {
    private val resolver = context.applicationContext.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val dirIds = ConcurrentHashMap<String, String>()

    private data class Child(val id: String, val name: String, val isDir: Boolean)

    private fun doc(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    // --- StoreFs -------------------------------------------------------------

    override fun list(dir: String): List<FsEntry> = guarded {
        val path = clean(dir)
        var children = childrenOf(path)
        if (children.any { isTemp(it.name) }) children = tidy(path)
        children.filterNot { isTemp(it.name) }.map { FsEntry(it.name, it.isDir) }
    }

    override fun read(path: String): ByteArray = guarded {
        val file = fileAt(clean(path)) ?: throw FsException.NotFound()
        resolver.openInputStream(doc(file.id))?.use { it.readBytes() } ?: throw FsException.NotFound()
    }

    override fun write(path: String, data: ByteArray) {
        guarded { writeLocked(path, data) }
    }

    private fun writeLocked(path: String, data: ByteArray) {
        val clean = clean(path)
        val name = nameOf(clean)
        synchronized(WRITE_LOCK) {
            val parentId = dirId(parentOf(clean)) ?: throw FsException.NotFound()
            val parent = doc(parentId)
            val siblings = query(parentId)
            val tmpName = ".$name${StoreFsPaths.TMP_SUFFIX}"
            val readyName = ".$name${StoreFsPaths.READY_SUFFIX}"
            siblings.filter { it.name == tmpName || it.name == readyName }.forEach { delete(it.id) }
            val old = siblings.firstOrNull { it.name == name }
            if (old?.isDir == true) throw FsException.Other("a folder named $name is in the way")

            var pending: Uri = DocumentsContract.createDocument(resolver, parent, MIME_BYTES, tmpName)
                ?: throw FsException.Other("couldn't create a file in the folder")
            try {
                writeBytes(pending, data)
                if (!supportsRename(pending)) {
                    // No rename on this provider: write straight over the
                    // target. Not atomic, but the only thing it offers.
                    DocumentsContract.deleteDocument(resolver, pending)
                    val target = old?.let { doc(it.id) }
                        ?: named(DocumentsContract.createDocument(resolver, parent, MIME_BYTES, name), name)
                    writeBytes(target, data)
                    return
                }
                pending = rename(pending, readyName)
                old?.let { delete(it.id) }
                // From here a failure leaves the ready file, which the
                // next listing renames into place.
                named(rename(pending, name), name)
            } catch (e: Exception) {
                if (displayName(pending) != readyName) runCatching { DocumentsContract.deleteDocument(resolver, pending) }
                throw e
            }
        }
    }

    override fun createDirAll(dir: String) {
        guarded { createDirs(dir) }
    }

    private fun createDirs(dir: String) {
        var current = ""
        for (part in StoreFsPaths.parts(dir)) {
            val next = StoreFsPaths.join(current, part)
            if (dirId(next) == null) {
                synchronized(WRITE_LOCK) {
                    if (dirId(next) == null) {
                        val parentId = dirId(current) ?: throw FsException.NotFound()
                        if (query(parentId).any { it.name == part }) {
                            throw FsException.Other("a file named $part is in the way")
                        }
                        val made = named(
                            DocumentsContract.createDocument(resolver, doc(parentId), Document.MIME_TYPE_DIR, part),
                            part,
                        )
                        dirIds[next] = DocumentsContract.getDocumentId(made)
                    }
                }
            }
            current = next
        }
    }

    override fun removeFile(path: String) {
        guarded {
            val file = fileAt(clean(path)) ?: throw FsException.NotFound()
            delete(file.id)
        }
    }

    override fun removeDir(dir: String) {
        guarded {
            val path = clean(dir)
            if (path.isEmpty()) throw FsException.Other("the store root is never removed")
            val id = dirId(path) ?: throw FsException.NotFound()
            // A provider's delete takes everything inside with it. The core
            // only asks for an empty directory to go; make sure it is one.
            if (query(id).isNotEmpty()) throw FsException.Other("directory not empty")
            delete(id)
            for (key in dirIds.keys.toList()) {
                if (key == path || key.startsWith("$path/")) dirIds.remove(key)
            }
        }
    }

    override fun isFile(path: String): Boolean = guarded { fileAt(clean(path)) != null }

    // --- lookups -------------------------------------------------------------

    private fun clean(path: String): String = StoreFsPaths.parts(path).joinToString("/")

    private fun parentOf(path: String): String = path.substringBeforeLast('/', "")

    private fun nameOf(path: String): String = path.substringAfterLast('/')

    private fun query(dirId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        val cursor = resolver.query(uri, CHILD_COLUMNS, null, null, null)
            ?: throw FileNotFoundException("no listing for $dirId")
        return cursor.use { c ->
            val out = ArrayList<Child>(c.count)
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out += Child(id, name, c.getString(2) == Document.MIME_TYPE_DIR)
            }
            out
        }
    }

    private fun dirId(path: String): String? {
        if (path.isEmpty()) return rootId
        dirIds[path]?.let { return it }
        val parentId = dirId(parentOf(path)) ?: return null
        val name = nameOf(path)
        val found = query(parentId).firstOrNull { it.isDir && it.name == name } ?: return null
        dirIds[path] = found.id
        return found.id
    }

    /** The children of a directory, remembering the ids of its subdirectories. */
    private fun childrenOf(path: String): List<Child> {
        val children = try {
            query(dirId(path) ?: throw FsException.NotFound())
        } catch (e: FsException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            if (path.isEmpty()) throw e
            // The remembered id is stale: the folder changed underneath.
            dirIds.clear()
            query(dirId(path) ?: throw FsException.NotFound())
        }
        for (child in children) {
            if (child.isDir) dirIds[StoreFsPaths.join(path, child.name)] = child.id
        }
        return children
    }

    private fun fileAt(path: String): Child? {
        if (path.isEmpty()) return null
        val parent = parentOf(path)
        if (dirId(parent) == null) return null
        val name = nameOf(path)
        return childrenOf(parent).firstOrNull { !it.isDir && it.name == name }
    }

    // --- temp files ------------------------------------------------------------

    private fun isTemp(name: String): Boolean =
        name.startsWith(".") &&
            (name.endsWith(StoreFsPaths.TMP_SUFFIX) || name.endsWith(StoreFsPaths.READY_SUFFIX))

    /**
     * Deal with what an interrupted write left in this directory, and
     * return its children afterwards. Under the write lock, so a write in
     * progress in this process is never mistaken for a leftover.
     */
    private fun tidy(path: String): List<Child> = synchronized(WRITE_LOCK) {
        val id = dirId(path) ?: throw FsException.NotFound()
        val children = query(id)
        for (child in children) {
            if (child.isDir || !isTemp(child.name)) continue
            if (child.name.endsWith(StoreFsPaths.READY_SUFFIX)) {
                val target = child.name.removePrefix(".").removeSuffix(StoreFsPaths.READY_SUFFIX)
                if (target.isNotEmpty() && children.none { it.name == target }) {
                    runCatching { rename(doc(child.id), target) }
                    continue
                }
            }
            runCatching { delete(child.id) }
        }
        query(id)
    }

    // --- document operations ---------------------------------------------------

    private fun writeBytes(uri: Uri, data: ByteArray) {
        val descriptor = resolver.openFileDescriptor(uri, "wt")
            ?: throw FsException.Other("couldn't open a file in the folder for writing")
        descriptor.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { out ->
                out.write(data)
                out.flush()
                // Not every provider is backed by a real file.
                runCatching { out.fd.sync() }
            }
        }
    }

    private fun delete(id: String) {
        if (!DocumentsContract.deleteDocument(resolver, doc(id))) {
            throw FsException.Other("couldn't delete a file in the folder")
        }
    }

    /** Null from the provider means the document kept its id. */
    private fun rename(uri: Uri, name: String): Uri =
        DocumentsContract.renameDocument(resolver, uri, name) ?: uri

    private fun column(uri: Uri, column: String): android.database.Cursor? =
        resolver.query(uri, arrayOf(column), null, null, null)

    private fun displayName(uri: Uri): String? = runCatching {
        column(uri, Document.COLUMN_DISPLAY_NAME)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun supportsRename(uri: Uri): Boolean = runCatching {
        column(uri, Document.COLUMN_FLAGS)?.use {
            it.moveToFirst() && (it.getInt(0) and Document.FLAG_SUPPORTS_RENAME) != 0
        } ?: false
    }.getOrDefault(false)

    /**
     * A provider may hand back a different name than it was asked for (a
     * number added to make it unique, an extension added for the MIME
     * type). A store cannot live with that: `github.age` has to be exactly
     * `github.age`.
     */
    private fun named(uri: Uri?, expected: String): Uri {
        uri ?: throw FsException.Other("couldn't create $expected in the folder")
        val actual = displayName(uri)
        if (actual != null && actual != expected) {
            throw FsException.Other("the folder saved $expected as $actual")
        }
        return uri
    }

    // --- errors ----------------------------------------------------------------

    private fun rootReachable(): Boolean {
        val held = resolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
        if (!held) return false
        return runCatching {
            column(doc(rootId), Document.COLUMN_DOCUMENT_ID)?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: FsException.AccessLost) {
        throw e
    } catch (e: SecurityException) {
        throw FsException.AccessLost()
    } catch (e: Exception) {
        if (!rootReachable()) throw FsException.AccessLost()
        when (e) {
            is FsException -> throw e
            is FileNotFoundException -> throw FsException.NotFound()
            else -> throw FsException.Other(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private val WRITE_LOCK = Any()
        private const val MIME_BYTES = "application/octet-stream"
        private val CHILD_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
        )

        /** The picked folder's own name, for showing in Settings. Null if it cannot be read. */
        fun folderName(context: Context, treeUri: Uri): String? = runCatching {
            val root = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri),
            )
            context.contentResolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }
}
