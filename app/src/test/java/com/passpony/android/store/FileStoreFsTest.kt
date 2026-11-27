package com.passpony.android.store

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.pass_ffi.FsEntry
import uniffi.pass_ffi.FsException

class FileStoreFsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun readsWritesAndListsLikeTheCoreLayer() {
        val root = tmp.newFolder("store")
        val fs = FileStoreFs(root)
        assertTrue(fs.list("").isEmpty())
        assertThrows(FsException.NotFound::class.java) { fs.list("nope") }
        assertThrows(FsException.NotFound::class.java) { fs.read("a.age") }
        assertFalse(fs.isFile("a.age"))

        fs.write("a.age", "one".toByteArray())
        fs.write("a.age", "two".toByteArray())
        assertArrayEquals("two".toByteArray(), fs.read("a.age"))
        assertTrue(fs.isFile("a.age"))
        assertEquals("no temp file is left behind", listOf("a.age"), root.list()!!.toList())

        fs.createDirAll("web/deep")
        fs.write("web/deep/b.age", "x".toByteArray())
        assertFalse("a directory is not a file", fs.isFile("web"))
        assertEquals(
            listOf(FsEntry("a.age", false), FsEntry("web", true)),
            fs.list("").sortedBy { it.name },
        )

        assertThrows(FsException.Other::class.java) { fs.removeDir("web/deep") }
        assertTrue(fs.isFile("web/deep/b.age"))
        fs.removeFile("web/deep/b.age")
        assertThrows(FsException.NotFound::class.java) { fs.removeFile("web/deep/b.age") }
        fs.removeDir("web/deep")
        fs.removeDir("web")
        assertEquals(listOf("a.age"), root.list()!!.toList())
    }

    @Test
    fun staysInsideTheStore() {
        val root = tmp.newFolder("inside")
        val outside = File(tmp.root, "outside.age").apply { writeText("secret") }
        val fs = FileStoreFs(root)
        assertThrows(FsException.Other::class.java) { fs.read("../outside.age") }
        assertThrows(FsException.Other::class.java) { fs.write("web/../../outside.age", ByteArray(1)) }
        assertEquals("secret", outside.readText())
        // A leading slash stays inside, as in the core and the CLIs.
        fs.write("/top.age", "x".toByteArray())
        assertTrue(File(root, "top.age").isFile)
    }

    @Test
    fun walkFilesSkipsGitAndIsSorted() {
        val root = tmp.newFolder("walk")
        File(root, ".git").mkdirs()
        File(root, ".git/trap.age").writeText("x")
        File(root, "web").mkdirs()
        File(root, "web/z.age").writeText("x")
        File(root, "web/a.age").writeText("x")
        File(root, "bank.age").writeText("x")
        File(root, ".age-recipients").writeText("x")
        assertEquals(
            listOf(".age-recipients", "bank.age", "web/a.age", "web/z.age"),
            FileStoreFs(root).walkFiles().toList(),
        )
    }
}
