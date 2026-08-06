package com.passpony.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.passpony.android.ui.PassPonyNavGraph
import com.passpony.android.ui.theme.PassPonyTheme

/**
 * Single activity, Compose navigation. P11's LockScreen gates
 * PassPonyNavGraph once the unlock gate lands; P14's onboarding carousel
 * takes its place on a fresh install. Neither exists yet, so this composes
 * the nav graph directly.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PassPonyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PassPonyNavGraph()
                }
            }
        }
    }
}
