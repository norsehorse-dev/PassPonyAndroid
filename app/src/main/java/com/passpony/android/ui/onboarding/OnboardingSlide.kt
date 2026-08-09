package com.passpony.android.ui.onboarding

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.passpony.android.R

/**
 * Port of PassPony iOS's OnboardingSlide/OnboardingSlides: the seven-slide
 * first-run tour's static data plus which interactive action area each
 * slide hosts. Titles/bodies are @StringRes/@PluralsRes ints rather than
 * resolved strings so they always read against the CURRENT locale --
 * important because slide 1's language picker changes the app locale live
 * via LanguageManager.apply(), which calls Activity.recreate(). Unlike
 * iOS (which re-keys the pager subtree with .id(model.language) to force
 * re-resolution), Android's recreate() already rebuilds the whole
 * composition from scratch against the new Configuration, so a plain
 * stringResource() call at render time is sufficient -- see
 * OnboardingScreen's rememberSaveable page state for the other half of
 * that: what recreate() would otherwise lose.
 */
enum class OnboardingAction {
    NONE, LANGUAGE, FORMAT, IMPORT_STORE, TRY_PASS, BIOMETRIC, AUTOFILL
}

data class OnboardingSlide(
    val icon: ImageVector,
    val tint: Color,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int? = null,
    @PluralsRes val bodyPluralRes: Int? = null,
    val action: OnboardingAction = OnboardingAction.NONE,
)

/**
 * A function rather than a top-level constant, matching iOS's
 * OnboardingSlides.all being a computed property -- kept that way here
 * too even though nothing in the Kotlin data itself is render-time
 * dynamic (the one dynamic piece, the biometric slide's grace-period
 * minutes, is interpolated where it's rendered in OnboardingPage, not
 * baked into this list) so the two implementations stay easy to compare
 * slide-for-slide.
 */
fun onboardingSlides(): List<OnboardingSlide> = listOf(
    OnboardingSlide(
        icon = Icons.Filled.Language,
        tint = Color(0xFF2F6FED),
        titleRes = R.string.settings_language,
        bodyRes = R.string.xc_choose_the_language_you_d_like_to_use_you_can_ch,
        action = OnboardingAction.LANGUAGE,
    ),
    OnboardingSlide(
        icon = Icons.Filled.Celebration,
        tint = Color(0xFF1FA2A2),
        titleRes = R.string.xc_welcome_to_passpony,
        bodyRes = R.string.xc_your_passwords_in_a_store_you_own_passpony_speak,
    ),
    OnboardingSlide(
        icon = Icons.Filled.Archive,
        tint = Color(0xFFE8871E),
        titleRes = R.string.xc_choose_your_format,
        bodyRes = R.string.xc_passage_encrypts_with_modern_age_identities_pass,
        action = OnboardingAction.FORMAT,
    ),
    OnboardingSlide(
        icon = Icons.Filled.CloudDownload,
        tint = Color(0xFF4B4FCB),
        titleRes = R.string.xc_bring_your_store,
        bodyRes = R.string.xc_already_using_pass_or_passage_clone_your_existin,
        action = OnboardingAction.IMPORT_STORE,
    ),
    OnboardingSlide(
        icon = Icons.Filled.AddCircle,
        tint = Color(0xFF2E9E4C),
        titleRes = R.string.xc_add_your_first_pass,
        bodyRes = R.string.xc_a_pass_is_one_encrypted_file_the_password_on_the,
        action = OnboardingAction.TRY_PASS,
    ),
    OnboardingSlide(
        icon = Icons.Filled.Fingerprint,
        tint = Color(0xFF8A3FFC),
        titleRes = R.string.xc_locked_by_default,
        bodyPluralRes = R.plurals.onboarding_biometric_body,
        action = OnboardingAction.BIOMETRIC,
    ),
    OnboardingSlide(
        icon = Icons.Filled.Keyboard,
        tint = Color(0xFFE0468A),
        titleRes = R.string.xc_fill_passwords_anywhere,
        bodyRes = R.string.onboarding_autofill_body,
        action = OnboardingAction.AUTOFILL,
    ),
)
