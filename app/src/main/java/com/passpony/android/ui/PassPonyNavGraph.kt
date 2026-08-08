package com.passpony.android.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.passpony.android.ui.detail.EntryDetailScreen
import com.passpony.android.ui.edit.AddEntryScreen
import com.passpony.android.ui.edit.EditEntryScreen
import com.passpony.android.ui.edit.MoveEntryScreen
import com.passpony.android.ui.settings.InitializeStoreScreen
import com.passpony.android.ui.settings.ReencryptScreen
import com.passpony.android.ui.settings.SettingsScreen
import com.passpony.android.ui.sync.SyncScreen

object Routes {
    const val STORE_LIST = "store_list"
    const val FOLDER_PATTERN = "folder/{path}"
    const val ENTRY_PATTERN = "entry/{name}"
    const val ADD_ENTRY = "add_entry"
    const val EDIT_ENTRY_PATTERN = "entry/{name}/edit"
    const val MOVE_ENTRY_PATTERN = "entry/{name}/move"
    const val SYNC = "sync"
    const val SETTINGS = "settings"
    const val INITIALIZE_STORE = "settings/initialize_store"
    const val REENCRYPT = "settings/reencrypt"

    /** Path segments can contain slashes; encode so the route stays one segment. */
    fun folder(path: String): String = "folder/" + Uri.encode(path)
    fun entry(name: String): String = "entry/" + Uri.encode(name)
    fun editEntry(name: String): String = "entry/" + Uri.encode(name) + "/edit"
    fun moveEntry(name: String): String = "entry/" + Uri.encode(name) + "/move"
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
            EntryDetailScreen(name, appViewModel, navController)
        }
        composable(Routes.ADD_ENTRY) {
            AddEntryScreen(appViewModel, onDone = { navController.popBackStack() })
        }
        composable(Routes.EDIT_ENTRY_PATTERN) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name").orEmpty())
            EditEntryScreen(name, appViewModel, onDone = { navController.popBackStack() })
        }
        composable(Routes.MOVE_ENTRY_PATTERN) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name").orEmpty())
            MoveEntryScreen(
                currentName = name,
                viewModel = appViewModel,
                onMoved = {
                    // Pop the move screen and the now-stale detail screen
                    // underneath it, landing back on whichever list/folder
                    // screen was open before -- matches iOS's
                    // MoveEntryView dismiss() + onMoved { dismiss() }.
                    navController.popBackStack()
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(Routes.SYNC) {
            SyncScreen(appViewModel, onDone = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                appViewModel,
                onDone = { navController.popBackStack() },
                onInitializeStore = { navController.navigate(Routes.INITIALIZE_STORE) },
                onReencrypt = { navController.navigate(Routes.REENCRYPT) },
            )
        }
        composable(Routes.INITIALIZE_STORE) {
            InitializeStoreScreen(
                appViewModel,
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(Routes.REENCRYPT) {
            ReencryptScreen(appViewModel, onDone = { navController.popBackStack() })
        }
    }
}
