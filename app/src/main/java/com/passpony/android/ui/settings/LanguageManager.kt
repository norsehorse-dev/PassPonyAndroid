package com.passpony.android.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Per-app language override, applied immediately and independent of
 * translations existing: only English strings.xml exists until P13, so
 * every non-English tag falls back to English resources until then,
 * same as iOS falling back to its base .lproj. Ports iOS's
 * LanguageManager (backed by AppModel.language / RootView's locale
 * environment) onto AppCompatDelegate's per-app locale API.
 *
 * Requires MainActivity to be an AppCompatActivity (not just any
 * FragmentActivity) -- per Android's own docs, a Compose app using
 * AppCompatDelegate.setApplicationLocales() from a non-AppCompatActivity
 * silently does nothing. Once that's true, on API 33+ MainActivity's
 * configChanges="locale" (see AndroidManifest.xml) means the change
 * lands as an in-place recomposition, not a window teardown -- no
 * manual Activity.recreate() call needed here, and no visible flash.
 * API 26-32 still gets a real recreate via AppCompat's compat shim,
 * which configChanges can't opt out of since it isn't a real
 * Configuration change on those versions.
 */
object LanguageManager {
    /** Tag to display name, in that language, verbatim -- never run
     * through the string catalog, matching iOS's `Text(verbatim:)` use
     * for these exact same seven languages. "" is "follow system". */
    val supported: List<Pair<String, String>> = listOf(
        "" to "System",
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "zh-Hans" to "简体中文",
        "pt-BR" to "Português (Brasil)",
        "ru" to "Русский",
    )

    /** The current override tag, or "" for "follow system". */
    fun currentTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) "" else locales.toLanguageTags()
    }

    /** Apply [tag] (one of [supported]'s first values). AppCompatActivity
     * picks up the change and recreates itself; no explicit Activity
     * reference or manual recreate() needed. */
    fun apply(tag: String) {
        val locales = if (tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
