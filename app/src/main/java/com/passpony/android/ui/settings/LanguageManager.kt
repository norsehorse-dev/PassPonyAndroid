package com.passpony.android.ui.settings

import android.app.Activity
import android.content.Context
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
 * MainActivity is a plain ComponentActivity, not AppCompatActivity, so
 * the automatic recreate-on-locale-change AppCompatDelegate normally
 * wires up for AppCompatActivity subclasses does not fire here --
 * [apply] recreates the passed Activity itself instead. That is safe on
 * every API level this app supports (recreate() is a base Activity
 * method) and avoids taking on an AppCompatActivity + AppCompat theme
 * migration just for this one feature.
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

    /** Apply [tag] (one of [supported]'s first values) and recreate
     * [activity] so every composable rebuilds against the new locale. */
    fun apply(activity: Activity, tag: String) {
        val locales = if (tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
        activity.recreate()
    }

    /** Best-effort Activity lookup from a Compose Context, which may be
     * wrapped (Application context, or a ContextWrapper around the
     * Activity) rather than the Activity itself. */
    fun activityOf(context: Context): Activity? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }
}
