package com.passpony.android.crypto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.util.OpenPgpApi
import uniffi.pass_ffi.CryptoException

/**
 * 3.0: the one binding to the chosen OpenPGP provider, shared by the app,
 * autofill and the passkey provider.
 *
 * - Binds explicitly to the chosen package, never by an implicit intent.
 * - Before binding, the package's signing certificate must still be the
 *   one recorded when the person chose it.
 * - One [OpenPgpApi] per binding. Its pipe counter is what keeps two
 *   calls' output apart; a client per call gives concurrent calls the same
 *   pipe id and the provider mixes them up (found in the rc7 spike).
 * - The system redelivers the binding when the provider's process dies
 *   and comes back, so a killed provider recovers on the next call.
 */
object ProviderConnection {
    private const val BIND_TIMEOUT_SECONDS = 5L

    private val lock = Any()
    private var boundPackage: String? = null
    private var connection: ServiceConnection? = null
    private var api: OpenPgpApi? = null
    private var ready = CountDownLatch(1)

    /** Packages that offer the provider service. */
    fun installedProviders(context: Context): List<String> =
        context.packageManager.queryIntentServices(Intent(OpenPgpApi.SERVICE_INTENT_2), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
            .sorted()

    /** Lowercase hex SHA-256 of the package's signing certificate, or null. */
    fun certificateSha256(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return null
        MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Start binding to [source] if that is not already under way. Returns at once. */
    fun connect(context: Context, source: PgpKeySource.Provider) {
        runCatching { ensureBinding(context.applicationContext, source) }
    }

    /**
     * The client for [source]. Waits for the binding when called off the
     * main thread. On the main thread it cannot wait (the binding is
     * delivered there), so a call that arrives before the provider has
     * connected fails with a message to try again.
     */
    fun api(context: Context, source: PgpKeySource.Provider): OpenPgpApi {
        val latch = ensureBinding(context.applicationContext, source)
        synchronized(lock) { api }?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw CryptoException.Unavailable(reason = "Still connecting to your key app. Try again.")
        }
        latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return synchronized(lock) { api }
            ?: throw CryptoException.Unavailable(reason = "Your key app did not answer.")
    }

    /** Drop the binding, for when the key source changes. */
    fun disconnect(context: Context) {
        synchronized(lock) {
            connection?.let { runCatching { context.applicationContext.unbindService(it) } }
            connection = null
            api = null
            boundPackage = null
            ready = CountDownLatch(1)
        }
    }

    private fun ensureBinding(context: Context, source: PgpKeySource.Provider): CountDownLatch {
        synchronized(lock) {
            if (connection != null && boundPackage == source.packageName) return ready
        }
        val current = certificateSha256(context, source.packageName)
            ?: throw CryptoException.Unavailable(reason = "Your key app is not installed.")
        if (current != source.certSha256) {
            throw CryptoException.Unavailable(
                reason = "Your key app changed since you chose it. Choose it again in Settings."
            )
        }
        synchronized(lock) {
            if (connection != null && boundPackage == source.packageName) return ready
            connection?.let { runCatching { context.unbindService(it) } }
            api = null
            ready = CountDownLatch(1)
            val next = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    synchronized(lock) {
                        if (connection !== this) return
                        api = OpenPgpApi(context, IOpenPgpService2.Stub.asInterface(service))
                        ready.countDown()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // The provider's process died. The binding stays and
                    // the system calls onServiceConnected again when it is
                    // back; until then there is no client.
                    synchronized(lock) {
                        if (connection !== this) return
                        api = null
                        ready = CountDownLatch(1)
                    }
                }

                override fun onBindingDied(name: ComponentName) {
                    synchronized(lock) {
                        if (connection !== this) return
                        runCatching { context.unbindService(this) }
                        connection = null
                        api = null
                        boundPackage = null
                        ready = CountDownLatch(1)
                    }
                }
            }
            val intent = Intent(OpenPgpApi.SERVICE_INTENT_2).setPackage(source.packageName)
            val started = runCatching { context.bindService(intent, next, Context.BIND_AUTO_CREATE) }
                .getOrDefault(false)
            if (!started) {
                runCatching { context.unbindService(next) }
                throw CryptoException.Unavailable(reason = "Could not connect to your key app.")
            }
            connection = next
            boundPackage = source.packageName
            return ready
        }
    }
}
