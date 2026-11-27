package com.passpony.android.store

import java.io.File
import java.io.FileNotFoundException
import uniffi.pass_ffi.FsEntry
import uniffi.pass_ffi.FsException
import uniffi.pass_ffi.StoreFs

/**
 * The store file layer over a plain directory (3.0).
 *
 * The app-storage store itself is opened by path, so the core never
 * calls this. It exists for the Kotlin code that looks at a store's
 * files directly ([AgeHeader], [DeviceKeys.classicFolders]): that code
 * is written once against [StoreFs] and gets this for app storage and
 * [SafStoreFs] for a folder. Pure JVM, so it is also what the unit tests
 * use.
 */
class FileStoreFs(private val root: File) : StoreFs {
    private fun at(path: String): File {
        val parts = StoreFsPaths.parts(path)
        return if (parts.isEmpty()) root else File(root, parts.joinToString(File.separator))
    }

    override fun list(dir: String): List<FsEntry> {
        val children = at(dir).listFiles() ?: throw FsException.NotFound()
        return children.mapNotNull { child ->
            when {
                child.isDirectory -> FsEntry(child.name, true)
                child.isFile -> FsEntry(child.name, false)
                else -> null
            }
        }
    }

    override fun read(path: String): ByteArray = try {
        at(path).readBytes()
    } catch (e: FileNotFoundException) {
        throw FsException.NotFound()
    }

    override fun write(path: String, data: ByteArray) {
        val target = at(path)
        val tmp = File(target.parentFile, ".${target.name}${StoreFsPaths.TMP_SUFFIX}")
        try {
            tmp.writeBytes(data)
            if (!tmp.renameTo(target)) throw FsException.Other("couldn't replace ${target.name}")
        } catch (e: FsException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw FsException.Other(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun createDirAll(dir: String) {
        val target = at(dir)
        if (!target.isDirectory && !target.mkdirs()) throw FsException.Other("couldn't create $dir")
    }

    override fun removeFile(path: String) {
        val target = at(path)
        if (!target.isFile) throw FsException.NotFound()
        if (!target.delete()) throw FsException.Other("couldn't delete ${target.name}")
    }

    override fun removeDir(dir: String) {
        val target = at(dir)
        if (!target.isDirectory) throw FsException.NotFound()
        // File.delete refuses a directory that still holds something.
        if (!target.delete()) throw FsException.Other("directory not empty")
    }

    override fun isFile(path: String): Boolean = at(path).isFile
}

/** Path rules shared by the Kotlin file layers. */
internal object StoreFsPaths {
    /** A write in progress. Never promoted; deleted when found. */
    const val TMP_SUFFIX = ".passpony-tmp"

    /** A finished write that has not been renamed into place yet. */
    const val READY_SUFFIX = ".passpony-tmp-ready"

    /**
     * The components of a store-relative path. A leading slash and `.`
     * components are dropped, the way the core's own layer treats them;
     * `..` is refused here even though the core never sends one.
     */
    fun parts(path: String): List<String> {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw FsException.Other("path leaves the store")
        return parts
    }

    fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"
}

/**
 * Every file under the store, as store-relative paths, `.git` skipped.
 * A directory that cannot be listed is passed over. Sorted by name at
 * each level, so "the first entry" is the same one every time.
 */
fun StoreFs.walkFiles(dir: String = ""): Sequence<String> = sequence {
    val children = runCatching { list(dir) }.getOrDefault(emptyList()).sortedBy { it.name }
    for (child in children) {
        if (child.name == ".git") continue
        val path = StoreFsPaths.join(dir, child.name)
        if (child.isDir) yieldAll(walkFiles(path)) else yield(path)
    }
}
