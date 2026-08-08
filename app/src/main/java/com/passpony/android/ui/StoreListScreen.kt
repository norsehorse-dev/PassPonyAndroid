package com.passpony.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.passpony.android.R
import com.passpony.android.store.BrowseModel
import com.passpony.android.ui.edit.DeleteEntryDialog
import uniffi.pass_ffi.EntryRef

/** Root store browser: unpushed banner, search, folder-level browsing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreListScreen(navController: NavHostController, viewModel: AppViewModel = viewModel(), onLock: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.openStore() }

    val visibleEntries by viewModel.visibleEntries.collectAsState()
    val searchText by viewModel.searchText.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.store_list_title)) },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.SYNC) }) {
                        Icon(Icons.Filled.Sync, contentDescription = stringResource(R.string.store_list_sync))
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.store_list_settings))
                    }
                    IconButton(onClick = { navController.navigate(Routes.ADD_ENTRY) }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.store_list_add))
                    }
                    var menuExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.store_list_more))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.store_list_lock_now)) },
                            onClick = {
                                menuExpanded = false
                                onLock()
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = searchText,
                onValueChange = viewModel::setSearchText,
                label = { Text(stringResource(R.string.store_list_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )

            if ((syncStatus?.ahead ?: 0u) > 0u) {
                UnpushedBanner((syncStatus?.ahead ?: 0u).toInt())
            }

            if (visibleEntries.isEmpty()) {
                EmptyState()
            } else if (searchText.isEmpty()) {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    browseLevel(
                        visibleEntries,
                        prefix = "",
                        navController = navController,
                        onDeleteRequest = { pendingDelete = it }
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(visibleEntries, key = { it.name }) { entry ->
                        EntryRow(
                            name = entry.name,
                            showFolder = true,
                            onClick = { navController.navigate(Routes.entry(entry.name)) },
                            onDeleteRequest = { pendingDelete = entry.name }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { name ->
        DeleteEntryDialog(
            name = name,
            onConfirm = {
                viewModel.deleteEntry(name)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** One drilled-in folder level, reached from a folder row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(path: String, navController: NavHostController, viewModel: AppViewModel = viewModel()) {
    val visibleEntries by viewModel.visibleEntries.collectAsState()
    val title = path.substringAfterLast('/')
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            browseLevel(
                visibleEntries,
                prefix = "$path/",
                navController = navController,
                onDeleteRequest = { pendingDelete = it }
            )
        }
    }

    pendingDelete?.let { name ->
        DeleteEntryDialog(
            name = name,
            onConfirm = {
                viewModel.deleteEntry(name)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** Subfolders first, then entries, both sorted; shared by the root and folder screens. */
private fun LazyListScope.browseLevel(
    visibleEntries: List<EntryRef>,
    prefix: String,
    navController: NavHostController,
    onDeleteRequest: (String) -> Unit
) {
    val level = BrowseModel.level(visibleEntries, prefix)

    if (level.folders.isNotEmpty()) {
        items(level.folders, key = { "folder:$it" }) { folder ->
            FolderRow(
                name = folder,
                count = BrowseModel.countInFolder(visibleEntries, prefix, folder),
                onClick = { navController.navigate(Routes.folder(prefix + folder)) }
            )
        }
    }

    if (level.entries.isNotEmpty()) {
        items(level.entries, key = { "entry:$it" }) { leaf ->
            EntryRow(
                name = prefix + leaf,
                showFolder = false,
                onClick = { navController.navigate(Routes.entry(prefix + leaf)) },
                onDeleteRequest = { onDeleteRequest(prefix + leaf) }
            )
        }
    }
}

@Composable
private fun UnpushedBanner(ahead: Int) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.elevatedCardColors()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = null)
            Text(stringResource(R.string.store_list_unpushed, ahead))
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.store_list_no_entries), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.store_list_no_entries_hint),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun FolderRow(name: String, count: Int, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
            Text(name, modifier = Modifier.weight(1f))
            Text(count.toString(), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}

@Composable
private fun EntryRow(
    name: String,
    showFolder: Boolean,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val leaf = name.substringAfterLast('/')
    val folder = name.substringBeforeLast('/', missingDelimiterValue = "")

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.VpnKey, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(leaf)
                if (showFolder && folder.isNotEmpty()) {
                    Text(folder, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onDeleteRequest) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.store_list_delete_entry))
            }
        }
        HorizontalDivider()
    }
}
