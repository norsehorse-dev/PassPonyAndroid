package com.passpony.android.crypto.fixtures

import com.pgpony.android.crypto.KeyAlgorithm
import com.pgpony.android.crypto.PGPCryptoService
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Not a real test: a fixture generator gated behind GENERATE_FIXTURES=1
 * (see scripts/make-v6-fixture.sh), so it never runs as part of the
 * normal test suite and does nothing on a plain `./gradlew test`.
 *
 * No external tool in this toolchain can mint an RFC 9580 v6 key: the
 * installed GnuPG (2.2.27) predates v6 support and sequoia-sq is not
 * installed. PGPonyCore-Kotlin can generate v6 keys itself (the same
 * buildV6Ed25519X25519KeyRings path PGPonyAndroid's key-gen UI uses), so
 * this generates and encrypts through the library under test rather than
 * an external one. Unlike the RSA fixture (real gpg, a genuine interop
 * check), this is self-consistency only — revisit with a real
 * externally-generated v6 fixture once sq or a v6-capable gpg is
 * available on this toolchain.
 *
 * Writes two fixture stores:
 *  - pass-v6-minimal: unprotected secret key, decrypts immediately.
 *  - pass-v6-locked: passphrase-protected secret key (fixed fake
 *    passphrase, committed in the open like the RSA fixture's fake
 *    credentials), for the locked-key behavior test.
 */
class GenerateV6FixtureTest {
    private val goldens = listOf(
        "alpha" to "v6-fixture-alpha-password",
        "beta" to "v6-fixture-beta-password",
        "gamma" to "v6-fixture-gamma-password",
    )

    @Test
    fun generateUnlocked() {
        assumeTrue(System.getenv("GENERATE_FIXTURES") == "1")
        write(outDirName = "pass-v6-minimal", passphrase = null)
    }

    @Test
    fun generateLocked() {
        assumeTrue(System.getenv("GENERATE_FIXTURES") == "1")
        write(outDirName = "pass-v6-locked", passphrase = LOCKED_FIXTURE_PASSPHRASE)
    }

    private fun write(outDirName: String, passphrase: String?) {
        val generated = PGPCryptoService.shared.generateKeyPair(
            name = "PassPony V6 Fixture",
            email = "v6@passpony.test",
            algorithm = KeyAlgorithm.V6_ED25519,
            passphrase = passphrase,
        )
        val recipientRing = PGPCryptoService.shared
            .importArmoredKey(generated.armoredPublicKey)
            .publicKeyRing!!

        val outDir = File("src/androidTest/assets/$outDirName")
        val storeDir = File(outDir, "store").apply { mkdirs() }
        val goldensDir = File(outDir, "goldens").apply { mkdirs() }

        File(storeDir, ".gpg-id").writeText(generated.fingerprint)
        File(outDir, "identity.asc").writeText(generated.armoredPrivateKey)

        for ((name, plain) in goldens) {
            File(goldensDir, "$name.plain").writeText(plain)
            val ciphertext = PGPCryptoService.shared.encrypt(
                data = plain.toByteArray(Charsets.UTF_8),
                recipientPublicKeys = listOf(recipientRing),
                armor = false,
            )
            File(storeDir, "$name.gpg").writeBytes(ciphertext)
        }

        println("v6 fixture written to $outDir (fingerprint ${generated.fingerprint})")
    }

    companion object {
        /** Test-only, committed in the open like the RSA fixture's fake
         * credentials — never a real secret. */
        const val LOCKED_FIXTURE_PASSPHRASE = "fixture-not-a-real-passphrase"
    }
}
