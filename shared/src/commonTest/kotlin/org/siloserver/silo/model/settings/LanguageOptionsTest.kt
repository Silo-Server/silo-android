package org.siloserver.silo.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LanguageOptionsTest {

    @Test
    fun `uses the definition-specific generated contract floor`() {
        val options = LanguageOptions.namedOptions(SettingKeys.PLAYBACK_AUDIO_LANGUAGE)
        val values = options.map { it.first }

        assertTrue("en" in values)
        assertTrue("te" in values)
        assertEquals(37, values.size)
        assertTrue(options.all { it.second.isNotBlank() })
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
            LanguageOptions.UNSET to "No preference",
            LanguageOptions.options(SettingKeys.PLAYBACK_AUDIO_LANGUAGE).first(),
        )
        assertEquals(
            LanguageOptions.UNSET to "None",
            LanguageOptions.options(SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE).first(),
        )
        assertEquals(
            LanguageOptions.UNSET to "Library default",
            LanguageOptions.options(SettingKeys.CATALOG_METADATA_LANGUAGE).first(),
        )
    }

    @Test
    fun `labels and wire values round trip through the rendered options`() {
        val options = LanguageOptions.options(
            SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE,
            currentValue = "pt-BR",
        )
        for ((wire, label) in options) {
            assertEquals(wire, LanguageOptions.wireValue(label, options))
        }
        assertNotEquals(
            "None",
            LanguageOptions.label("pt-BR", SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE),
        )
        assertEquals(
            "None",
            LanguageOptions.label("English", SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE),
        )
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
