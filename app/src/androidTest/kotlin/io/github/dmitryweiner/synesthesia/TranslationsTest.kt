package io.github.dmitryweiner.synesthesia

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app speaks the phone's language: English, Russian, Hebrew, Ukrainian.
 *
 * That *every* line exists in every language is lint's job (`MissingTranslation`
 * is an error in `scripts/check.sh`, so a forgotten line fails the build).
 * What a device can tell and lint cannot is that the files load, that the
 * words are different words, and that the lines carrying numbers still format
 * — a translator who loses a `%1$d` leaves a crash, not a typo.
 */
@RunWith(AndroidJUnit4::class)
class TranslationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** One line from each part of the app, in the order they are met. */
    private val sample = listOf(
        R.string.play,
        R.string.points,
        R.string.view_spectrum,
        R.string.status_hint,
        R.string.menu_help,
        R.string.help_play,
        R.string.keep,
        R.string.forget,
        R.string.details_switched_on,
        R.string.settings,
        R.string.read_more,
        R.string.meter_loud,
        R.string.msg_not_a_token,
        R.string.channel_playback,
    )

    @Test
    fun theWordsAreTheLanguagesOwn() {
        val english = resourcesIn("en")
        for (tag in TRANSLATED) {
            val them = resourcesIn(tag)
            for (line in sample) {
                val said = them.getString(line)
                assertTrue("$tag: ${english.getResourceEntryName(line)} is empty", said.isNotBlank())
                assertNotEquals(
                    "$tag: ${english.getResourceEntryName(line)} is still English",
                    english.getString(line),
                    said,
                )
            }
        }
    }

    @Test
    fun theLinesWithNumbersInThemStillFormat() {
        for (tag in LANGUAGES) {
            val r = resourcesIn(tag)
            // Each of these is handed its numbers by the app; a placeholder
            // lost in translation throws here rather than on someone's phone.
            assertTrue(tag, r.getString(R.string.scouted, 2, 3).contains("2"))
            assertTrue(tag, r.getString(R.string.scouting, 6).contains("6"))
            assertTrue(tag, r.getString(R.string.undo_left, 4).contains("4"))
            assertTrue(tag, r.getString(R.string.details_step, 3, 0.12, 2).contains("3"))
            assertTrue(tag, r.getString(R.string.spread, "0.12").contains("0.12"))
            assertTrue(tag, r.getString(R.string.volume, "0.80").contains("0.80"))
            assertTrue(tag, r.getString(R.string.menu_version, "0.2.0").contains("0.2.0"))
            assertTrue(tag, r.getString(R.string.forget_title, "Dawn").contains("Dawn"))
            assertTrue(tag, r.getString(R.string.frame_stats, 7, 0.5, -1.2, 3.0).contains("7"))
            val stats = r.getString(R.string.output_stats, 48_000, 20, 12.0, 30.0, 0)
            assertTrue("$tag: $stats", stats.contains("48000") && stats.contains("12.0"))
        }
    }

    @Test
    fun thePhonesLanguageIsWhatTheAppSays() {
        // The one word the whole app is arranged around, in each language.
        assertEquals("Points", resourcesIn("en").getString(R.string.points))
        assertEquals("Точки", resourcesIn("ru").getString(R.string.points))
        assertEquals("נקודות", resourcesIn("he").getString(R.string.points))
        assertEquals("Точки", resourcesIn("uk").getString(R.string.points))
    }

    private fun resourcesIn(tag: String): Resources {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(configuration).resources
    }

    private companion object {
        val TRANSLATED = listOf("ru", "he", "uk")
        val LANGUAGES = listOf("en") + TRANSLATED
    }
}
