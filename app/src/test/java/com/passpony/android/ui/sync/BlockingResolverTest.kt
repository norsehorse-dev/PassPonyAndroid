package com.passpony.android.ui.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pass_ffi.ConflictChoice

/**
 * The threading contract is the whole point of this class: choose()
 * must genuinely block the calling thread until resolve() is called,
 * and must return exactly what resolve() was given. A plain JVM test
 * with a raw Thread exercises that directly -- no Android framework or
 * coroutine dispatcher swap needed, since CompletableDeferred/
 * runBlocking are pure kotlinx-coroutines-core.
 */
class BlockingResolverTest {

    @Test
    fun choose_blocksUntilResolve_thenReturnsThatChoice() {
        val resolver = BlockingResolver()
        val result = AtomicReference<ConflictChoice?>(null)
        val finished = CountDownLatch(1)

        val worker = Thread {
            result.set(resolver.choose("web/shared"))
            finished.countDown()
        }
        worker.start()

        assertTrue("conflictPath never became visible", waitUntil { resolver.conflictPath == "web/shared" })
        assertFalse("choose() returned before resolve() was called", finished.await(200, TimeUnit.MILLISECONDS))

        resolver.resolve(ConflictChoice.KEEP_BOTH)

        assertTrue("choose() never returned after resolve()", finished.await(2, TimeUnit.SECONDS))
        assertEquals(ConflictChoice.KEEP_BOTH, result.get())
        assertNull(resolver.conflictPath)
        worker.join(2000)
    }

    @Test
    fun choose_calledAgainAfterResolve_asksAgainIndependently() {
        val resolver = BlockingResolver()
        assertEquals(ConflictChoice.KEEP_LOCAL, blockingChoose(resolver, "a", ConflictChoice.KEEP_LOCAL))
        assertEquals(ConflictChoice.KEEP_REMOTE, blockingChoose(resolver, "b", ConflictChoice.KEEP_REMOTE))
    }

    @Test
    fun resolve_withNothingPending_isANoOp() {
        val resolver = BlockingResolver()
        resolver.resolve(ConflictChoice.KEEP_LOCAL)
        assertNull(resolver.conflictPath)
    }

    /** Runs one choose()/resolve() round trip on a worker thread and returns the result. */
    private fun blockingChoose(resolver: BlockingResolver, path: String, answer: ConflictChoice): ConflictChoice {
        val result = AtomicReference<ConflictChoice?>(null)
        val finished = CountDownLatch(1)
        Thread {
            result.set(resolver.choose(path))
            finished.countDown()
        }.start()
        assertTrue(waitUntil { resolver.conflictPath == path })
        resolver.resolve(answer)
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        return result.get()!!
    }

    private fun waitUntil(timeoutMs: Long = 2000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return false
    }
}
