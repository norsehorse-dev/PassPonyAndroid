package com.passpony.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.passpony.android.R

/**
 * Placeholder for the real store browser (folders, search, the unpushed-
 * changes banner) that P03 replaces this with. It exists in P01 only to
 * prove the app shell, theme, and navigation graph all work end to end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreListScreen() {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.store_list_title)) })
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier.padding(innerPadding).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.store_list_empty))
        }
    }
}
