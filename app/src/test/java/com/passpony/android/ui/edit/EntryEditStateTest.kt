package com.passpony.android.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryEditStateTest {

    private fun baseState(
        password: String = "hunter2",
        values: Map<String, String> = mapOf("username" to "kevin", "url" to "example.com"),
        newKey: String = "",
        newValue: String = "",
    ) = EntryEditState(
        originalPassword = "hunter2",
        originalValues = mapOf("username" to "kevin", "url" to "example.com"),
        fieldKeys = listOf("username", "url"),
        password = password,
        values = values,
        newKey = newKey,
        newValue = newValue,
    )

    @Test
    fun hasChanges_falseWhenNothingEdited() {
        assertFalse(baseState().hasChanges)
    }

    @Test
    fun hasChanges_trueWhenPasswordChanges() {
        assertTrue(baseState(password = "different").hasChanges)
    }

    @Test
    fun hasChanges_trueWhenAFieldValueChanges() {
        val state = baseState(values = mapOf("username" to "kstewart", "url" to "example.com"))
        assertTrue(state.hasChanges)
    }

    @Test
    fun hasChanges_falseWhenNewFieldIsOnlyHalfFilled() {
        assertFalse(baseState(newKey = "note").hasChanges)
        assertFalse(baseState(newValue = "some note").hasChanges)
    }

    @Test
    fun hasChanges_trueWhenNewFieldIsFullyFilled() {
        assertTrue(baseState(newKey = "note", newValue = "some note").hasChanges)
    }

    @Test
    fun editSteps_emptyWhenNothingChanged() {
        assertEquals(emptyList<EditStep>(), baseState().editSteps())
    }

    @Test
    fun editSteps_passwordFirstThenChangedFieldsInOriginalOrderThenNewField() {
        val state = baseState(
            password = "newpass",
            values = mapOf("username" to "kstewart", "url" to "example.com"),
            newKey = "note",
            newValue = "some note",
        )
        assertEquals(
            listOf(
                EditStep.SetPassword("newpass"),
                EditStep.SetField("username", "kstewart"),
                EditStep.SetField("note", "some note"),
            ),
            state.editSteps()
        )
    }

    @Test
    fun editSteps_onlyIncludesFieldsThatActuallyChanged() {
        val state = baseState(values = mapOf("username" to "kevin", "url" to "changed.example.com"))
        assertEquals(listOf(EditStep.SetField("url", "changed.example.com")), state.editSteps())
    }

    @Test
    fun editSteps_trimsTheNewFieldKey() {
        val state = baseState(newKey = "  note  ", newValue = "value")
        assertEquals(listOf(EditStep.SetField("note", "value")), state.editSteps())
    }

    @Test
    fun editSteps_ignoresNewFieldWhenHalfFilled() {
        val state = baseState(newKey = "note", newValue = "")
        assertEquals(emptyList<EditStep>(), state.editSteps())
    }
}
