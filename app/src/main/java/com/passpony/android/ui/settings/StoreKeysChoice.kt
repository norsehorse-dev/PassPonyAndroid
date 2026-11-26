package com.passpony.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.passpony.android.R
import com.passpony.android.crypto.AgeIdentityStore
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.theme.ChoiceField
import com.passpony.android.ui.util.KeyDisplay
import kotlinx.coroutines.launch

/**
 * 3.0: what kind of age keys the passage store uses. While the store is
 * empty and lists no other device it is a choice, post-quantum first;
 * after that it is a fact about the store and only shown. Used by
 * Settings and by the onboarding format slide. Draws nothing for a pass
 * store.
 */
@Composable
fun StoreKeysChoice(
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onMove: (() -> Unit)? = null,
) {
    val keys by appViewModel.storeKeys.collectAsState()
    val current = keys ?: return
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxWidth()) {
        if (current.canChoose) {
            ChoiceField(
                label = stringResource(R.string.store_keys_label),
                selected = current.postQuantum,
                options = listOf(true, false),
                optionLabel = { storeKeysLabel(it) },
                onSelect = { chosen -> scope.launch { appViewModel.chooseStoreKeys(chosen) } }
            )
            Text(
                stringResource(R.string.store_keys_choice_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            Text(
                stringResource(R.string.store_keys_fixed, storeKeysLabel(current.postQuantum)),
                style = MaterialTheme.typography.bodyMedium
            )
            if (current.blocked) {
                Text(
                    stringResource(R.string.error_mixed_recipients),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (onMove != null) {
                if (!current.postQuantum) {
                    TextButton(onClick = onMove) { Text(stringResource(R.string.settings_move_pq)) }
                } else {
                    // A move that did not reach every entry; see MovePostQuantumScreen.
                    val entries by appViewModel.entries.collectAsState()
                    val left by produceState(0, current, entries) { value = appViewModel.classicEntryCount() }
                    if (left > 0) {
                        TextButton(onClick = onMove) { Text(stringResource(R.string.settings_move_pq_finish)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun storeKeysLabel(postQuantum: Boolean): String = stringResource(
    if (postQuantum) R.string.store_keys_post_quantum else R.string.store_keys_classic
)

/**
 * This device's public keys, one row each: the kind, the key (whole when
 * classic, short form when post-quantum) and, for a post-quantum key,
 * the fingerprint another device shows when it asks about this key.
 * Tapping a row copies the whole key. Public keys only, so the plain
 * clipboard is used and nothing is cleared afterwards.
 */
@Composable
fun AgeKeyRows(keys: List<AgeIdentityStore.PublicKey>, onCopied: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    for (item in keys) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    clipboard.setText(AnnotatedString(item.key))
                    onCopied()
                }
                .padding(vertical = 6.dp)
        ) {
            Text(
                storeKeysLabel(item.postQuantum),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                KeyDisplay.short(item.key),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            if (KeyDisplay.isLong(item.key)) {
                Text(
                    stringResource(R.string.key_fingerprint, KeyDisplay.fingerprint(item.key)),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
