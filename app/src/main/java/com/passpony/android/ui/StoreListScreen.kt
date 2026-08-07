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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.passpony.android.R
import com.passpony.android.store.BrowseModel
import uniffi.pass_ffi.EntryRef

/** Root store browser: unpushed banner, search, folder-level browsing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreListScreen(navController: NavHostController, viewModel: AppViewModel = viewModel()) {
    LaunchedEffect(Unit) { viewModel.openStore() }

    val visibleEntries by viewModel.visibleEntries.collectAsState()
    val searchText by viewModel.searchText.collectAsState()
    val ahead by viewModel.syncStatusAhead.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.store_list_title)) },
                actions = {
                    // Placeholders: P09 wires sync, P08 wires add. The
                    // settings gear is wired to AppViewModel.debugToggleFormat()
                    // (P06 developer-only pass/passage switch, a no-op in
                    // release builds) until P10 lands a real Settings screen.
                    IconButton(onClick = {}) {
                        Icon(Icons.Filled.Sync, contentDescription = stringResource(R.string.store_list_sync))
                    }
                    IconButton(onClick = viewModel::debugToggleFormat) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.store_list_settings))
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.store_list_add))
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

            if (ahead > 0) {
                UnpushedBanner(ahead)
            }

            if (visibleEntries.isEmpty()) {
                EmptyState()
            } else if (searchText.isEmpty()) {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    browseLevel(visibleEntries, prefix = "", navController = navController)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(visibleEntries, key = { it.name }) { entry ->
                        EntryRow(
                            name = entry.name,
                            showFolder = true,
                            onClick = { navController.navigate(Routes.entry(entry.name)) }
                        )
                    }
                }
            }
        }
    }
}

/** One drilled-in folder level, reached from a folder row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(path: String, navController: NavHostController, viewModel: AppViewModel = viewModel()) {
    val visibleEntries by viewModel.visibleEntries.collectAsState()
    val title = path.substringAfterLast('/')

    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            browseLevel(visibleEntries, prefix = "$path/", navController = navController)
        }
    }
}

/** Subfolders first, then entries, both sorted; shared by the root and folder screens. */
private fun LazyListScope.browseLevel(
    visibleEntries: List<EntryRef>,
    prefix: String,
    navController: NavHostController
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
                onClick = { navController.navigate(Routes.entry(prefix + leaf)) }
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
private fun EntryRow(name: String, showFolder: Boolean, onClick: () -> Unit) {
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
            Column {
                Text(leaf)
                if (showFolder && folder.isNotEmpty()) {
                    Text(folder, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider()
    }
}
