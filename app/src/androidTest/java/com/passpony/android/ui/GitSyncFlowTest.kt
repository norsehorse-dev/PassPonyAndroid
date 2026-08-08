package com.passpony.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.passpony.android.crypto.AgeCryptoBackend
import com.passpony.android.crypto.AgeIdentityStore
import com.passpony.android.store.StorePaths
import com.passpony.android.ui.sync.BlockingResolver
import uniffi.pass_ffi.AgeEngine
import uniffi.pass_ffi.ageGenerateIdentity
import uniffi.pass_ffi.ConflictChoice
import uniffi.pass_ffi.GitSync
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.StoreFormat
import uniffi.pass_ffi.SyncOutcome
import uniffi.pass_ffi.commitMessageAdd
import uniffi.pass_ffi.commitMessageEdit

/**
 * P09's Sync screen is a thin UI over AppViewModel's git actions; the
 * git matrix itself (fast-forward, rebase, per-choice conflict
 * resolution) is already proven by PassPonyCore's own
 * pass-devtools/tests/git_matrix.rs. These tests instead prove the
 * Android wiring: publishStore/cloneReplaceStore/syncNow drive a real
 * on-disk repo correctly, and BlockingResolver's threading contract
 * (choose() blocks an IO thread, resolve() unblocks it from Main)
 * actually works end to end against a real rebase.
 *
 * "Remote" is a bare repo created with JGit -- a pure-JVM git
 * implementation, already a test-only dependency for GitCommitMessageTest
 * (P08) -- exactly the way PassPonyCore's own git_matrix.rs uses git2
 * directly to scaffold its bare remote rather than going through
 * GitStore, which has no bare-init of its own. A second "device" is a
 * raw PassStore + GitSync pair (bypassing AppViewModel, which only
 * ever manages one fixed store root), mirroring that same Rust test's
 * lightweight `Device` harness.
 */
@RunWith(AndroidJUnit4::class)
class GitSyncFlowTest {

    private fun appContext(): Application = ApplicationProvider.getApplicationContext()

    private fun freshDir(name: String): File {
        val dir = File(appContext().cacheDir, "git-sync-flow-$name")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    private fun bareRemote(name: String): File {
        val dir = freshDir(name)
        Git.init().setBare(true).setDirectory(dir).call().use { }
        return dir
    }

    /** Cloned working copies have no committer identity until JGit sets one -- GitSync.init's own placeholder identity only applies to repos it creates itself. */
    private fun configureIdentity(dir: File) {
        Git.open(dir).use { git ->
            val config = git.repository.config
            config.setString("user", null, "name", "PassPony Test")
            config.setString("user", null, "email", "test@passpony.test")
            config.save()
        }
    }

    private fun waitUntilConflict(resolver: BlockingResolver, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (resolver.conflictPath != null) return true
            Thread.sleep(10)
        }
        return false
    }

    @Test
    fun publishFlow_initsAndPushesToAnEmptyRemote() = runBlocking {
        val remote = bareRemote("publish-remote")

        StorePaths.setFormat(appContext(), StoreFormat.PASSAGE)
        AgeIdentityStore.identitiesFile(appContext()).delete()
        StorePaths.storeRoot(appContext(), StoreFormat.PASSAGE).deleteRecursively()

        val appViewModel = AppViewModel(appContext())
        appViewModel.openStore()
        appViewModel.saveEntry("published-entry", "pw-from-this-device\n".toByteArray(Charsets.UTF_8), isNew = true)

        appViewModel.publishStore(remote.absolutePath)

        assertEquals(remote.absolutePath, appViewModel.remoteUrl())
        val status = appViewModel.syncStatus.value
        assertTrue("expected a remote after publish", status?.hasRemote == true)
        assertEquals("nothing should be unpushed right after publish", 0u, status?.ahead)

        // Verify from a completely independent clone -- not just that
        // AppViewModel *thinks* it published, but that the bare remote
        // genuinely has the pushed, decryptable content.
        val verifyDir = freshDir("publish-verify")
        GitSync.cloneFrom(remote.absolutePath, verifyDir.absolutePath, null).close()
        val identitiesText = AgeIdentityStore.identitiesFile(appContext()).readText(Charsets.UTF_8)
        val backend = AgeCryptoBackend(AgeEngine.fromIdentitiesText(identitiesText))
        val verifyStore = PassStore.open(verifyDir.absolutePath, StoreFormat.PASSAGE)
        assertArrayEquals(
            "pw-from-this-device\n".toByteArray(Charsets.UTF_8),
            verifyStore.readEntry("published-entry", backend)
        )
    }

    @Test
    fun cloneThenSync_conflictOnTheSameEntry_keepBothProducesTheConflictSibling() = runBlocking {
        val remote = bareRemote("conflict-remote")
        val sharedIdentities = ageGenerateIdentity()
        val sharedIdentitiesText = "${sharedIdentities.identityString}\n"
        val sharedBackend = AgeCryptoBackend(AgeEngine.fromIdentitiesText(sharedIdentitiesText))

        // Seed device: founds the store, writes "shared", pushes -- the
        // remote's initial state before anyone clones it.
        val seedDir = freshDir("conflict-seed")
        val seedStore = PassStore.open(seedDir.absolutePath, StoreFormat.PASSAGE)
        val seedGit = GitSync.init(seedDir.absolutePath, StoreFormat.PASSAGE)
        seedStore.writeEntry("shared", "seed-version\n".toByteArray(Charsets.UTF_8), sharedBackend)
        seedGit.commitPaths(listOf("shared.age"), commitMessageAdd("shared"))
        seedGit.setRemote(remote.absolutePath)
        seedGit.push()

        // "This device": AppViewModel, real production code path, clones
        // the shared store into place via cloneReplaceStore.
        StorePaths.setFormat(appContext(), StoreFormat.PASSAGE)
        StorePaths.storeRoot(appContext(), StoreFormat.PASSAGE).deleteRecursively()
        AgeIdentityStore.identitiesFile(appContext()).apply {
            delete()
            writeText(sharedIdentitiesText, Charsets.UTF_8)
        }
        val appViewModel = AppViewModel(appContext())
        appViewModel.cloneReplaceStore(remote.absolutePath)
        assertEquals("seed-version\n", String(appViewModel.readEntry("shared")!!, Charsets.UTF_8))

        // "Other device": a second clone, edits "shared" differently, pushes.
        val otherDir = freshDir("conflict-other")
        val otherGit = GitSync.cloneFrom(remote.absolutePath, otherDir.absolutePath, null)
        configureIdentity(otherDir)
        val otherStore = PassStore.open(otherDir.absolutePath, StoreFormat.PASSAGE)
        otherStore.writeEntry("shared", "other-device-version\n".toByteArray(Charsets.UTF_8), sharedBackend)
        otherGit.commitPaths(listOf("shared.age"), commitMessageEdit("shared"))
        otherGit.push()

        // "This device" edits the same entry differently, but hasn't
        // pushed yet -- the two histories have now diverged on one file.
        appViewModel.saveEntry("shared", "this-device-version\n".toByteArray(Charsets.UTF_8), isNew = false)

        // Sync now: the rebase hits exactly one conflicted file, and the
        // BlockingResolver bridge answers it from a separate thread the
        // same way SyncScreen's conflict dialog would.
        val resolver = BlockingResolver()
        val watcher = Thread {
            if (waitUntilConflict(resolver)) {
                resolver.resolve(ConflictChoice.KEEP_BOTH)
            }
        }
        watcher.start()
        val outcome = appViewModel.syncNow(resolver)
        watcher.join(2000)

        val resolved = (outcome as? SyncOutcome.ResolvedConflicts)
            ?: throw AssertionError("expected ResolvedConflicts, got $outcome")
        assertEquals(listOf("shared.age"), resolved.resolved)
        assertEquals(listOf("shared.conflict.age"), resolved.keptBoth)

        // Remote wins the name; the local edit survives beside it.
        assertEquals("other-device-version\n", String(appViewModel.readEntry("shared")!!, Charsets.UTF_8))
        assertEquals(
            "this-device-version\n",
            String(appViewModel.readEntry("shared.conflict")!!, Charsets.UTF_8)
        )
        assertTrue(appViewModel.entries.value.any { it.name == "shared.conflict" })

        // Push the resolution; the other device should see both with no
        // conflict on its own next sync.
        appViewModel.pushToRemote()
        assertEquals(0u, appViewModel.syncStatus.value?.ahead)
    }
}
