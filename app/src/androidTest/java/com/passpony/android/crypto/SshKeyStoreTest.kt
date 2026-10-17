package com.passpony.android.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.HostKey
import uniffi.pass_ffi.sshGenerateKey

/**
 * 2.0 rc2 on the device: the SSH key survives a Keystore seal and unseal
 * with its fingerprint intact, only the public half sits in plain text,
 * and trusted hosts pin, list and forget. Saves and restores the app's
 * own SSH files around each case.
 */
@RunWith(AndroidJUnit4::class)
class SshKeyStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.filesDir, "ssh")
    private val names = listOf("id.enc", "id.pub", "known_hosts")
    private val saved = mutableMapOf<String, ByteArray>()

    @Before
    fun setUp() {
        for (n in names) {
            val f = File(dir, n)
            if (f.exists()) saved[n] = f.readBytes()
            f.delete()
        }
    }

    @After
    fun tearDown() {
        for (n in names) {
            val f = File(dir, n)
            f.delete()
            saved[n]?.let { f.writeBytes(it) }
        }
    }

    @Test
    fun generatedKeyIsSealedAtRestAndUnsealsIntact() {
        assertFalse(SshKeyStore.hasKey(context))
        assertNull(SshKeyStore.config(context).privateKey)

        val key = SshKeyStore.generate(context)
        assertTrue(SshKeyStore.hasKey(context))
        assertEquals(key.publicKey, SshKeyStore.publicKey(context))
        assertTrue(key.publicKey.endsWith(SshKeyStore.COMMENT))

        val sealed = File(dir, "id.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(sealed.contains("OPENSSH PRIVATE KEY"))

        val loaded = SshKeyStore.load(context)
        assertNotNull(loaded)
        assertEquals(key.fingerprint, loaded!!.fingerprint)
        assertEquals(key.privateKey, SshKeyStore.config(context).privateKey)

        SshKeyStore.delete(context)
        assertFalse(SshKeyStore.hasKey(context))
        assertNull(SshKeyStore.publicKey(context))
    }

    @Test
    fun trustedHostsPinListAndForget() {
        val other = sshGenerateKey("")
        val blob = other.publicKey.split(" ")[1]
        val hostKey = HostKey(keyType = "ssh-ed25519", keyBase64 = blob, fingerprint = other.fingerprint)

        assertFalse(SshKeyStore.isPinned(context, "[git.example.org]:2222"))
        SshKeyStore.trust(context, "[git.example.org]:2222", hostKey)
        assertTrue(SshKeyStore.isPinned(context, "[git.example.org]:2222"))
        val listed = SshKeyStore.trustedHosts(context)
        assertEquals(1, listed.size)
        assertEquals(other.fingerprint, listed[0].key.fingerprint)
        assertTrue(SshKeyStore.config(context).knownHosts.contains("[git.example.org]:2222 ssh-ed25519 $blob"))

        SshKeyStore.forget(context, "[git.example.org]:2222")
        assertFalse(SshKeyStore.isPinned(context, "[git.example.org]:2222"))
    }
}
