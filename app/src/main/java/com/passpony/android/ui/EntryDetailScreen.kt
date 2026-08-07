package com.passpony.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.passpony.android.R

/**
 * Placeholder for the real entry detail screen (password reveal, TOTP
 * ring, fields, edit) that P07 replaces this with. Proves navigation
 * from the browse screens reaches a concrete entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(name: String) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(name.substringAfterLast('/')) }) }
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.entry_detail_placeholder))
        }
    }
}
