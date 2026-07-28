package org.siloserver.silo.model.settings

/**
 * The quality picker's presets.
 *
 * The server stores two orthogonal values — `playback.preferred_quality` (a
 * resolution cap) and `playback.max_bitrate_kbps` (a bandwidth cap, null for
 * uncapped). This table composes them into the single list a user picks from,
 * and is a field-for-field port of the web client's `qualityPresets.ts` so a
 * preset chosen on one platform reads back with the same label on the others.
 *
 * Presets live here rather than in the contract on purpose. Baking "high" into
 * an enum member would freeze what it means: retuning 1080p High from 10 to 12
 * Mbps would be a contract change every client has to agree to. As a
 * client-side table it is a one-line edit, and older servers keep working
 * because they only ever see the two axes they already understand.
 *
 * The compound legacy spellings (`1080p-high`, `720p-high`, …) were never a
 * third dimension, only a bitrate written into the resolution string. They are
 * dead: nothing here produces one, and [presetFor] decomposes any that a
 * pre-contract local value still holds.
 */
data class QualityPreset(
    val id: String,
    val label: String,
    val description: String,
    /** A `playback.preferred_quality` enum member. */
    val resolution: String,
    /** null is uncapped — the absence of a `playback.max_bitrate_kbps` row. */
    val bitrateKbps: Int?,
)

object QualityPresets {

    const val RESOLUTION_AUTO = "auto"
    const val RESOLUTION_ORIGINAL = "original"

    val ALL: List<QualityPreset> = listOf(
        QualityPreset(
            id = "auto",
            label = "Auto",
            description = "Silo picks based on your connection.",
            resolution = RESOLUTION_AUTO,
            bitrateKbps = null,
        ),
        QualityPreset(
            id = "original",
            label = "Original",
            description = "Never transcode. Needs bandwidth to match the file.",
            resolution = RESOLUTION_ORIGINAL,
            bitrateKbps = null,
        ),
        QualityPreset(
            id = "2160p",
            label = "4K",
            description = "Up to 2160p.",
            resolution = "2160p",
            bitrateKbps = null,
        ),
        QualityPreset(
            id = "1080p-high",
            label = "1080p High",
            description = "1080p at up to 10 Mbps.",
            resolution = "1080p",
            bitrateKbps = 10000,
        ),
        QualityPreset(
            id = "1080p",
            label = "1080p",
            description = "1080p at up to 6 Mbps.",
            resolution = "1080p",
            bitrateKbps = 6000,
        ),
        QualityPreset(
            id = "1080p-low",
            label = "1080p Low",
            description = "1080p at up to 3 Mbps, for a slower link.",
            resolution = "1080p",
            bitrateKbps = 3000,
        ),
        QualityPreset(
            id = "720p-high",
            label = "720p High",
            description = "720p at up to 4 Mbps.",
            resolution = "720p",
            bitrateKbps = 4000,
        ),
        QualityPreset(
            id = "720p",
            label = "720p",
            description = "720p at up to 2 Mbps.",
            resolution = "720p",
            bitrateKbps = 2000,
        ),
        QualityPreset(
            id = "480p",
            label = "480p",
            description = "480p at up to 1.5 Mbps, for the tightest connections.",
            resolution = "480p",
            bitrateKbps = 1500,
        ),
    )

    /** The preset for a stored (resolution, bitrate) pair, or null for a combination no preset covers. */
    fun presetFor(resolution: String?, bitrateKbps: Int?): QualityPreset? {
        val normalizedResolution = normalizeResolution(resolution)
        val normalizedBitrate = bitrateKbps?.takeIf { it > 0 }
        return ALL.firstOrNull {
            it.resolution == normalizedResolution && it.bitrateKbps == normalizedBitrate
        }
    }

    fun byId(id: String?): QualityPreset? = ALL.firstOrNull { it.id == id }

    /**
     * A label for any stored pair, including combinations no preset covers —
     * someone who set the two axes independently through the API, or whose
     * values came from a legacy compound value.
     */
    fun describe(resolution: String?, bitrateKbps: Int?): String {
        presetFor(resolution, bitrateKbps)?.let { return it.label }

        val normalized = normalizeResolution(resolution)
        val resolutionLabel = when (normalized) {
            RESOLUTION_AUTO -> "Auto"
            RESOLUTION_ORIGINAL -> "Original"
            "2160p" -> "4K"
            else -> normalized
        }
        val capped = bitrateKbps?.takeIf { it > 0 } ?: return resolutionLabel
        val mbps = capped / 1000.0
        val rounded = if (capped % 1000 == 0) "${capped / 1000}" else formatOneDecimal(mbps)
        return "$resolutionLabel at $rounded Mbps"
    }

    /**
     * Reduces any stored resolution — including the compound transcode-ladder
     * spellings older builds wrote (`1080p-high`, `720p-8`, `4k`) — to a member
     * of the contract's enum. The bitrate half of a compound value is dropped
     * rather than guessed at: the bitrate axis carries it now, and inventing a
     * cap the user never chose would silently throttle playback.
     */
    fun normalizeResolution(value: String?): String {
        val trimmed = value?.trim()?.lowercase().orEmpty()
        if (trimmed.isEmpty()) return RESOLUTION_AUTO
        if (trimmed == RESOLUTION_AUTO || trimmed == RESOLUTION_ORIGINAL) return trimmed
        if (trimmed == "4k") return "2160p"
        val head = trimmed.substringBefore('-')
        return when (head) {
            "480p", "720p", "1080p", "2160p" -> head
            "4k" -> "2160p"
            else -> RESOLUTION_AUTO
        }
    }

    private fun formatOneDecimal(value: Double): String {
        val tenths = kotlin.math.round(value * 10).toInt()
        return "${tenths / 10}.${tenths % 10}"
    }
}
