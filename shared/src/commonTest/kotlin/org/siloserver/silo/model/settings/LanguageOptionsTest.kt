package org.siloserver.silo.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.common.i18n.SharedString

class LanguageOptionsTest {

    @Test
    fun `uses the definition-specific generated contract floor`() {
        val options = LanguageOptions.namedOptions(SettingKeys.PLAYBACK_AUDIO_LANGUAGE)
        val values = options.map { it.first }

        assertTrue("en" in values)
        assertTrue("te" in values)
        assertEquals(37, values.size)
        assertTrue(options.all { it.second == UiText.LanguageName(it.first) })
    }

    @Test
    fun `unions runtime and exact current values without collapsing regions`() {
        val values = LanguageOptions.namedOptions(
            key = SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE,
            currentValue = "pt-BR",
            runtimeValues = listOf("eng", "es-MX"),
        ).map { it.first }

        assertTrue("en" in values)
        assertFalse("eng" in values, "a true ISO alias must not create a duplicate row")
        assertTrue("es-MX" in values)
        assertTrue("pt" in values)
        assertTrue("pt-BR" in values)
    }

    @Test
    fun `an exact current alias replaces the contract spelling`() {
        val values = LanguageOptions.namedOptions(
            key = SettingKeys.PLAYBACK_AUDIO_LANGUAGE,
            currentValue = "eng",
        ).map { it.first }

        assertTrue("eng" in values)
        assertFalse("en" in values)
    }

    @Test
    fun `each definition supplies its own unset label`() {
        assertEquals(
            LanguageOptions.UNSET to UiText.Key(SharedString.AudioLanguageUnset),
            LanguageOptions.options(SettingKeys.PLAYBACK_AUDIO_LANGUAGE).first(),
        )
        assertEquals(
            LanguageOptions.UNSET to UiText.Key(SharedString.SubtitleLanguageUnset),
            LanguageOptions.options(SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE).first(),
        )
        assertEquals(
            LanguageOptions.UNSET to UiText.Key(SharedString.MetadataLanguageUnset),
            LanguageOptions.options(SettingKeys.CATALOG_METADATA_LANGUAGE).first(),
        )
    }

    @Test
    fun `a stored value's label picks out its own option`() {
        val options = LanguageOptions.options(
            SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE,
            currentValue = "pt-BR",
        )
        val labels = options.map { it.second }
        options.forEachIndexed { index, (wire, _) ->
            assertEquals(index, labels.indexOf(LanguageOptions.label(wire, SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE)))
        }
        val none = UiText.Key(SharedString.SubtitleLanguageUnset)
        assertNotEquals<UiText>(none, LanguageOptions.label("pt-BR", SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE))
        assertEquals(none, LanguageOptions.label("English", SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE))
    }

    @Test
    fun `legacy label values migrate while valid tags survive`() {
        assertEquals("en", LanguageOptions.migrateLegacyValue("English"))
        assertEquals("ja", LanguageOptions.migrateLegacyValue("Japanese"))
        assertEquals("pt-BR", LanguageOptions.migrateLegacyValue("pt-BR"))
        assertEquals("zh-Hant", LanguageOptions.migrateLegacyValue("zh-Hant"))
        assertEquals("eng", LanguageOptions.migrateLegacyValue("eng"))
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue("Klingon"))
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue("Off"))
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue("Default"))
    }
}
