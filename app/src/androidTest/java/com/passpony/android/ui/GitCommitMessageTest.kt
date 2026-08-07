package com.passpony.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.passpony.android.crypto.PgpKeyStore
import com.passpony.android.store.StorePaths
import java.io.File
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.GitSync
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.commitMessageAdd
import uniffi.pass_ffi.commitMessageEdit
import uniffi.pass_ffi.commitMessageRemove
import uniffi.pass_ffi.commitMessageRename

/**
 * P08's git-commit-on-mutation guarantee, verified against a REAL git
 * repository: add/edit/rename/delete through AppViewModel each produce
 * exactly one commit, with the exact message the corresponding
 * commitMessage* helper produces. GitSyncInterface has commitPaths /
 * push / remoteUrl / setRemote / status / sync -- no way to read commit
 * history back over FFI -- and there is no git CLI binary on-device to
 * shell out to, so this reads the repository GitSync (git2-rs) actually
 * wrote using JGit, a pure-JVM git implementation needing no native
 * binary either. Reuses the P08 edit fixture's identity/gpg-id (see
 * PgpEditByteFidelityTest) purely as a working key pair -- this test is
 * about the git wiring, not the crypto.
 */
@RunWith(AndroidJUnit4::class)
class GitCommitMessageTest {

    private fun asset(path: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("pass-edit-minimal/$path").use { it.readBytes() }

    @Test
    fun addEditRenameDelete_eachProduceTheExactPassCliCommitMessage() {
        val appContext = ApplicationProvider.getApplicationContext<Application>()

        runBlocking { StorePaths.setFormat(appContext, StoreFormat.PASS) }
        val root = StorePaths.storeRoot(appContext, StoreFormat.PASS)
        root.deleteRecursively()
        root.mkdirs()
        File(root, ".gpg-id").writeBytes(asset("store/gpg-id"))

        val keyDir = PgpKeyStore.keyDirectory(appContext)
        keyDir.listFiles()?.forEach { it.delete() }
        File(keyDir, "identity.asc").writeBytes(asset("identity.asc"))

        // The app itself has no "create a repo" flow yet (P09); bootstrap
        // one directly the way `pass git init` would, then let it go --
        // AppViewModel.openStore() only ever opens an existing repo.
        GitSync.init(root.absolutePath, StoreFormat.PASS).use { }

        val viewModel = AppViewModel(appContext)
        viewModel.openStore()

        val name = "web/git-commit-test"
        val renamed = "web/git-commit-test-renamed"

        viewModel.saveEntry(name, "first-password\n".toByteArray(Charsets.UTF_8), isNew = true)
        viewModel.saveEntry(
            name,
            "first-password\nurl: example.com\n".toByteArray(Charsets.UTF_8),
            isNew = false
        )
        assertTrue(viewModel.moveEntry(name, renamed))
        viewModel.deleteEntry(renamed)

        val expected = listOf(
            commitMessageAdd(name),
            commitMessageEdit(name),
            commitMessageRename(name, renamed),
            commitMessageRemove(renamed),
        )

        val messages = Git.open(root).use { git -> git.log().call().map { it.shortMessage }.toList() }
        // JGit's log() is newest-first; the 4 mutations above are the 4
        // most recent commits, on top of GitSync.init's own 2 (initial
        // contents + .gitattributes configure).
        val actual = messages.take(4).reversed()
        assertEquals(expected, actual)
    }
}
