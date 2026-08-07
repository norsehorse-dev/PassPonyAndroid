package com.passpony.android.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Requests FLAG_SECURE (blocks screenshots, screen recording, and the
 * recent-apps thumbnail) for as long as this composable is in
 * composition. Refcounted rather than a plain set/clear on enter/leave:
 * P10 (a settings screen showing raw key material) and P12 (autofill)
 * will want the same protection independently, and a plain set/clear
 * would let whichever of two overlapping requests leaves first turn the
 * flag off while the other is still on screen. Per the threat model, any
 * surface that can show plaintext secrets or key material calls this.
 */
private val activeSecureRequests = AtomicInteger(0)

@Composable
fun SecureScreenEffect() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        if (activeSecureRequests.getAndIncrement() == 0) {
            window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (activeSecureRequests.decrementAndGet() == 0) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

/** Compose's LocalContext is often a ContextWrapper (theming, etc.), not
 * the Activity itself; unwrap to find it. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
