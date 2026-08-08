package com.passpony.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.passpony.android.store.UnlockGate
import com.passpony.android.store.UnlockGateLogic
import com.passpony.android.ui.LockScreen
import com.passpony.android.ui.PassPonyNavGraph
import com.passpony.android.ui.theme.PassPonyTheme

/**
 * Single activity, Compose navigation. FragmentActivity (bumped from
 * ComponentActivity this packet) since BiometricPrompt needs a
 * FragmentManager host. LockScreen gates PassPonyNavGraph until
 * UnlockGate.isFresh(); P14's onboarding carousel will gate the very
 * first launch ahead of both, once it lands.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PassPonyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    var locked by remember { mutableStateOf(!UnlockGate.isFresh(context)) }

                    // Re-checked on every ON_START -- covers a lapsed grace
                    // window after backgrounding as well as a fresh process
                    // start (ON_START always fires before the first frame,
                    // so this and the `remember` above agree at launch). A
                    // still-fresh window leaves `locked` alone rather than
                    // force-unlocking, so a panic-lock triggered moments
                    // before backgrounding survives a fast switch back.
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (UnlockGateLogic.shouldRelock(event, UnlockGate.isFresh(context))) {
                                locked = true
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    if (locked) {
                        LockScreen(onUnlock = { locked = false })
                    } else {
                        PassPonyNavGraph(
                            onLock = {
                                UnlockGate.lock(context)
                                locked = true
                            }
                        )
                    }
                }
            }
        }
    }
}
