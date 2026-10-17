package org.siloserver.silo.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QualityChoicesTest {

    @Test
    fun resolutionPickerOffersAutoThenEachCeiling() {
        assertEquals(listOf("Auto", "4K", "1080p", "720p", "480p"), QualityChoices.RESOLUTIONS.map { it.label })
        assertEquals(listOf("auto", "2160p", "1080p", "720p", "480p"), QualityChoices.RESOLUTIONS.map { it.value })
    }

    @Test
    fun resolutionCopySaysWhatPlaysAsIs() {
        assertEquals(
            "Plays the original file whenever your device and connection can handle it.",
            QualityChoices.resolutionDescription("auto"),
        )
        assertEquals(
            "Plays the original file up to 1080p. Larger files are converted.",
            QualityChoices.resolutionDescription("1080p"),
        )
        assertEquals(
            "Plays the original file up to 4K. Larger files are converted.",
            QualityChoices.resolutionDescription("2160p"),
        )
        // A legacy "original" is Auto.
        assertEquals("Auto", QualityChoices.resolutionLabel("original"))
    }

    @Test
    fun theBitrateCopyIsAddedOnlyWhenACapIsSet() {
        assertEquals(QualityChoices.resolutionDescription("720p"), QualityChoices.description("720p", null))
        assertEquals(
            "Plays the original file up to 720p. Larger files are converted. " +
                "Files above this bitrate are converted to fit.",
            QualityChoices.description("720p", 4_000),
        )
    }

    @Test
    fun bitratePickerOffersNoLimitThenTheWebLadderHighestFirst() {
        val choices = QualityChoices.bitrateChoices(null)
        assertEquals(
            listOf(
                "No limit", "200 Mbps", "100 Mbps", "60 Mbps", "40 Mbps", "20 Mbps", "15 Mbps",
                "10 Mbps", "6 Mbps", "4 Mbps", "2 Mbps", "1.5 Mbps",
            ),
            choices.map { it.label },
        )
        assertNull(choices.first().value)
        assertEquals(choices, QualityChoices.bitrateChoices(40_000), "a listed cap adds nothing")
    }

    @Test
    fun aStoredCapNoChoiceCoversStaysListedInItsPlace() {
        val choices = QualityChoices.bitrateChoices(8_500)

        assertEquals(1, choices.count { it.value == 8_500 })
        val values = choices.mapNotNull { it.value }
        assertEquals(values.sortedDescending(), values)
        assertEquals("8.5 Mbps", QualityChoices.bitrateLabel(8_500))
        // One decimal of Mbps would collide with the 1.5 Mbps choice.
        assertEquals("1520 kbps", QualityChoices.bitrateLabel(1_520))
        assertEquals("No limit", QualityChoices.bitrateLabel(null))
    }

    @Test
    fun theSummaryNamesBothAxes() {
        assertEquals("Auto", QualityChoices.summary("auto", null))
        assertEquals("1080p, up to 6 Mbps", QualityChoices.summary("1080p", 6_000))
    }

    @Test
    fun cellularQualityNeedsManifestRevision20() {
        assertEquals(20, CellularQuality.minimumRevision(SettingKeys.PLAYBACK_CELLULAR_PREFERRED_QUALITY))
        assertEquals(20, CellularQuality.minimumRevision(SettingKeys.PLAYBACK_CELLULAR_MAX_BITRATE_KBPS))
        assertNull(CellularQuality.minimumRevision(SettingKeys.PLAYBACK_PREFERRED_QUALITY))
        // The default is the 720p preset.
        assertEquals(
            "720p",
            CellularQuality.label(CellularQuality.DEFAULT_RESOLUTION, CellularQuality.DEFAULT_MAX_BITRATE_KBPS),
        )
    }

    @Test
    fun aCellularPairNoPresetCoversNeverReadsAsAPreset() {
        val presetLabels = QualityPresets.ALL.map { it.label }.toSet()

        // 1080p uncapped is not the 1080p (6 Mbps) preset.
        assertEquals("1080p, no limit", CellularQuality.label("1080p", null))
        assertEquals("720p, up to 8 Mbps", CellularQuality.label("720p", 8_000))
        for (resolution in listOf("auto", "2160p", "1080p", "720p", "480p")) {
            for (cap in listOf(null, 1_500, 2_000, 3_000, 4_000, 6_000, 8_000, 10_000)) {
                val label = CellularQuality.label(resolution, cap)
                val isPreset = QualityPresets.presetFor(resolution, cap) != null
                assertEquals(isPreset, label in presetLabels, "$resolution/$cap -> $label")
            }
        }
    }
}
