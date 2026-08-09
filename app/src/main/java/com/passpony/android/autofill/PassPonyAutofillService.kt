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
import android.util.Log
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
        Log.d(TAG, "onFillRequest: called")
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null) {
                Log.d(TAG, "onFillRequest: no structure in fillContexts, bailing")
                callback.onSuccess(null)
                return
            }

            val parsed = parseAll(structure)
            Log.d(TAG, "onFillRequest: parsed ${parsed.fields.size} field(s), webDomain=${parsed.webDomain}")
            val passwordId = parsed.fields.firstOrNull { it.kind == FieldKind.PASSWORD }?.id
            val usernameId = parsed.fields.firstOrNull { it.kind == FieldKind.USERNAME }?.id
            if (passwordId == null && usernameId == null) {
                // Nothing this service can offer to fill -- plan item 1's
                // "bail with callback.onSuccess(null)" case. Deliberately
                // not "passwordId == null" alone: Chrome sometimes scopes
                // the AssistStructure to just the currently-focused field,
                // so a request with only a username field (password not
                // focused yet) is common and legitimate, not an empty form.
                Log.d(TAG, "onFillRequest: no USERNAME or PASSWORD field classified, bailing")
                callback.onSuccess(null)
                return
            }
            val domain = parsed.webDomain ?: structure.activityComponent?.packageName ?: ""
            Log.d(TAG, "onFillRequest: passwordId=${passwordId != null}, usernameId=${usernameId != null}, domain=$domain")

            val entries = AutofillCredential.entries(this)
            val matches = entries
                .filter { !it.hidden }
                .filter { ServiceHint.matchesDomain(ServiceHint.forEntryName(it.name), domain) }
                .take(MAX_DATASETS)
            Log.d(TAG, "onFillRequest: ${entries.size} total entries, ${matches.size} matched domain '$domain'")

            val inlineSpecs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                inlineSpecsFor(request)
            } else {
                emptyList()
            }
            Log.d(TAG, "onFillRequest: ${inlineSpecs.size} inline presentation spec(s) requested")

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
            Log.d(TAG, "onFillRequest: responded with ${matches.size + 1} dataset(s)")
        } catch (e: Throwable) {
            // Throwable, not Exception: a bad guess anywhere in the inline
            // presentation API surface fails as a LinkageError/NoSuchMethodError,
            // not an Exception -- this must still degrade to no suggestions
            // rather than silently hang the fill request (no callback call at
            // all reads to the host app as an autofill service that's stuck).
            Log.e(TAG, "onFillRequest: failed, returning no suggestions", e)
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
        passwordId: AutofillId?,
        requestCode: Int,
        inlineSpec: InlinePresentationSpec?,
    ): Dataset {
        val pendingIntent = pendingIntentFor(AutofillAuthActivity::class.java, requestCode) {
            putExtra(AutofillAuthActivity.EXTRA_ENTRY_NAME, entry.name)
            passwordId?.let { putExtra(AutofillAuthActivity.EXTRA_PASSWORD_ID, it) }
            usernameId?.let { putExtra(AutofillAuthActivity.EXTRA_USERNAME_ID, it) }
        }
        return buildDataset(entry.name, usernameId, passwordId, pendingIntent, inlineSpec)
    }

    private fun pickerDataset(
        domain: String,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        requestCode: Int,
        inlineSpec: InlinePresentationSpec?,
    ): Dataset {
        val label = getString(R.string.autofill_search_dataset_label)
        val pendingIntent = pendingIntentFor(AutofillPickerActivity::class.java, requestCode) {
            putExtra(AutofillPickerActivity.EXTRA_SERVICE_HINT, domain)
            passwordId?.let { putExtra(AutofillAuthActivity.EXTRA_PASSWORD_ID, it) }
            usernameId?.let { putExtra(AutofillAuthActivity.EXTRA_USERNAME_ID, it) }
        }
        return buildDataset(label, usernameId, passwordId, pendingIntent, inlineSpec)
    }

    /**
     * One Dataset, gated behind [pendingIntent]: every field gets a null
     * AutofillValue (the platform accepts that for an authenticated
     * dataset -- nothing decrypts until the pick, per the plan's threat
     * model) paired with [label]'s RemoteViews, plus an InlinePresentation
     * too when [inlineSpec] is present. [usernameId]/[passwordId] are both
     * nullable and at least one is always non-null (onFillRequest's own
     * bail check guarantees that) -- a request scoped to a single focused
     * field only fills that field.
     */
    private fun buildDataset(
        label: String,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
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
        passwordId?.let { addValue(builder, it, presentation, inline) }
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
     * failing the whole fill request. Catches Throwable, not Exception:
     * a missing/renamed method in this surface (e.g. androidx.autofill
     * failing to resolve at runtime) raises a LinkageError or
     * NoSuchMethodError, neither of which is an Exception -- an
     * Exception-only catch here would let that propagate uncaught out of
     * onFillRequest.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildInlinePresentationOrNull(label: String, pendingIntent: PendingIntent, spec: InlinePresentationSpec): InlinePresentation? =
        try {
            val slice = InlineSuggestionUi.newContentBuilder(pendingIntent)
                .setTitle(label)
                .build()
                .slice
            InlinePresentation(slice, spec, false)
        } catch (e: Throwable) {
            Log.w(TAG, "buildInlinePresentationOrNull: falling back to RemoteViews only", e)
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
            val root = structure.getWindowNodeAt(i).rootViewNode
            dumpNodeForDebugging(root, depth = 0)
            val parsed = AutofillStructureParser.parse(ViewNodeAdapter(root))
            fields += parsed.fields
            if (webDomain == null) webDomain = parsed.webDomain
        }
        return ParsedStructure(fields, webDomain)
    }

    /**
     * TEMPORARY diagnostic: dumps every <input>-like node's raw hints,
     * inputType, and HTML tag/attributes (never the typed value) so we
     * can see exactly what Chrome hands us for a field that isn't
     * classifying the way its HTML suggests it should. Remove once P12's
     * classify() gap is root-caused.
     */
    private fun dumpNodeForDebugging(node: AssistStructure.ViewNode, depth: Int) {
        val htmlTag = node.htmlInfo?.tag
        if (node.autofillId != null || htmlTag != null) {
            val attrs = node.htmlInfo?.attributes?.joinToString { "${it.first}=${it.second}" }
            Log.d(
                TAG,
                "structure: depth=$depth class=${node.className} idPresent=${node.autofillId != null} " +
                    "hints=${node.autofillHints?.joinToString()} inputType=0x${Integer.toHexString(node.inputType)} " +
                    "htmlTag=$htmlTag htmlAttrs=[$attrs]"
            )
        }
        for (i in 0 until node.childCount) {
            dumpNodeForDebugging(node.getChildAt(i), depth + 1)
        }
    }

    companion object {
        private const val TAG = "PassPonyAutofillService"

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
