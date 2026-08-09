package com.passpony.android.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.passpony.android.R
import com.passpony.android.store.ServiceHint
import uniffi.pass_ffi.EntryRef

/**
 * The system-wide fill service, plan item 1. Never decrypts anything
 * itself -- every Dataset it builds carries only an entry name and a
 * RemoteViews label, gated behind an authentication PendingIntent
 * targeting AutofillAuthActivity (a specific match) or
 * AutofillPickerActivity (the always-present "Search in PassPony"
 * fallback). Both of those, not this service, ever touch
 * AutofillCredential.resolve.
 *
 * Structure parsing goes through AutofillStructureParser via
 * ViewNodeAdapter below, so the actual walk/classify logic is the same
 * code AutofillStructureParserTest exercises against fakes --
 * AssistStructure.ViewNode itself has no public constructor, so this
 * class is the one integration point that can't be unit-tested directly.
 */
class PassPonyAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null) {
                callback.onSuccess(null)
                return
            }

            val parsed = parseAll(structure)
            val passwordId = parsed.fields.firstOrNull { it.kind == FieldKind.PASSWORD }?.id
            if (passwordId == null) {
                // Nothing this service can offer to fill -- plan item 1's
                // "bail with callback.onSuccess(null)" case.
                callback.onSuccess(null)
                return
            }
            val usernameId = parsed.fields.firstOrNull { it.kind == FieldKind.USERNAME }?.id
            val domain = parsed.webDomain ?: structure.activityComponent?.packageName ?: ""

            val entries = AutofillCredential.entries(this)
            val matches = entries
                .filter { !it.hidden }
                .filter { ServiceHint.matchesDomain(ServiceHint.forEntryName(it.name), domain) }
                .take(MAX_DATASETS)

            val inlineSpecs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                inlineSpecsFor(request)
            } else {
                emptyList()
            }

            val response = FillResponse.Builder()
            matches.forEachIndexed { index, entry ->
                response.addDataset(
                    matchDataset(entry, usernameId, passwordId, requestCode = index, inlineSpec = inlineSpecs.getOrNull(index))
                )
            }
            response.addDataset(
                pickerDataset(domain, usernameId, passwordId, requestCode = matches.size, inlineSpec = inlineSpecs.getOrNull(matches.size))
            )

            callback.onSuccess(response.build())
        } catch (e: Exception) {
            // A fill request is never user-initiated in the way a pick or
            // a save is -- swallow and offer nothing rather than crash the
            // host app the user is trying to use.
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // SaveInfo (offering to create/update an entry from a filled form)
        // is out of scope for v1 -- plan item 1's onSaveRequest note.
        callback.onFailure(getString(R.string.autofill_save_not_supported))
    }

    private fun matchDataset(
        entry: EntryRef,
        usernameId: AutofillId?,
        passwordId: AutofillId,
        requestCode: Int,
        inlineSpec: InlinePresentationSpec?,
    ): Dataset {
        val pendingIntent = pendingIntentFor(AutofillAuthActivity::class.java, requestCode) {
            putExtra(AutofillAuthActivity.EXTRA_ENTRY_NAME, entry.name)
            putExtra(AutofillAuthActivity.EXTRA_PASSWORD_ID, passwordId)
            usernameId?.let { putExtra(AutofillAuthActivity.EXTRA_USERNAME_ID, it) }
        }
        return buildDataset(entry.name, usernameId, passwordId, pendingIntent, inlineSpec)
    }

    private fun pickerDataset(
        domain: String,
        usernameId: AutofillId?,
        passwordId: AutofillId,
        requestCode: Int,
        inlineSpec: InlinePresentationSpec?,
    ): Dataset {
        val label = getString(R.string.autofill_search_dataset_label)
        val pendingIntent = pendingIntentFor(AutofillPickerActivity::class.java, requestCode) {
            putExtra(AutofillPickerActivity.EXTRA_SERVICE_HINT, domain)
            putExtra(AutofillAuthActivity.EXTRA_PASSWORD_ID, passwordId)
            usernameId?.let { putExtra(AutofillAuthActivity.EXTRA_USERNAME_ID, it) }
        }
        return buildDataset(label, usernameId, passwordId, pendingIntent, inlineSpec)
    }

    /**
     * One Dataset, gated behind [pendingIntent]: every field gets a null
     * AutofillValue (the platform accepts that for an authenticated
     * dataset -- nothing decrypts until the pick, per the plan's threat
     * model) paired with [label]'s RemoteViews, plus an InlinePresentation
     * too when [inlineSpec] is present.
     */
    private fun buildDataset(
        label: String,
        usernameId: AutofillId?,
        passwordId: AutofillId,
        pendingIntent: PendingIntent,
        inlineSpec: InlinePresentationSpec?,
    ): Dataset {
        val presentation = remoteViewsFor(label)
        val inline = if (inlineSpec != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            buildInlinePresentationOrNull(label, pendingIntent, inlineSpec)
        } else {
            null
        }

        val builder = Dataset.Builder()
        usernameId?.let { addValue(builder, it, presentation, inline) }
        addValue(builder, passwordId, presentation, inline)
        builder.setAuthentication(pendingIntent.intentSender)
        return builder.build()
    }

    private fun addValue(builder: Dataset.Builder, id: AutofillId, presentation: RemoteViews, inline: InlinePresentation?) {
        if (inline != null) {
            addValueWithInline(builder, id, presentation, inline)
        } else {
            builder.setValue(id, null, presentation)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun addValueWithInline(builder: Dataset.Builder, id: AutofillId, presentation: RemoteViews, inline: InlinePresentation) {
        builder.setValue(id, null, presentation, inline)
    }

    private fun remoteViewsFor(label: String): RemoteViews =
        RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
            setTextViewText(R.id.autofill_dataset_text, label)
        }

    /**
     * Isolated behind its own @RequiresApi method (rather than inlined at
     * the call site) so a wrong guess anywhere in this API surface -- the
     * least-verified part of this packet -- throws inside a try/catch
     * that falls back to the plain RemoteViews presentation instead of
     * failing the whole fill request.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildInlinePresentationOrNull(label: String, pendingIntent: PendingIntent, spec: InlinePresentationSpec): InlinePresentation? =
        try {
            val slice = InlineSuggestionUi.newContentBuilder(pendingIntent)
                .setTitle(label)
                .build()
                .slice
            InlinePresentation(slice, spec, false)
        } catch (e: Exception) {
            null
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun inlineSpecsFor(request: FillRequest): List<InlinePresentationSpec> =
        request.inlineSuggestionsRequest?.inlinePresentationSpecs.orEmpty()

    private fun pendingIntentFor(targetActivity: Class<*>, requestCode: Int, configure: Intent.() -> Unit): PendingIntent {
        val intent = Intent(this, targetActivity).apply(configure)
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun parseAll(structure: AssistStructure): ParsedStructure<AutofillId> {
        val fields = mutableListOf<AutofillField<AutofillId>>()
        var webDomain: String? = null
        for (i in 0 until structure.windowNodeCount) {
            val parsed = AutofillStructureParser.parse(ViewNodeAdapter(structure.getWindowNodeAt(i).rootViewNode))
            fields += parsed.fields
            if (webDomain == null) webDomain = parsed.webDomain
        }
        return ParsedStructure(fields, webDomain)
    }

    companion object {
        /** Plan item 1: "up to N (5)". */
        private const val MAX_DATASETS = 5
    }
}

/** Adapts the platform's AssistStructure.ViewNode to ParsedNode so
 * AutofillStructureParser's walk/classify logic runs unchanged here and
 * under test. */
private class ViewNodeAdapter(private val node: AssistStructure.ViewNode) : ParsedNode<AutofillId> {
    override val autofillId: AutofillId? get() = node.autofillId
    override val autofillHints: Array<String>? get() = node.autofillHints
    override val inputType: Int get() = node.inputType
    override val webDomain: String? get() = node.webDomain
    override val childCount: Int get() = node.childCount
    override fun childAt(index: Int): ParsedNode<AutofillId> = ViewNodeAdapter(node.getChildAt(index))
}
