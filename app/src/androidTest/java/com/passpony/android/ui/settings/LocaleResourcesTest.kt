package com.passpony.android.ui.settings

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.passpony.android.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P13 exit criteria: the generated non-English resource files actually
 * package and resolve, independent of the AppCompatDelegate per-app
 * language machinery LanguageManager drives (that's an Activity-level
 * concern needing a real recreate() cycle; this test only needs to know
 * the resource system itself finds and returns the right strings for a
 * given Configuration -- which is exactly what breaks if
 * scripts/xcstrings_to_strings.py ever emits a locale directory Android
 * can't parse, or a value that isn't actually different from English).
 *
 * createConfigurationContext gives a Context scoped to one Configuration
 * without touching the process-wide locale, so this runs safely
 * alongside every other instrumented test in the module.
 */
@RunWith(AndroidJUnit4::class)
class LocaleResourcesTest {

    private fun resourcesFor(languageTag: String): android.content.res.Resources {
        val appContext = ApplicationProvider.getApplicationContext<Application>()
        val config = Configuration(appContext.resources.configuration)
        config.setLocale(Locale.forLanguageTag(languageTag))
        return appContext.createConfigurationContext(config).resources
    }

    @Test
    fun spanishResources_loadAndResolveAKnownKey() {
        val english = resourcesFor("en").getString(R.string.settings_about_header)
        val spanish = resourcesFor("es").getString(R.string.settings_about_header)

        assertEquals("About", english)
        assertEquals("Acerca de", spanish)
        assertNotEquals(english, spanish)
    }

    @Test
    fun russianResources_resolvePluralQuantitiesDistinctly() {
        val resources = resourcesFor("ru")

        // Russian has four CLDR plural categories (one/few/many/other);
        // this is the case zh-Hans/en/es cannot exercise at all (they
        // only ever have one/other), so it's worth its own assertion
        // that the generated <plurals> block actually carries all four.
        val one = resources.getQuantityString(R.plurals.xc_lld_unpushed_changes, 1, 1)
        val few = resources.getQuantityString(R.plurals.xc_lld_unpushed_changes, 2, 2)
        val many = resources.getQuantityString(R.plurals.xc_lld_unpushed_changes, 5, 5)

        assertEquals("1 неотправленное изменение", one)
        assertEquals("2 неотправленных изменения", few)
        assertEquals("5 неотправленных изменений", many)
    }

    @Test
    fun everySupportedLocale_loadsWithoutFallingBackToEnglishForAKnownKey() {
        val english = resourcesFor("en").getString(R.string.settings_about_header)
        val translations = mapOf(
            "es" to "Acerca de",
            "fr" to "À propos",
            "de" to "Info",
            "ru" to "О приложении",
            "zh-Hans" to "关于",
            "pt-BR" to "Sobre",
        )
        for ((tag, expected) in translations) {
            val actual = resourcesFor(tag).getString(R.string.settings_about_header)
            assertEquals("locale $tag", expected, actual)
            assertNotEquals("locale $tag unexpectedly fell back to English", english, actual)
        }
    }
}
