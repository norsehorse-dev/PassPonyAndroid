package com.passpony.android.passkey

import android.content.Context
import com.passpony.android.R
import com.passpony.android.crypto.EngineProvider
import com.passpony.android.store.StorePaths
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import uniffi.pass_ffi.GitSync
import uniffi.pass_ffi.PassStore
import uniffi.pass_ffi.PasskeyInfo
import uniffi.pass_ffi.PasskeyRegistration
import uniffi.pass_ffi.commitMessagePasskeyAdd
import uniffi.pass_ffi.entryAddPasskey
import uniffi.pass_ffi.entryPasskeys
import uniffi.pass_ffi.passkeyAssert
import uniffi.pass_ffi.passkeyRegister
import uniffi.pass_ffi.passkeySignedMessage

/**
 * Store access and ES256 for the passkey provider (2.0 rc3). Like
 * AutofillCredential, it opens the store fresh on every call: the provider
 * service and its activities can be the first thing to run in a process.
 *
 * Keys are software P-256 keys from the platform provider, exported as
 * PKCS#8 into the entry; an Android Keystore key could not follow the
 * store to the user's other devices. The core builds every WebAuthn byte;
 * only key generation and signing happen here.
 */
object PasskeyVault {
    private fun store(context: Context): Pair<PassStore, uniffi.pass_ffi.StoreFormat> {
        val format = StorePaths.currentFormatSnapshot(context)
        val root = StorePaths.storeRoot(context, format)
        return PassStore.open(root.absolutePath, format) to format
    }

    fun format(context: Context) = StorePaths.currentFormatSnapshot(context)

    fun entryNames(context: Context): List<String> = store(context).first.entries().map { it.name }

    /**
     * Where a new passkey for [rpId] goes by default: an entry that already
     * holds one for the site, else an entry named after the site (`web/github.com`
     * style, or the bare domain), else the domain itself as a new entry.
     */
    fun suggestedEntry(context: Context, rpId: String): String {
        val format = format(context)
        PasskeyIndex.forRpId(context, format, rpId).firstOrNull()?.let { return it.entryName }
        val names = entryNames(context)
        names.firstOrNull { it == rpId || it.endsWith("/$rpId") }?.let { return it }
        return rpId
    }

    /**
     * Generate a key, build the registration, and add the passkey line to
     * [entryName] (a new entry when it doesn't exist), with one commit.
     * Returns what the relying party gets back.
     */
    fun register(
        context: Context,
        entryName: String,
        rpId: String,
        userHandle: ByteArray,
        username: String,
        userDisplayName: String,
    ): PasskeyRegistration {
        val (store, format) = store(context)
        val engine = EngineProvider.engine(context, format)
        val existing = if (store.hasEntry(entryName)) store.readEntry(entryName, engine) else ByteArray(0)

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val pkcs8 = pair.private.encoded
        val point = (pair.public as ECPublicKey).w
        val registration = try {
            passkeyRegister(
                rpId = rpId,
                userHandle = userHandle,
                username = username,
                userDisplayName = userDisplayName,
                keyPkcs8 = pkcs8,
                x = coordinate(point.affineX),
                y = coordinate(point.affineY),
                userVerified = true,
            )
        } finally {
            pkcs8.fill(0)
        }

        val updated = entryAddPasskey(existing, registration.value)
        existing.fill(0)
        try {
            store.writeEntry(entryName, updated, engine)
            runCatching {
                GitSync.open(StorePaths.storeRoot(context, format).absolutePath).use { git ->
                    git.commitPaths(listOf(StorePaths.entryFileName(entryName, format)), commitMessagePasskeyAdd(entryName))
                }
            }
            PasskeyIndex.updateEntry(context, format, entryName, updated)
        } finally {
            updated.fill(0)
        }
        return registration
    }

    /** The passkey [credentialId] in [entryName], for [rpId], or null. */
    fun find(context: Context, entryName: String, rpId: String, credentialId: ByteArray): PasskeyInfo? {
        val (store, format) = store(context)
        val content = store.readEntry(entryName, EngineProvider.engine(context, format))
        return try {
            entryPasskeys(content).firstOrNull { it.rpId == rpId && it.credentialId.contentEquals(credentialId) }
        } finally {
            content.fill(0)
        }
    }

    /** Sign in with [passkey]: authenticator data, ES256 signature (DER). */
    fun sign(passkey: PasskeyInfo, clientDataHash: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val assertion = passkeyAssert(passkey.value, userVerified = true)
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(assertion.keyPkcs8))
        assertion.keyPkcs8.fill(0)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(passkeySignedMessage(assertion.authenticatorData, clientDataHash))
            sign()
        }
        return Triple(assertion.authenticatorData, signature, assertion.userHandle)
    }

    /** An unsigned BigInteger as exactly 32 big-endian bytes. */
    private fun coordinate(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    /** Message for the "this app's request" decline screens. */
    fun declineMessage(context: Context): String = context.getString(R.string.passkey_browser_only)
}
