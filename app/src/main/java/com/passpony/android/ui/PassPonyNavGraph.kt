package com.passpony.android.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.passpony.android.ui.detail.EntryDetailScreen

object Routes {
    const val STORE_LIST = "store_list"
    const val FOLDER_PATTERN = "folder/{path}"
    const val ENTRY_PATTERN = "entry/{name}"

    // P07 to P10 add their own destinations here as those packets land.

    /** Path segments can contain slashes; encode so the route stays one segment. */
    fun folder(path: String): String = "folder/" + Uri.encode(path)
    fun entry(name: String): String = "entry/" + Uri.encode(name)
}

@Composable
fun PassPonyNavGraph() {
    val navController = rememberNavController()
    // Created here rather than inside each destination: androidx.navigation
    // scopes a default viewModel() call to that destination's own
    // back-stack entry, so StoreListScreen and FolderScreen would each get
    // a separate, never-opened AppViewModel if they called viewModel()
    // themselves. Sharing one instance across the graph is what makes
    // opening the store in StoreListScreen visible from FolderScreen.
    val appViewModel: AppViewModel = viewModel()
    NavHost(navController = navController, startDestination = Routes.STORE_LIST) {
        composable(Routes.STORE_LIST) {
            StoreListScreen(navController, appViewModel)
        }
        composable(Routes.FOLDER_PATTERN) { backStackEntry ->
            val path = Uri.decode(backStackEntry.arguments?.getString("path").orEmpty())
            FolderScreen(path, navController, appViewModel)
        }
        composable(Routes.ENTRY_PATTERN) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name").orEmpty())
            EntryDetailScreen(name, appViewModel)
        }
    }
}
