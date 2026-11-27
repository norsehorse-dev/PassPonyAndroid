package com.passpony.android.store

import uniffi.pass_ffi.FsException
import uniffi.pass_ffi.StoreFs

/**
 * 3.0: copy a whole store from one place to another, for moving a store
 * into a folder or back into app storage. Everything except `.git` goes
 * across: entries, every recipients file, anything else a person keeps
 * in the store. git history never does; a folder store has no git.
 *
 * All or nothing. A name already taken at the destination stops the copy
 * before a single file is written. After copying, every file is read back
 * and compared byte for byte, and if anything fails the files this run
 * wrote are removed again. The source is never touched here; deleting it
 * is the caller's decision, made only on [Outcome.Done].
 */
object StoreMove {
    sealed class Outcome {
        data class Done(val files: Int) : Outcome()

        /** These names already exist at the destination. Nothing was written. */
        data class Clash(val names: List<String>) : Outcome()

        /** Something failed. What this run wrote has been removed again. */
        data class Failed(val reason: String) : Outcome()
    }

    fun copy(source: StoreFs, destination: StoreFs): Outcome {
        val files = try {
            source.list("")
            source.walkFiles().filterNot(::isLeftover).toList()
        } catch (e: Exception) {
            return Outcome.Failed(reasonOf(e))
        }
        val clashes = try {
            files.filter { destination.isFile(it) }
        } catch (e: Exception) {
            return Outcome.Failed(reasonOf(e))
        }
        if (clashes.isNotEmpty()) return Outcome.Clash(clashes)

        val written = mutableListOf<String>()
        return try {
            for (path in files) {
                val parent = path.substringBeforeLast('/', "")
                if (parent.isNotEmpty()) destination.createDirAll(parent)
                destination.write(path, source.read(path))
                written += path
            }
            for (path in files) {
                if (!destination.read(path).contentEquals(source.read(path))) {
                    throw FsException.Other("$path did not read back the same")
                }
            }
            Outcome.Done(files.size)
        } catch (e: Exception) {
            for (path in written) runCatching { destination.removeFile(path) }
            Outcome.Failed(reasonOf(e))
        }
    }

    /** A temp file an interrupted write left behind is not part of the store. */
    private fun isLeftover(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.startsWith(".") &&
            (name.endsWith(StoreFsPaths.TMP_SUFFIX) || name.endsWith(StoreFsPaths.READY_SUFFIX))
    }

    private fun reasonOf(e: Exception): String = when (e) {
        is FsException.Other -> e.reason
        is FsException.AccessLost -> "the folder is no longer available"
        is FsException.NotFound -> "a file went missing while copying"
        else -> e.message ?: e.javaClass.simpleName
    }
}
