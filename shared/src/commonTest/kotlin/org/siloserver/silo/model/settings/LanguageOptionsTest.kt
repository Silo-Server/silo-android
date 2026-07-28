package org.siloserver.silo.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageOptionsTest {

    @Test
    fun everyOptionPersistsALanguageTagRatherThanItsLabel() {
        // The server validates playback.audio_language and the profile's
        // subtitle_language as BCP 47. A display name is rejected outright, and
        // never matched a track even when it was accepted.
        val tagShape = Regex("^[a-z]{2,3}$")
        for ((wire, label) in LanguageOptions.tags) {
            assertTrue(tagShape.matches(wire), "$label persists \"$wire\", which is not a tag")
        }
    }

    @Test
    fun optionsLeadWithTheUnsetChoiceUnderTheCallersLabel() {
        val audio = LanguageOptions.options(unsetLabel = "Default")
        val subtitles = LanguageOptions.options(unsetLabel = "Off")

        assertEquals(LanguageOptions.UNSET to "Default", audio.first())
        assertEquals(LanguageOptions.UNSET to "Off", subtitles.first())
        assertEquals(LanguageOptions.tags.size + 1, audio.size)
        assertEquals(audio.drop(1), subtitles.drop(1))
    }

    @Test
    fun labelsAndWireValuesRoundTrip() {
        for ((wire, label) in LanguageOptions.tags) {
            assertEquals(label, LanguageOptions.label(wire, unsetLabel = "Default"))
            assertEquals(wire, LanguageOptions.wireValue(label))
        }
    }

    @Test
    fun anUnsetOrLegacyLabelValueReadsAsTheUnsetLabel() {
        assertEquals("Default", LanguageOptions.label("", unsetLabel = "Default"))
        assertEquals("Default", LanguageOptions.label(null, unsetLabel = "Default"))
        // A value stored by a build that persisted labels must not be echoed
        // back as if it were a choice the server holds.
        assertEquals("Off", LanguageOptions.label("English", unsetLabel = "Off"))
    }

    @Test
    fun aPreservedTagOutsideTheTableReadsAsItselfNotAsUnset() {
        // migrateLegacyValue passes these through, so playback still applies
        // them; showing "Off"/"Default" would claim an active preference is
        // disabled.
        assertEquals("nl", LanguageOptions.label("nl", unsetLabel = "Off"))
        assertEquals("pt-BR", LanguageOptions.label("pt-BR", unsetLabel = "Default"))
    }

    @Test
    fun legacyLabelValuesMigrateToTheirTags() {
        // What older builds actually wrote to DataStore and PUT to the server.
        assertEquals("en", LanguageOptions.migrateLegacyValue("English"))
        assertEquals("ja", LanguageOptions.migrateLegacyValue("Japanese"))
        // Already-correct values survive untouched.
        assertEquals("en", LanguageOptions.migrateLegacyValue("en"))
        assertEquals("pt", LanguageOptions.migrateLegacyValue("pt"))
        // Unset stays unset, and anything unrecognized clears rather than
        // continuing to fail validation on every flush.
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue(""))
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue(null))
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue("Klingon"))
    }

    @Test
    fun validTagsOutsideThePickerTableSurviveMigration() {
        // The table lists only the languages the pickers offer. A tag synced
        // from another surface (web offers 37 languages) is server-valid and
        // must pass through, not be erased to "no preference".
        assertEquals("nl", LanguageOptions.migrateLegacyValue("nl"))
        assertEquals("hi", LanguageOptions.migrateLegacyValue("hi"))
        assertEquals("pt-BR", LanguageOptions.migrateLegacyValue("pt-BR"))
        assertEquals("zh-Hant", LanguageOptions.migrateLegacyValue("zh-Hant"))
        assertEquals("eng", LanguageOptions.migrateLegacyValue("eng"))
    }

    @Test
    fun legacyUnsetLabelsMigrateToUnset() {
        // "Off"/"Default" were the old pickers' unset rows; "Off" is short
        // enough to look tag-shaped and must still clear.
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue("Off"))
        assertEquals(LanguageOptions.UNSET, LanguageOptions.migrateLegacyValue("Default"))
    }
}
