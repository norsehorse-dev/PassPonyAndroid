package com.passpony.android.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pass_ffi.entryAddPasskey
import uniffi.pass_ffi.entryPasskeys
import uniffi.pass_ffi.entryRemovePasskey
import uniffi.pass_ffi.passkeyAssert
import uniffi.pass_ffi.passkeyRegister
import uniffi.pass_ffi.passkeySignedMessage

/**
 * 2.0 rc3 on the device: a passkey made with this platform's software EC
 * key round-trips through the core and the entry, and its sign-in
 * signature verifies against the public key the relying party got at
 * registration (the SPKI in the response). This is the java.security half
 * of the core's own ES256 tests.
 */
@RunWith(AndroidJUnit4::class)
class PasskeyCryptoTest {

    private fun coordinate(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    @Test
    fun registerStoreAndSignIn() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val point = (pair.public as ECPublicKey).w
        val registration = passkeyRegister(
            rpId = "webauthn.io",
            userHandle = byteArrayOf(1, 2, 3),
            username = "kevin",
            userDisplayName = "Kevin",
            keyPkcs8 = pair.private.encoded,
            x = coordinate(point.affineX),
            y = coordinate(point.affineY),
            userVerified = true,
        )
        // The SPKI the site receives is this key's public half.
        val spki = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(registration.publicKeySpki))
        assertArrayEquals(pair.public.encoded, spki.encoded)

        // Stored in an entry, read back, removed again.
        val content = entryAddPasskey(ByteArray(0), registration.value)
        val stored = entryPasskeys(content).single()
        assertEquals("webauthn.io", stored.rpId)
        assertArrayEquals(registration.credentialId, stored.credentialId)
        assertTrue(entryPasskeys(entryRemovePasskey(content, stored.credentialId)).isEmpty())

        // Sign in: import the stored PKCS#8, sign, verify with the SPKI.
        val assertion = passkeyAssert(stored.value, userVerified = true)
        val clientDataHash = MessageDigest.getInstance("SHA-256").digest("{}".toByteArray())
        val message = passkeySignedMessage(assertion.authenticatorData, clientDataHash)
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(assertion.keyPkcs8))
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(message)
            sign()
        }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(spki)
        verifier.update(message)
        assertTrue(verifier.verify(signature))
        assertArrayEquals(byteArrayOf(1, 2, 3), assertion.userHandle)
    }
}
