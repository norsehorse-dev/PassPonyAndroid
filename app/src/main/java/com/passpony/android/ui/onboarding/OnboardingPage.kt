package com.passpony.android.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.passpony.android.R
import com.passpony.android.crypto.PgpKeyStore
import com.passpony.android.store.UnlockGate
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.AppViewModel
import com.passpony.android.ui.theme.ChoiceField
import com.passpony.android.ui.theme.ListDivider
import com.passpony.android.ui.edit.NewEntryContent
import com.passpony.android.ui.edit.PasswordGenerator
import com.passpony.android.ui.settings.LanguageManager
import com.passpony.android.ui.settings.rememberAgeIdentitiesImporter
import com.passpony.android.ui.settings.rememberBackupRestorer
import com.passpony.android.ui.sync.HostKeyTrustDialog
import com.passpony.android.ui.util.safDisplayName
import java.io.File
import kotlinx.coroutines.launch
import uniffi.pass_ffi.GitException
import uniffi.pass_ffi.StoreFormat

/**
 * Port of PassPony iOS's OnboardingPage: per-slide layout (icon circle,
 * title, body, action area) plus the seven action-area implementations.
 * The whole page sits in a scroll container regardless of content
 * height -- the PGPony 4.1.1 lesson referenced in the P14 packet doc:
 * zero visual change when content fits the screen, no clipped content
 * at large font scales when it doesn't.
 */
@Composable
fun OnboardingPage(slide: OnboardingSlide, appViewModel: AppViewModel, onAdvance: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(
            slide.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(slide.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))

        val body = when {
            slide.bodyPluralRes != null -> {
                // The only interpolated body: UnlockGate's grace period,
                // read fresh at render time -- matches iOS computing
                // OnboardingSlides.all as a property for the same reason.
                val minutes = (UnlockGate.GRACE_PERIOD_MILLIS / 60_000L).toInt()
                pluralStringResource(slide.bodyPluralRes, minutes, minutes)
            }
            slide.bodyRes != null -> stringResource(slide.bodyRes)
            else -> null
        }
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }

        when (slide.action) {
            OnboardingAction.LANGUAGE -> LanguageAction()
            OnboardingAction.FORMAT -> FormatAction(appViewModel)
            OnboardingAction.IMPORT_STORE -> ImportStoreAction(appViewModel)
            OnboardingAction.TRY_PASS -> TryPassAction(appViewModel, onAdvance)
            OnboardingAction.AUTOFILL -> AutofillAction()
            // Biometric is detection/explanation only on iOS too (its
            // action area is EmptyView() -- see OnboardingPage.swift):
            // no auth prompt is triggered from this slide, only the
            // grace-period text above.
            OnboardingAction.BIOMETRIC, OnboardingAction.NONE -> Unit
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Slide 1: sets the real app language immediately on tap, same as the
 * Settings language picker -- LanguageManager.apply() is what makes
 * every subsequent slide (and this one, once AppCompatActivity
 * recreates) resolve against the new locale. */
@Composable
private fun LanguageAction() {
    val currentTag = LanguageManager.currentTag()
    Column(modifier = Modifier.fillMaxWidth()) {
        for ((tag, name) in LanguageManager.supported) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { LanguageManager.apply(tag) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                    if (tag == currentTag) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(if (tag.isEmpty()) stringResource(R.string.xc_system) else name)
            }
            ListDivider()
        }
    }
}

/** Slide 3: really switches the store format and reopens, live. Same
 * dropdown as Settings' format choice: the translated format names are
 * too long for a pair of buttons side by side. */
@Composable
private fun FormatAction(appViewModel: AppViewModel) {
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(appViewModel.format) }
    ChoiceField(
        label = stringResource(R.string.xc_store_format),
        selected = format,
        options = listOf(StoreFormat.PASSAGE, StoreFormat.PASS),
        optionLabel = { formatLabel(it) },
        onSelect = { chosen ->
            format = chosen
            scope.launch { appViewModel.switchFormat(chosen) }
        }
    )
}

@Composable
private fun formatLabel(format: StoreFormat): String = stringResource(
    when (format) {
        StoreFormat.PASSAGE -> R.string.settings_format_passage
        StoreFormat.PASS -> R.string.settings_format_pass
    }
)

/** Slide 4: clone (P09's flow) or import OpenPGP key files (P10's SAF
 * flow), reused rather than reimplemented. Once the store has entries
 * (either from here or from a prior run), shows [ExistingIndicator]
 * instead -- covers both "just imported/cloned" and "replaying the tour
 * on an already-populated store", same check iOS uses. */
@Composable
private fun ImportStoreAction(appViewModel: AppViewModel) {
    val entries by appViewModel.entries.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val format = appViewModel.format
    var showCloneDialog by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    val keyImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val keyDir = PgpKeyStore.keyDirectory(context)
        var imported = 0
        for (uri in uris) {
            val name = safDisplayName(context, uri) ?: continue
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                File(keyDir, name).writeBytes(bytes)
                imported++
            } catch (e: Exception) {
                importMessage = context.getString(R.string.settings_import_failed, name, context.getString(R.string.backup_restore_unreadable))
            }
        }
        if (imported > 0) {
            importMessage = context.getString(R.string.settings_import_success, imported)
            scope.launch { appViewModel.switchFormat(StoreFormat.PASS) }
        }
    }

    val launchAgeImport = rememberAgeIdentitiesImporter(appViewModel) { result ->
        importMessage = result
    }
    val launchRestore = rememberBackupRestorer(appViewModel) { result ->
        importMessage = result
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // A clone populates entries; keep the key step reachable here
        // afterward -- cloning a store is exactly when you still need the
        // key it was encrypted to -- rather than hiding the whole action.
        if (entries.isNotEmpty()) {
            ExistingIndicator(count = entries.size)
        } else {
            TextButton(onClick = { showCloneDialog = true }) {
                Text(stringResource(R.string.xc_clone_existing_store))
            }
        }
        when (format) {
            StoreFormat.PASS -> TextButton(onClick = { keyImportLauncher.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.settings_pgp_import))
            }
            StoreFormat.PASSAGE -> TextButton(onClick = { launchAgeImport() }) {
                Text(stringResource(R.string.settings_age_import))
            }
        }
        // A PassPony backup carries keys for both formats.
        TextButton(onClick = { launchRestore() }) {
            Text(stringResource(R.string.backup_restore_button))
        }
        importMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showCloneDialog) {
        OnboardingCloneDialog(
            appViewModel = appViewModel,
            onCloned = { showCloneDialog = false },
            onDismiss = { showCloneDialog = false }
        )
    }
}

@Composable
private fun OnboardingCloneDialog(appViewModel: AppViewModel, onCloned: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hostPrompt by remember { mutableStateOf<GitException.UnknownHostKey?>(null) }

    fun clone() {
        busy = true
        error = null
        scope.launch {
            try {
                appViewModel.cloneReplaceStore(url)
                onCloned()
            } catch (e: GitException.UnknownHostKey) {
                hostPrompt = e
            } catch (e: Exception) {
                error = e.ponyMessage(context)
            } finally {
                busy = false
            }
        }
    }

    hostPrompt?.let { prompt ->
        HostKeyTrustDialog(
            host = prompt.host,
            key = prompt.key,
            onTrust = {
                hostPrompt = null
                appViewModel.trustHost(prompt.host, prompt.key)
                clone()
            },
            onCancel = {
                hostPrompt = null
                error = context.getString(R.string.ssh_host_not_trusted)
            }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.xc_clone_existing_store)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.sync_publish_url_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.sync_clone_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { clone() },
                enabled = url.isNotEmpty() && !busy
            ) { Text(stringResource(R.string.xc_clone)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.delete_entry_cancel)) }
        }
    )
}

/** Slide 5: a trimmed AddEntryScreen (name + password only, no
 * username/url) that saves a REAL entry via the same
 * AppViewModel.saveEntry P08 uses. Auto-advances once the entry count
 * actually increases, same detection iOS uses (entryCountBeforeAdd set
 * at tap time, compared against the live count). A failed save (2.0.1:
 * saveEntry returns false) shows its error under the button. */
@Composable
private fun TryPassAction(appViewModel: AppViewModel, onAdvance: () -> Unit) {
    val context = LocalContext.current
    val entries by appViewModel.entries.collectAsState()
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var entryCountBeforeAdd by remember { mutableStateOf<Int?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(entries.size) {
        val before = entryCountBeforeAdd
        if (before != null && entries.size > before) {
            entryCountBeforeAdd = null
            onAdvance()
        }
    }

    if (entries.isNotEmpty()) {
        ExistingIndicator(count = entries.size)
        return
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.add_entry_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.entry_detail_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { password = PasswordGenerator.generate(context) }) {
                Text(stringResource(R.string.add_entry_generate))
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                entryCountBeforeAdd = entries.size
                val saved = appViewModel.saveEntry(name, NewEntryContent.assemble(password, "", ""), isNew = true)
                saveError = if (saved) null else appViewModel.lastError.value
            },
            enabled = name.isNotEmpty() && password.isNotEmpty()
        ) {
            Text(stringResource(R.string.xc_add_a_pass))
        }
        saveError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** Slide 7: the system picker for setting PassPony as the default
 * autofill service (same Intent Settings' AutofillSection uses), plus
 * an ON_RESUME recheck -- unlike iOS (whose equivalent slide has no
 * scenePhase-driven recheck at all, per the source), the only path to
 * enabling autofill from here is a round trip through system Settings,
 * so picking up the change on return isn't optional on Android the way
 * it is on iOS. */
@Composable
private fun AutofillAction() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isAutofillServiceEnabled(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = isAutofillServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (enabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.xc_autofill_is_on))
        }
    } else {
        TextButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:" + context.packageName))
                )
            }
        }) {
            Text(stringResource(R.string.xc_turn_on_autofill))
        }
    }
}

private fun isAutofillServiceEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AutofillManager::class.java)
    return manager?.hasEnabledAutofillServices() == true
}

/** Shared "you already have N passes" row (slides 4 and 5's replay
 * state, matching iOS's existingIndicator reused across both). */
@Composable
private fun ExistingIndicator(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(pluralStringResource(R.plurals.xc_you_already_have_lld_passes, count, count))
    }
}
