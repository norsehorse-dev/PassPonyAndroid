package com.passpony.android.ui.edit

/**
 * One entry's edit-form state and the byte-faithful diff against its
 * original values -- the decision layer behind EditEntryScreen. Pure
 * and FFI-free: it works only with already-extracted Kotlin
 * field/password strings (from entryFields()/entryPassword(), read once
 * by the screen), never with entry bytes directly, so "what changed" is
 * a plain JVM unit test. Applying the result (via entrySetField /
 * entrySetPassword against the real bytes) is the screen's job, tested
 * against a real fixture as an instrumented test instead
 * (PgpEditByteFidelityTest).
 *
 * Port of PassPony iOS's EditEntryView: `hasChanges` and the
 * password-then-changed-fields-then-new-field sequence mirror its
 * `hasChanges` computed property and `save()` exactly.
 */
data class EntryEditState(
    val originalPassword: String,
    val originalValues: Map<String, String>,
    val fieldKeys: List<String>,
    val password: String,
    val values: Map<String, String>,
    val newKey: String = "",
    val newValue: String = "",
) {
    val passwordChanged: Boolean
        get() = password != originalPassword

    /** [fieldKeys] order, not [values]' iteration order -- preserves the
     * entry's own first-occurrence field order from entryFields(). */
    val changedFieldKeys: List<String>
        get() = fieldKeys.filter { values[it] != originalValues[it] }

    val hasNewField: Boolean
        get() = newKey.isNotEmpty() && newValue.isNotEmpty()

    val hasChanges: Boolean
        get() = passwordChanged || changedFieldKeys.isNotEmpty() || hasNewField

    /**
     * Ordered edit steps to apply to the original bytes: password first
     * (if changed), then each changed existing field in the entry's own
     * order, then a new field last. The order matters only in that it
     * matches EditEntryView.save()'s sequence for parity -- each step
     * rewrites only its own slice of the bytes (entrySetField/
     * entrySetPassword), so applying them in any order produces the
     * same final bytes.
     */
    fun editSteps(): List<EditStep> {
        val steps = mutableListOf<EditStep>()
        if (passwordChanged) steps += EditStep.SetPassword(password)
        for (key in changedFieldKeys) steps += EditStep.SetField(key, values[key] ?: "")
        if (hasNewField) steps += EditStep.SetField(newKey.trim(), newValue)
        return steps
    }
}

sealed class EditStep {
    data class SetPassword(val password: String) : EditStep()
    data class SetField(val key: String, val value: String) : EditStep()
}
