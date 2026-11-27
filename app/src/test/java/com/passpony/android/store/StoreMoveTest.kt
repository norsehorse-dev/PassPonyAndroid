package com.passpony.android.store

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.pass_ffi.FsException
import uniffi.pass_ffi.StoreFs

class StoreMoveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(name: String): File = tmp.newFolder(name).also { root ->
        File(root, ".age-recipients").writeText("age1example\n")
        File(root, "bank.age").writeText("bank")
        File(root, "web").mkdirs()
        File(root, "web/github.age").writeText("github")
        File(root, "web/.age-recipients").writeText("age1other\n")
        File(root, ".git").mkdirs()
        File(root, ".git/config").writeText("[remote]")
        File(root, ".bank.age.passpony-tmp").writeText("half")
    }

    @Test
    fun copiesEverythingButGitAndLeftovers() {
        val source = store("source")
        val target = tmp.newFolder("target")
        val outcome = StoreMove.copy(FileStoreFs(source), FileStoreFs(target))
        assertEquals(StoreMove.Outcome.Done(4), outcome)
        assertEquals("bank", File(target, "bank.age").readText())
        assertEquals("github", File(target, "web/github.age").readText())
        assertEquals("age1other\n", File(target, "web/.age-recipients").readText())
        assertFalse(File(target, ".git").exists())
        assertFalse(File(target, ".bank.age.passpony-tmp").exists())
        assertTrue("the source is left alone", File(source, "bank.age").isFile)
    }

    @Test
    fun aTakenNameStopsItBeforeAnythingIsWritten() {
        val source = store("source")
        val target = tmp.newFolder("target")
        File(target, "bank.age").writeText("theirs")
        val outcome = StoreMove.copy(FileStoreFs(source), FileStoreFs(target))
        assertEquals(StoreMove.Outcome.Clash(listOf("bank.age")), outcome)
        assertEquals("theirs", File(target, "bank.age").readText())
        assertEquals(listOf("bank.age"), target.list()!!.toList())
    }

    @Test
    fun aFailureRemovesWhatWasWritten() {
        val source = store("source")
        val target = tmp.newFolder("target")
        val real = FileStoreFs(target)
        // Fails on the third file, like a folder that filled up.
        val failing = object : StoreFs by real {
            var writes = 0
            override fun write(path: String, data: ByteArray) {
                if (++writes == 3) throw FsException.Other("no space left")
                real.write(path, data)
            }
        }
        val outcome = StoreMove.copy(FileStoreFs(source), failing)
        assertEquals(StoreMove.Outcome.Failed("no space left"), outcome)
        assertTrue(real.walkFiles().toList().isEmpty())
    }

    @Test
    fun aCopyThatReadsBackDifferentlyIsAFailure() {
        val source = store("source")
        val target = tmp.newFolder("target")
        val real = FileStoreFs(target)
        val lying = object : StoreFs by real {
            override fun read(path: String): ByteArray =
                if (path == "bank.age") "other".toByteArray() else real.read(path)
        }
        val outcome = StoreMove.copy(FileStoreFs(source), lying)
        assertTrue(outcome is StoreMove.Outcome.Failed)
        assertTrue(real.walkFiles().toList().isEmpty())
    }

    @Test
    fun probeTellsTheFormatsApart() {
        fun probe(make: (File) -> Unit): FolderProbe.Kind {
            val root = tmp.newFolder()
            make(root)
            return FolderProbe.inspect(FileStoreFs(root))
        }
        assertEquals(FolderProbe.Kind.EMPTY, probe { })
        assertEquals(FolderProbe.Kind.EMPTY, probe { File(it, ".stfolder").mkdirs() })
        assertEquals(FolderProbe.Kind.PASSAGE, probe { File(it, ".age-recipients").writeText("x") })
        assertEquals(FolderProbe.Kind.PASSAGE, probe { File(it, "web").mkdirs(); File(it, "web/a.age").writeText("x") })
        assertEquals(FolderProbe.Kind.PASS, probe { File(it, ".gpg-id").writeText("x") })
        assertEquals(FolderProbe.Kind.PASS, probe { File(it, "a.gpg").writeText("x") })
        assertEquals(FolderProbe.Kind.BOTH, probe { File(it, ".gpg-id").writeText("x"); File(it, "a.age").writeText("x") })
        assertEquals(FolderProbe.Kind.UNKNOWN, probe { File(it, "photo.jpg").writeText("x") })
    }
}
