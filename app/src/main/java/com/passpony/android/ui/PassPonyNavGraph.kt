package com.passpony.android.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object Routes {
    const val STORE_LIST = "store_list"
    // P03 adds folder/{path} and entry/{name}; P07 to P10 add their own
    // destinations. One object, growing alongside the packets, rather than
    // scattering route string literals across files.
}

@Composable
fun PassPonyNavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.STORE_LIST) {
        composable(Routes.STORE_LIST) {
            StoreListScreen()
        }
    }
}
