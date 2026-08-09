package com.passpony.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.passpony.android.store.StorePaths
import com.passpony.android.store.UnlockGate
import com.passpony.android.store.UnlockGateLogic
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.LockScreen
import com.passpony.android.ui.PassPonyNavGraph
import com.passpony.android.ui.onboarding.OnboardingScreen
import com.passpony.android.ui.theme.PassPonyTheme
import kotlinx.coroutines.launch

/**
 * Single activity, Compose navigation. AppCompatActivity (bumped from
 * FragmentActivity this packet, which it extends, so BiometricPrompt's
 * FragmentManager-host requirement still holds) -- required for
 * AppCompatDelegate.setApplicationLocales() to actually take effect in
 * a Compose app; a plain FragmentActivity silently no-ops per-app
 * language changes (see LanguageManager.kt). OnboardingScreen gates
 * everything else on the very first launch
 * (StorePaths.onboardingCompletedSnapshot); once past it, LockScreen
 * gates PassPonyNavGraph until UnlockGate.isFresh().
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PassPonyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    var showOnboarding by remember {
                        mutableStateOf(!StorePaths.onboardingCompletedSnapshot(context))
                    }
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

                    if (showOnboarding) {
                        // Same viewModel() call PassPonyNavGraph makes below,
                        // called here at the same (Activity-scoped) point in
                        // composition -- both resolve to one shared
                        // AppViewModel instance, so the store onboarding
                        // opens is still open once the nav graph takes over.
                        val appViewModel: AppViewModel = viewModel()
                        OnboardingScreen(
                            appViewModel = appViewModel,
                            onComplete = {
                                scope.launch { StorePaths.setOnboardingCompleted(context, true) }
                                showOnboarding = false
                            }
                        )
                    } else if (locked) {
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
