package com.passpony.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.passpony.android.R
import com.passpony.android.crypto.PassphraseCache
import com.passpony.android.crypto.PgpKeySource
import com.passpony.android.crypto.PgpKeyStore
import com.passpony.android.crypto.ProviderConnection
import com.passpony.android.crypto.ProviderOps
import com.passpony.android.crypto.ProviderRunner
import com.passpony.android.crypto.rememberProviderRunner
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.theme.SectionHeader
import kotlinx.coroutines.launch

/** One decrypt through the provider, or a permission check when the store has no entry yet. */
private suspend fun testProvider(
    appViewModel: AppViewModel,
    runner: ProviderRunner,
    source: PgpKeySource.Provider,
): Pair<Result<Unit>, Boolean> {
    val context = appViewModel.getApplication<android.app.Application>()
    val name = appViewModel.entries.value.firstOrNull { !it.hidden }?.name
    val outcome = runner.run {
        if (name == null) {
            ProviderOps.checkPermission(context, source)
        } else {
            appViewModel.readEntryOrThrow(name).fill(0)
        }
    }
    return outcome to (name != null)
}

/**
 * 3.0: the chooser for a key app, shared by Settings and onboarding.
 * Returns the function that opens it.
 *
 * Choosing an app records its signing certificate and asks it for access
 * (its own consent screen) before anything switches, so a refusal leaves
 * the store on the keys it had. After the switch one entry is decrypted
 * through the app, and only when that worked, and PassPony still holds
 * imported key files, does it offer to delete them. Never on its own.
 * [onResult] gets the source now in use and a message, or null.
 */
@Composable
fun rememberKeyAppChooser(
    appViewModel: AppViewModel,
    onBusy: (Boolean) -> Unit = {},
    onResult: (PgpKeySource, String?) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberProviderRunner()
    var choosing by remember { mutableStateOf(false) }
    var offerDelete by remember { mutableStateOf<PgpKeySource.Provider?>(null) }

    fun use(packageName: String) {
        val certificate = ProviderConnection.certificateSha256(context, packageName)
        if (certificate == null) {
            onResult(appViewModel.keySource(), context.getString(R.string.key_source_not_installed))
            return
        }
        val chosen = PgpKeySource.Provider(packageName, certificate)
        val label = ProviderConnection.label(context, packageName)
        onBusy(true)
        scope.launch {
            ProviderConnection.disconnect(context)
            val asked = runner.run { ProviderOps.checkPermission(context, chosen) }
            if (asked.isFailure) {
                ProviderConnection.disconnect(context)
                onBusy(false)
                onResult(appViewModel.keySource(), asked.exceptionOrNull()?.ponyMessage(context))
                return@launch
            }
            appViewModel.useKeySource(chosen)
            val (tested, decrypted) = testProvider(appViewModel, runner, chosen)
            onBusy(false)
            val hasKeyFiles = PgpKeyStore.keyDirectory(context).listFiles()?.any { it.isFile } == true
            when {
                tested.isFailure -> onResult(
                    chosen,
                    context.getString(
                        R.string.key_source_switched_untested, label,
                        tested.exceptionOrNull()?.ponyMessage(context).orEmpty(),
                    ),
                )
                decrypted && hasKeyFiles -> {
                    offerDelete = chosen
                    onResult(chosen, context.getString(R.string.key_source_switched, label))
                }
                else -> onResult(chosen, context.getString(R.string.key_source_switched, label))
            }
        }
    }

    if (choosing) {
        val installed = remember { ProviderConnection.installedProviders(context) }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.key_source_choose_title)) },
            text = {
                Column {
                    if (installed.isEmpty()) {
                        Text(stringResource(R.string.key_source_none_installed))
                        TextButton(onClick = {
                            choosing = false
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://pgpony.app"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text(stringResource(R.string.key_source_get_pgpony)) }
                    } else {
                        Text(stringResource(R.string.key_source_choose_body))
                    }
                    for (packageName in installed) {
                        TextButton(onClick = {
                            choosing = false
                            use(packageName)
                        }) { Text(ProviderConnection.label(context, packageName)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    offerDelete?.let { chosen ->
        val label = ProviderConnection.label(context, chosen.packageName)
        AlertDialog(
            onDismissRequest = { offerDelete = null },
            title = { Text(stringResource(R.string.key_source_delete_title)) },
            text = { Text(stringResource(R.string.key_source_delete_body, label)) },
            confirmButton = {
                TextButton(onClick = {
                    offerDelete = null
                    PgpKeyStore.keyDirectory(context).listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                    PassphraseCache.clear(context)
                    onResult(chosen, context.getString(R.string.key_source_deleted))
                }) { Text(stringResource(R.string.key_source_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { offerDelete = null }) {
                    Text(stringResource(R.string.key_source_delete_keep))
                }
            },
        )
    }

    return { choosing = true }
}

/**
 * 3.0: where a pass store's OpenPGP keys are kept. Either PassPony's own
 * imported key files or one other app (PGPony, OpenKeychain) that holds
 * the keys and does the decrypting and encrypting. [onChanged] is called
 * with the source now in use whenever it may have changed.
 */
@Composable
fun KeySourceSection(
    appViewModel: AppViewModel,
    source: PgpKeySource,
    onChanged: (PgpKeySource) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberProviderRunner()
    var busy by remember { mutableStateOf(false) }
    // Shown here, next to the buttons that produced it. Settings' own
    // message line is at the very bottom of a long screen.
    var result by remember { mutableStateOf<String?>(null) }

    val choose = rememberKeyAppChooser(
        appViewModel = appViewModel,
        onBusy = {
            busy = it
            if (it) result = null
        },
        onResult = { now, text ->
            result = text
            onChanged(now)
        },
    )

    SectionHeader(stringResource(R.string.key_source_header))
    when (source) {
        is PgpKeySource.BuiltIn -> {
            Text(stringResource(R.string.key_source_builtin), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = choose, enabled = !busy) {
                Text(stringResource(R.string.key_source_use_app))
            }
        }
        is PgpKeySource.Provider -> {
            val label = ProviderConnection.label(context, source.packageName)
            val version = ProviderConnection.version(context, source.packageName)
            val certificate = ProviderConnection.certificateSha256(context, source.packageName)
            // Each way the app can be "not there" gets its own sentence.
            // The entry list still shows: names are not encrypted.
            val usable = version != null && certificate == source.certSha256
            Text(
                when {
                    version == null -> stringResource(R.string.key_source_app_missing, label)
                    certificate != source.certSha256 -> stringResource(R.string.key_source_app_changed, label)
                    else -> stringResource(R.string.key_source_app, label, version)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (usable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            if (usable) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        result = null
                        busy = true
                        scope.launch {
                            val (outcome, decrypted) = testProvider(appViewModel, runner, source)
                            busy = false
                            result = outcome.fold(
                                {
                                    if (decrypted) {
                                        context.getString(R.string.key_source_test_ok, label)
                                    } else {
                                        context.getString(R.string.key_source_test_connected, label)
                                    }
                                },
                                { it.ponyMessage(context) },
                            )
                        }
                    },
                ) { Text(stringResource(R.string.key_source_test)) }
            }
            TextButton(onClick = choose, enabled = !busy) {
                Text(
                    if (usable) {
                        stringResource(R.string.key_source_change_app)
                    } else {
                        stringResource(R.string.key_source_choose_again)
                    }
                )
            }
            TextButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        appViewModel.useKeySource(PgpKeySource.BuiltIn)
                        result = context.getString(R.string.key_source_switched_builtin)
                        onChanged(PgpKeySource.BuiltIn)
                    }
                },
            ) { Text(stringResource(R.string.key_source_use_builtin)) }
            Text(
                stringResource(R.string.key_source_footer, label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (busy) {
        Text(
            stringResource(R.string.key_source_working),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    result?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
