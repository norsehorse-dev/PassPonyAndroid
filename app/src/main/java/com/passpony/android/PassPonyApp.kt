package com.passpony.android

import android.app.Application
import uniffi.pass_ffi.initTls
import java.io.File

/**
 * Application entry point. On first construction it hands the Rust git core
 * a CA bundle so HTTPS sync can verify certificates.
 *
 * The core's git transport is libgit2 over vendored OpenSSL, which has no
 * Android system trust store to fall back on, so without this every clone,
 * fetch, and push fails with "the SSL certificate is invalid; class=Ssl
 * (16)". We ship the Mozilla CA set as res/raw/cacert.pem, copy it into
 * app-private storage (OpenSSL needs a real filesystem path, not a resource
 * stream), and point the core at it before any sync can run.
 */
class PassPonyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val caFile = File(filesDir, "cacert.pem")
        resources.openRawResource(R.raw.cacert).use { input ->
            caFile.outputStream().use { output -> input.copyTo(output) }
        }
        initTls(caFile.absolutePath)
    }
}
