package com.passpony.android.ui.edit

/**
 * One entry's edit-form state and the byte-faithful diff against its
 * original values -- the decision layer behind EditEntryScreen. Pure
 * and FFI-free: it works only with already-extracted Kotlin
 * field/password strings (from entryFields()/entryPassword(), read once
 * by the screen), never with entry bytes directly, so "what changed" is
 * a plain JVM unit test. Applying the result (via entrySetField /
 * entrySetPassword / entrySetOtpauth / entryRemoveOtpauth against the
 * real bytes) is the screen's job, tested against a real fixture as an
 * instrumented test instead (PgpEditByteFidelityTest).
 *
 * Port of PassPony iOS's EditEntryView: `hasChanges` and the
 * password-then-changed-fields-then-new-field sequence mirror its
 * `hasChanges` computed property and `save()` exactly. 1.1 adds the
 * one-time code step at the end of that sequence on both platforms.
 */
data class EntryEditState(
    val originalPassword: String,
    val originalValues: Map<String, String>,
    val fieldKeys: List<String>,
    val password: String,
    val values: Map<String, String>,
    val newKey: String = "",
    val newValue: String = "",
    /** Whether the entry carried an `otpauth://` line when it was loaded. */
    val hasOriginalOtpauth: Boolean = false,
    /** What the user decided about the one-time code, if anything. */
    val otpauthEdit: OtpauthEdit = OtpauthEdit.Unchanged,
) {
    val passwordChanged: Boolean
        get() = password != originalPassword

    /** [fieldKeys] order, not [values]' iteration order -- preserves the
     * entry's own first-occurrence field order from entryFields(). */
    val changedFieldKeys: List<String>
        get() = fieldKeys.filter { values[it] != originalValues[it] }

    val hasNewField: Boolean
        get() = newKey.isNotEmpty() && newValue.isNotEmpty()

    /**
     * Setting a URI always counts. Removing counts only when there is a
     * code to remove: the codec's remove is a no-op on an entry without
     * one, so the screen must not light up Save for it.
     */
    val otpauthChanged: Boolean
        get() = when (otpauthEdit) {
            OtpauthEdit.Unchanged -> false
            is OtpauthEdit.Set -> true
            OtpauthEdit.Remove -> hasOriginalOtpauth
        }

    val hasChanges: Boolean
        get() = passwordChanged || changedFieldKeys.isNotEmpty() || hasNewField || otpauthChanged

    /**
     * Ordered edit steps to apply to the original bytes: password first
     * (if changed), then each changed existing field in the entry's own
     * order, then a new field, then the one-time code last. The order
     * matters only in that it matches EditEntryView.save()'s sequence
     * for parity -- each step rewrites only its own slice of the bytes
     * (entrySetField/entrySetPassword/entrySetOtpauth/
     * entryRemoveOtpauth), so applying them in any order produces the
     * same final bytes.
     */
    fun editSteps(): List<EditStep> {
        val steps = mutableListOf<EditStep>()
        if (passwordChanged) steps += EditStep.SetPassword(password)
        for (key in changedFieldKeys) steps += EditStep.SetField(key, values[key] ?: "")
        if (hasNewField) steps += EditStep.SetField(newKey.trim(), newValue)
        when (val edit = otpauthEdit) {
            OtpauthEdit.Unchanged -> {}
            is OtpauthEdit.Set -> steps += EditStep.SetOtpauth(edit.uri)
            OtpauthEdit.Remove -> if (hasOriginalOtpauth) steps += EditStep.RemoveOtpauth
        }
        return steps
    }
}

/** The user's decision about the entry's `otpauth://` line. */
sealed class OtpauthEdit {
    /** Leave whatever the entry has alone. */
    data object Unchanged : OtpauthEdit()

    /** Add a code, or replace the existing one, with this URI. */
    data class Set(val uri: String) : OtpauthEdit()

    /** Drop the existing code (never offered for an OTP-only entry, whose
     * URI is its first line; the codec refuses that removal). */
    data object Remove : OtpauthEdit()
}

sealed class EditStep {
    data class SetPassword(val password: String) : EditStep()
    data class SetField(val key: String, val value: String) : EditStep()
    data class SetOtpauth(val uri: String) : EditStep()
    data object RemoveOtpauth : EditStep()
}
