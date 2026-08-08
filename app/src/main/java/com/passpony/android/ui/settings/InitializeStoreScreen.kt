package com.passpony.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.passpony.android.R
import com.passpony.android.crypto.PgpKeyStore
import com.passpony.android.store.StorePaths
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.util.SecureScreenEffect
import uniffi.pass_ffi.StoreFormat

/**
 * Port of PassPony iOS's InitializeStoreView: `pass init`, app-shaped --
 * pick one or more imported keys and write the store's root `.gpg-id`
 * with their primary fingerprints. Offered only when no `.gpg-id`
 * exists yet; changing the key set of a live store is the re-encrypt
 * flow (ReencryptScreen), not this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitializeStoreScreen(
    appViewModel: AppViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    // Shows imported keys' fingerprints -- key material, same standard
    // as SettingsScreen and EntryDetailScreen.
    SecureScreenEffect()

    var keys by remember { mutableStateOf(emptyList<PgpKeyStore.KeyFileInfo>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { keys = PgpKeyStore.availableKeys(context) }

    fun toggle(file: String) {
        selected = if (selected.contains(file)) selected - file else selected + file
    }

    fun initialize() {
        try {
            val root = StorePaths.storeRoot(context, StoreFormat.PASS)
            root.mkdirs()
            val gpgId = StorePaths.gpgIdFile(context)
            if (gpgId.exists()) {
                error = context.getString(R.string.init_store_already_exists)
                return
            }
            val fingerprints = keys.filter { selected.contains(it.file) }.map { it.primaryFingerprint }
            gpgId.writeText(KeySummary.formatGpgId(fingerprints))
            appViewModel.openStore()
            onDone()
        } catch (e: Exception) {
            error = e.ponyMessage(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.init_store_title)) },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                },
                actions = {
                    TextButton(onClick = { initialize() }, enabled = selected.isNotEmpty()) {
                        Text(stringResource(R.string.init_store_confirm))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                stringResource(R.string.init_store_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))

            if (keys.isEmpty()) {
                Text(
                    stringResource(R.string.init_store_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            for (key in keys) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggle(key.file) }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(key.file)
                        Text(
                            KeySummary.summaryLine(key),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    if (selected.contains(key.file)) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.init_store_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
