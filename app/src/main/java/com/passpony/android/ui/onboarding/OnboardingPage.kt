package com.passpony.android.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.passpony.android.ui.edit.NewEntryContent
import com.passpony.android.ui.edit.PasswordGenerator
import com.passpony.android.ui.settings.LanguageManager
import com.passpony.android.ui.util.safDisplayName
import java.io.File
import kotlinx.coroutines.launch
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(slide.tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(slide.icon, contentDescription = null, tint = slide.tint, modifier = Modifier.size(56.dp))
        }
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
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(24.dp))
        }

        when (slide.action) {
            OnboardingAction.LANGUAGE -> LanguageAction()
            OnboardingAction.FORMAT -> FormatAction(appViewModel)
            OnboardingAction.IMPORT_STORE -> ImportStoreAction(appViewModel, onAdvance)
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
 * Settings language picker -- LanguageManager.apply() recreates the
 * Activity, which is what makes every subsequent slide (and this one,
 * on rebuild) resolve against the new locale. */
@Composable
private fun LanguageAction() {
    val context = LocalContext.current
    val currentTag = LanguageManager.currentTag()
    Column(modifier = Modifier.fillMaxWidth()) {
        for ((tag, name) in LanguageManager.supported) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        LanguageManager.activityOf(context)?.let { LanguageManager.apply(it, tag) }
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                    if (tag == currentTag) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(name)
            }
        }
    }
}

/** Slide 3: really switches the store format and reopens, live -- same
 * TextButton-pair/bold-when-selected pattern as Settings' FormatSection. */
@Composable
private fun FormatAction(appViewModel: AppViewModel) {
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(appViewModel.format) }
    Row(horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = {
            format = StoreFormat.PASSAGE
            scope.launch { appViewModel.switchFormat(StoreFormat.PASSAGE) }
        }) {
            Text(
                stringResource(R.string.settings_format_passage),
                fontWeight = if (format == StoreFormat.PASSAGE) FontWeight.Bold else FontWeight.Normal
            )
        }
        TextButton(onClick = {
            format = StoreFormat.PASS
            scope.launch { appViewModel.switchFormat(StoreFormat.PASS) }
        }) {
            Text(
                stringResource(R.string.settings_format_pass),
                fontWeight = if (format == StoreFormat.PASS) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

/** Slide 4: clone (P09's flow) or import OpenPGP key files (P10's SAF
 * flow), reused rather than reimplemented. Once the store has entries
 * (either from here or from a prior run), shows [ExistingIndicator]
 * instead -- covers both "just imported/cloned" and "replaying the tour
 * on an already-populated store", same check iOS uses. */
@Composable
private fun ImportStoreAction(appViewModel: AppViewModel, onAdvance: () -> Unit) {
    val entries by appViewModel.entries.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCloneDialog by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    if (entries.isNotEmpty()) {
        ExistingIndicator(count = entries.size)
        return
    }

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
                importMessage = context.getString(R.string.settings_import_failed, name, e.message ?: "")
            }
        }
        if (imported > 0) {
            importMessage = context.getString(R.string.settings_import_success, imported)
            scope.launch { appViewModel.switchFormat(StoreFormat.PASS) }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = { showCloneDialog = true }) {
            Text(stringResource(R.string.xc_clone_existing_store))
        }
        TextButton(onClick = { keyImportLauncher.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.settings_pgp_import))
        }
        importMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }

    if (showCloneDialog) {
        OnboardingCloneDialog(
            appViewModel = appViewModel,
            onCloned = {
                showCloneDialog = false
                onAdvance()
            },
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
                    color = MaterialTheme.colorScheme.secondary,
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
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            appViewModel.cloneReplaceStore(url)
                            onCloned()
                        } catch (e: Exception) {
                            error = e.ponyMessage(context)
                        } finally {
                            busy = false
                        }
                    }
                },
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
 * at tap time, compared against the live count) rather than trusting
 * saveEntry's own (silent-on-failure) return. */
@Composable
private fun TryPassAction(appViewModel: AppViewModel, onAdvance: () -> Unit) {
    val entries by appViewModel.entries.collectAsState()
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var entryCountBeforeAdd by remember { mutableStateOf<Int?>(null) }

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
            TextButton(onClick = { password = PasswordGenerator.generate() }) {
                Text(stringResource(R.string.add_entry_generate))
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                entryCountBeforeAdd = entries.size
                appViewModel.saveEntry(name, NewEntryContent.assemble(password, "", ""), isNew = true)
            },
            enabled = name.isNotEmpty() && password.isNotEmpty()
        ) {
            Text(stringResource(R.string.xc_add_a_pass))
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
