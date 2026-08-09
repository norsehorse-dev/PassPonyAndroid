package com.passpony.android.autofill

import android.text.InputType
import android.view.View

/** A field PassPony's autofill service recognizes as fillable. */
enum class FieldKind { USERNAME, PASSWORD }

/**
 * The handful of AssistStructure.ViewNode properties classification and
 * domain resolution need, behind an interface so the walk/classify
 * logic is unit-testable with a fake tree -- AssistStructure.ViewNode
 * itself has no public constructor and can't be built outside the
 * platform. [Id] is whatever type identifies a node to the caller
 * (AutofillId in production, a plain marker in tests).
 */
interface ParsedNode<Id> {
    val autofillId: Id?
    val autofillHints: Array<String>?
    val inputType: Int
    /** Set by the platform on nodes inside a WebView-rendered page; null
     * everywhere in a native app's structure. */
    val webDomain: String?
    val childCount: Int
    fun childAt(index: Int): ParsedNode<Id>
}

/** One fillable field found while walking a structure: what a Dataset
 * targets, and which kind of field it is. */
data class AutofillField<Id>(val id: Id, val kind: FieldKind)

/** Everything one fill request's structure yields: the fillable fields,
 * and the first webDomain found anywhere in the tree (null for a
 * native-app structure -- the caller falls back to the requesting
 * package name in that case). */
data class ParsedStructure<Id>(val fields: List<AutofillField<Id>>, val webDomain: String?)

/**
 * Walks a parsed view tree collecting username/password candidates and
 * the page's web domain, if any. Port of the plan's rule: nodes with an
 * explicit autofill hint win outright; otherwise the Android input
 * type's class/variation decides. Deliberately conservative on the
 * username side (email-variation only) -- a bare unhinted text field is
 * too ambiguous (search boxes, names, anything) to guess at without an
 * explicit signal, and under-detecting just means a smaller (never
 * wrong) set of chips, not a broken fill.
 */
object AutofillStructureParser {
    fun <Id> parse(root: ParsedNode<Id>): ParsedStructure<Id> {
        val fields = mutableListOf<AutofillField<Id>>()
        var webDomain: String? = null
        fun walk(node: ParsedNode<Id>) {
            classify(node)?.let { kind -> node.autofillId?.let { id -> fields += AutofillField(id, kind) } }
            if (webDomain == null) {
                val domain = node.webDomain
                if (!domain.isNullOrEmpty()) webDomain = domain
            }
            for (i in 0 until node.childCount) walk(node.childAt(i))
        }
        walk(root)
        return ParsedStructure(fields, webDomain)
    }

    fun <Id> classify(node: ParsedNode<Id>): FieldKind? {
        node.autofillHints?.let { hints ->
            for (hint in hints) {
                when (hint) {
                    View.AUTOFILL_HINT_PASSWORD -> return FieldKind.PASSWORD
                    View.AUTOFILL_HINT_USERNAME, View.AUTOFILL_HINT_EMAIL_ADDRESS -> return FieldKind.USERNAME
                }
            }
        }

        val inputType = node.inputType
        if (inputType == 0) return null
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return null
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> FieldKind.PASSWORD
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.USERNAME
            else -> null
        }
    }
}
