package com.passpony.android.autofill

import android.text.InputType
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AssistStructure.ViewNode has no public constructor, so the walk/
 * classify logic is exercised here against a small fake ParsedNode tree
 * instead -- the "fake structures" the plan calls for. android.view.View
 * and android.text.InputType are referenced only for their int
 * constants, which resolve fine against the unmocked Android stub jar
 * used for plain JVM unit tests (no Robolectric needed).
 */
class AutofillStructureParserTest {

    private class FakeNode(
        override val autofillId: Int? = null,
        override val autofillHints: Array<String>? = null,
        override val inputType: Int = 0,
        override val webDomain: String? = null,
        private val children: List<FakeNode> = emptyList(),
    ) : ParsedNode<Int> {
        override val childCount: Int get() = children.size
        override fun childAt(index: Int): ParsedNode<Int> = children[index]
    }

    @Test
    fun classify_explicitPasswordHint_isPassword() {
        val node = FakeNode(autofillId = 1, autofillHints = arrayOf(View.AUTOFILL_HINT_PASSWORD))
        assertEquals(FieldKind.PASSWORD, AutofillStructureParser.classify(node))
    }

    @Test
    fun classify_explicitUsernameHint_isUsername() {
        val node = FakeNode(autofillId = 1, autofillHints = arrayOf(View.AUTOFILL_HINT_USERNAME))
        assertEquals(FieldKind.USERNAME, AutofillStructureParser.classify(node))
    }

    @Test
    fun classify_explicitEmailHint_isUsername() {
        val node = FakeNode(autofillId = 1, autofillHints = arrayOf(View.AUTOFILL_HINT_EMAIL_ADDRESS))
        assertEquals(FieldKind.USERNAME, AutofillStructureParser.classify(node))
    }

    @Test
    fun classify_passwordInputTypeVariation_isPassword() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val node = FakeNode(autofillId = 1, inputType = inputType)
        assertEquals(FieldKind.PASSWORD, AutofillStructureParser.classify(node))
    }

    @Test
    fun classify_emailInputTypeVariation_isUsername() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        val node = FakeNode(autofillId = 1, inputType = inputType)
        assertEquals(FieldKind.USERNAME, AutofillStructureParser.classify(node))
    }

    @Test
    fun classify_plainTextNoHints_isNeither() {
        val inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        val node = FakeNode(autofillId = 1, inputType = inputType)
        assertEquals(null, AutofillStructureParser.classify(node))
    }

    @Test
    fun classify_numberField_isNeither() {
        val node = FakeNode(autofillId = 1, inputType = InputType.TYPE_CLASS_NUMBER)
        assertEquals(null, AutofillStructureParser.classify(node))
    }

    @Test
    fun classify_zeroInputTypeNoHints_isNeither() {
        assertEquals(null, AutofillStructureParser.classify(FakeNode(autofillId = 1)))
    }

    @Test
    fun classify_hintWinsOverConflictingInputType() {
        // An explicit hint is authoritative even if the raw input type
        // would have suggested something else.
        val node = FakeNode(
            autofillId = 1,
            autofillHints = arrayOf(View.AUTOFILL_HINT_PASSWORD),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        )
        assertEquals(FieldKind.PASSWORD, AutofillStructureParser.classify(node))
    }

    @Test
    fun classify_nodeWithoutAnAutofillId_isStillClassified() {
        // classify() itself doesn't care about id -- parse() is what
        // drops id-less nodes (nothing a Dataset could target anyway).
        val node = FakeNode(autofillId = null, autofillHints = arrayOf(View.AUTOFILL_HINT_PASSWORD))
        assertEquals(FieldKind.PASSWORD, AutofillStructureParser.classify(node))
    }

    @Test
    fun parse_findsFieldsAcrossNestedChildren() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(
                    children = listOf(
                        FakeNode(autofillId = 10, autofillHints = arrayOf(View.AUTOFILL_HINT_USERNAME)),
                        FakeNode(autofillId = 11, autofillHints = arrayOf(View.AUTOFILL_HINT_PASSWORD)),
                        FakeNode(autofillId = 12), // an unrelated node in between
                    )
                ),
                FakeNode(autofillId = 20, inputType = InputType.TYPE_CLASS_NUMBER), // unrelated sibling subtree
            )
        )
        val result = AutofillStructureParser.parse(tree)
        assertEquals(listOf(AutofillField(10, FieldKind.USERNAME), AutofillField(11, FieldKind.PASSWORD)), result.fields)
    }

    @Test
    fun parse_dropsClassifiedNodesWithNoAutofillId() {
        val tree = FakeNode(
            children = listOf(FakeNode(autofillId = null, autofillHints = arrayOf(View.AUTOFILL_HINT_PASSWORD)))
        )
        assertTrue(AutofillStructureParser.parse(tree).fields.isEmpty())
    }

    @Test
    fun parse_emptyTree_returnsEmptyFieldsAndNullDomain() {
        val result = AutofillStructureParser.parse(FakeNode())
        assertTrue(result.fields.isEmpty())
        assertEquals(null, result.webDomain)
    }

    @Test
    fun parse_findsWebDomainOnAnyDescendant() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(), // no domain here
                FakeNode(
                    children = listOf(
                        FakeNode(autofillId = 1, autofillHints = arrayOf(View.AUTOFILL_HINT_PASSWORD), webDomain = "github.com")
                    )
                ),
            )
        )
        assertEquals("github.com", AutofillStructureParser.parse(tree).webDomain)
    }

    @Test
    fun parse_firstNonEmptyWebDomainWins() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(webDomain = "github.com"),
                FakeNode(webDomain = "example.com"),
            )
        )
        assertEquals("github.com", AutofillStructureParser.parse(tree).webDomain)
    }

    @Test
    fun parse_blankWebDomainIsSkipped() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(webDomain = ""),
                FakeNode(webDomain = "github.com"),
            )
        )
        assertEquals("github.com", AutofillStructureParser.parse(tree).webDomain)
    }
}
