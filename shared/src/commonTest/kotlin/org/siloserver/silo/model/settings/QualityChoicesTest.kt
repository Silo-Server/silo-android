package org.siloserver.silo.model.settings

import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The English these labels read is checked in android-shared's SharedPresentationTextTest.
class QualityChoicesTest {

    @Test
    fun resolutionPickerOffersAutoThenEachCeiling() {
        assertEquals(listOf("auto", "2160p", "1080p", "720p", "480p"), QualityChoices.RESOLUTIONS.map { it.value })
    }

    @Test
    fun aLegacyOriginalReadsAsAuto() {
        assertEquals(QualityChoices.resolutionLabel("auto"), QualityChoices.resolutionLabel("original"))
        assertEquals(QualityChoices.resolutionDescription("auto"), QualityChoices.resolutionDescription("original"))
    }

    @Test
    fun theBitrateCopyIsAddedOnlyWhenACapIsSet() {
        assertEquals(QualityChoices.resolutionDescription("720p"), QualityChoices.description("720p", null))
        assertEquals(QualityChoices.resolutionDescription("720p"), QualityChoices.description("720p", 0))
        assertEquals(
            UiText.Key(
                SharedString.QualityFooterJoined,
                listOf(QualityChoices.resolutionDescription("720p"), UiText.Key(SharedString.QualityFooterBitrate)),
            ),
            QualityChoices.description("720p", 4_000),
        )
    }

    @Test
    fun bitratePickerOffersNoLimitThenTheWebLadderHighestFirst() {
        val choices = QualityChoices.bitrateChoices(null)
        assertEquals(listOf(null) + QualityChoices.BITRATES_KBPS, choices.map { it.value })
        assertEquals(choices.map { QualityChoices.bitrateLabel(it.value) }, choices.map { it.label })
        assertEquals(choices, QualityChoices.bitrateChoices(40_000), "a listed cap adds nothing")
    }

    @Test
    fun aStoredCapNoChoiceCoversStaysListedInItsPlace() {
        val choices = QualityChoices.bitrateChoices(8_500)

        assertEquals(1, choices.count { it.value == 8_500 })
        val values = choices.mapNotNull { it.value }
        assertEquals(values.sortedDescending(), values)
        assertEquals(choices.size, choices.map { it.label }.toSet().size, "no two choices share a label")
        // One decimal of Mbps would collide with the 1.5 Mbps choice.
        assertEquals(
            UiText.Key(SharedString.RateKbps, listOf(UiText.Number(1_520))),
            QualityChoices.bitrateLabel(1_520),
        )
        assertEquals(QualityChoices.NO_BITRATE_LIMIT_LABEL, QualityChoices.bitrateLabel(null))
        assertEquals(QualityChoices.NO_BITRATE_LIMIT_LABEL, QualityChoices.bitrateLabel(0))
    }

    @Test
    fun theSummaryNamesBothAxes() {
        assertEquals(QualityChoices.resolutionLabel("auto"), QualityChoices.summary("auto", null))
        assertEquals(
            UiText.Key(
                SharedString.QualitySummaryUpTo,
                listOf(QualityChoices.resolutionLabel("1080p"), QualityChoices.bitrateLabel(6_000)),
            ),
            QualityChoices.summary("1080p", 6_000),
        )
    }

    @Test
    fun cellularQualityNeedsManifestRevision20() {
        assertEquals(20, CellularQuality.minimumRevision(SettingKeys.PLAYBACK_CELLULAR_PREFERRED_QUALITY))
        assertEquals(20, CellularQuality.minimumRevision(SettingKeys.PLAYBACK_CELLULAR_MAX_BITRATE_KBPS))
        assertNull(CellularQuality.minimumRevision(SettingKeys.PLAYBACK_PREFERRED_QUALITY))
        // The default is the 720p preset.
        assertEquals(
            QualityPresets.byId("720p")?.label,
            CellularQuality.label(CellularQuality.DEFAULT_RESOLUTION, CellularQuality.DEFAULT_MAX_BITRATE_KBPS),
        )
    }

    @Test
    fun aCellularPairNoPresetCoversNeverReadsAsAPreset() {
        val presetLabels = QualityPresets.ALL.map { it.label }.toSet()

        // 1080p uncapped is not the 1080p (6 Mbps) preset.
        assertEquals(
            UiText.Key(SharedString.QualitySummaryNoLimit, listOf(QualityChoices.resolutionLabel("1080p"))),
            CellularQuality.label("1080p", null),
        )
        assertEquals(QualityChoices.summary("720p", 8_000), CellularQuality.label("720p", 8_000))
        for (resolution in listOf("auto", "2160p", "1080p", "720p", "480p")) {
            for (cap in listOf(null, 1_500, 2_000, 3_000, 4_000, 6_000, 8_000, 10_000)) {
                val label = CellularQuality.label(resolution, cap)
                val isPreset = QualityPresets.presetFor(resolution, cap) != null
                assertEquals(isPreset, label in presetLabels, "$resolution/$cap -> $label")
            }
        }
    }
}
