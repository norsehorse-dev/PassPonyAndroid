package com.passpony.android.autofill

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.passpony.android.R
import com.passpony.android.store.ponyMessage
import com.passpony.android.ui.theme.PassPonyTheme
import com.passpony.android.ui.util.SecureScreenEffect
import uniffi.pass_ffi.EntryRef

/**
 * The autofill picker fallback: a standalone activity (not part of
 * PassPonyNavGraph -- the autofill framework launches this directly via
 * PendingIntent, same as AutofillAuthActivity) listing every visible
 * entry, ranked by AutofillPickerRanking. Port of iOS's
 * AutofillPickerView, with one deliberate difference: picking an entry
 * here re-runs the same fresh-or-authenticate gate AutofillAuthActivity
 * uses (plan item 3) rather than skipping straight to decrypt, since
 * Android has no equivalent to iOS's system-level pre-gating of the
 * credential sheet.
 */
class AutofillPickerActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val usernameId: AutofillId? = intent.getParcelableExtraCompat(AutofillAuthActivity.EXTRA_USERNAME_ID)
        val passwordId: AutofillId? = intent.getParcelableExtraCompat(AutofillAuthActivity.EXTRA_PASSWORD_ID)
        val serviceHint = intent.getStringExtra(EXTRA_SERVICE_HINT).orEmpty()

        if (passwordId == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            PassPonyTheme {
                AutofillPickerScreen(
                    serviceHint = serviceHint,
                    onPick = { name, onFailure ->
                        AutofillCredential.resolveGated(
                            activity = this,
                            name = name,
                            onSuccess = { credential ->
                                val dataset = Dataset.Builder().apply {
                                    usernameId?.let { setValue(it, AutofillValue.forText(credential.username)) }
                                    setValue(passwordId, AutofillValue.forText(credential.password))
                                }.build()
                                val result = Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
                                setResult(Activity.RESULT_OK, result)
                                finish()
                            },
                            onFailure = onFailure
                        )
                    },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_SERVICE_HINT = "com.passpony.android.autofill.EXTRA_SERVICE_HINT"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutofillPickerScreen(
    serviceHint: String,
    onPick: (name: String, onFailure: () -> Unit) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    SecureScreenEffect()

    var entries by remember { mutableStateOf(emptyList<EntryRef>()) }
    var search by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            entries = AutofillCredential.entries(context)
        } catch (e: Exception) {
            error = e.ponyMessage(context)
        }
    }

    val filtered = remember(entries, serviceHint, search) {
        AutofillPickerRanking.filtered(entries, serviceHint, search)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.autofill_picker_title)) },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(stringResource(R.string.autofill_picker_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            LazyColumn {
                items(filtered, key = { it.name }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        modifier = Modifier.fillMaxWidth().clickable {
                            onPick(entry.name) { error = context.getString(R.string.autofill_pick_failed) }
                        }
                    )
                }
            }
        }
    }
}
