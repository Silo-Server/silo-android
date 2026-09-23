package org.siloserver.silo.model.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Media type a relative-seek interval applies to. */
enum class SeekMedia { Video, Audiobook }

/** Direction of a relative seek. */
enum class SeekDirection { Back, Forward }

/** One resolved back/forward pair, in whole seconds. */
data class SeekIntervalPair(
    val backSeconds: Int,
    val forwardSeconds: Int,
) {
    fun seconds(direction: SeekDirection): Int = when (direction) {
        SeekDirection.Back -> backSeconds
        SeekDirection.Forward -> forwardSeconds
    }

    fun with(direction: SeekDirection, seconds: Int): SeekIntervalPair = when (direction) {
        SeekDirection.Back -> copy(backSeconds = seconds)
        SeekDirection.Forward -> copy(forwardSeconds = seconds)
    }

    val backMs: Long get() = backSeconds * 1_000L
    val forwardMs: Long get() = forwardSeconds * 1_000L
}

/**
 * Contract facts and pure rules for the revision-9 profile-wide seek
 * intervals (`player.{video,audiobook}_skip_{back,forward}_seconds`).
 *
 * The four keys are numeric enums allowed only at `profile` scope and resolve
 * `profile > default`. A value outside [CHOICES] is not something this client
 * may act on, so it falls back to the contract default rather than being
 * clamped to a neighbour. SettingsConformanceTest pins [CHOICES] and the
 * defaults to the vendored manifest so they cannot drift from the contract.
 */
object SeekIntervals {
    /** Contract revision that introduced the four keys. */
    const val MIN_CONTRACT_REVISION = 9

    /** Settings protocol version these keys are defined against. */
    const val SETTINGS_API_VERSION = 1

    /** Allowed values, in contract order. */
    val CHOICES: List<Int> = listOf(5, 10, 15, 30, 45, 60, 90)

    const val DEFAULT_BACK_SECONDS = 10
    const val DEFAULT_FORWARD_SECONDS = 30

    /** Contract defaults; identical for video and audiobooks. */
    val DEFAULTS = SeekIntervalPair(DEFAULT_BACK_SECONDS, DEFAULT_FORWARD_SECONDS)

    val KEYS: List<String> = listOf(
        SettingKeys.PLAYER_VIDEO_SKIP_BACK_SECONDS,
        SettingKeys.PLAYER_VIDEO_SKIP_FORWARD_SECONDS,
        SettingKeys.PLAYER_AUDIOBOOK_SKIP_BACK_SECONDS,
        SettingKeys.PLAYER_AUDIOBOOK_SKIP_FORWARD_SECONDS,
    )

    fun key(media: SeekMedia, direction: SeekDirection): String = when (media) {
        SeekMedia.Video -> when (direction) {
            SeekDirection.Back -> SettingKeys.PLAYER_VIDEO_SKIP_BACK_SECONDS
            SeekDirection.Forward -> SettingKeys.PLAYER_VIDEO_SKIP_FORWARD_SECONDS
        }
        SeekMedia.Audiobook -> when (direction) {
            SeekDirection.Back -> SettingKeys.PLAYER_AUDIOBOOK_SKIP_BACK_SECONDS
            SeekDirection.Forward -> SettingKeys.PLAYER_AUDIOBOOK_SKIP_FORWARD_SECONDS
        }
    }

    fun defaultSeconds(direction: SeekDirection): Int = DEFAULTS.seconds(direction)

    fun isValid(seconds: Int?): Boolean = seconds != null && seconds in CHOICES

    /**
     * Server supports the keys: same settings protocol, a manifest that knows
     * revision 9, and the batched effective read the store resolves through.
     */
    fun isSupported(capabilities: SettingsContractCapabilities): Boolean =
        capabilities.apiVersion == SETTINGS_API_VERSION &&
            capabilities.manifestRevision >= MIN_CONTRACT_REVISION &&
            capabilities.supportsBatchedEffective

    /**
     * Wire value to seconds. Only a JSON number whose value is one of
     * [CHOICES] is accepted; strings, fractions, nulls and out-of-set numbers
     * resolve to the contract default for [direction].
     */
    fun decode(value: JsonElement?, direction: SeekDirection): Int =
        decodeOrNull(value) ?: defaultSeconds(direction)

    fun decodeOrNull(value: JsonElement?): Int? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val number = primitive.doubleOrNull ?: return null
        if (number % 1.0 != 0.0) return null
        val seconds = number.toInt()
        return seconds.takeIf { it in CHOICES }
    }

    /** Resolve one media type from a batched effective read. */
    fun resolve(effective: Map<String, EffectiveSettingValue>, media: SeekMedia): SeekIntervalPair =
        SeekIntervalPair(
            backSeconds = decode(effective[key(media, SeekDirection.Back)]?.value, SeekDirection.Back),
            forwardSeconds = decode(
                effective[key(media, SeekDirection.Forward)]?.value,
                SeekDirection.Forward,
            ),
        )

    /** Short label, e.g. "10 seconds". */
    fun label(seconds: Int): String = "$seconds seconds"
}

/**
 * Legacy device-local audiobook intervals, holding only values the user
 * explicitly stored. A null direction was never set on this device and is
 * never offered for import.
 */
data class LegacyAudiobookIntervals(
    val backSeconds: Int? = null,
    val forwardSeconds: Int? = null,
) {
    fun seconds(direction: SeekDirection): Int? = when (direction) {
        SeekDirection.Back -> backSeconds
        SeekDirection.Forward -> forwardSeconds
    }

    /** Directions with a stored value the contract accepts. */
    val importable: Map<SeekDirection, Int>
        get() = SeekDirection.entries.mapNotNull { direction ->
            seconds(direction)?.takeIf(SeekIntervals::isValid)?.let { direction to it }
        }.toMap()

    val hasImportable: Boolean get() = importable.isNotEmpty()
}

/** Outcome of importing one direction of the legacy audiobook intervals. */
sealed interface SeekImportOutcome {
    /** Written at profile scope. */
    data class Imported(val seconds: Int) : SeekImportOutcome

    /** Nothing stored locally for this direction; no write was attempted. */
    data object NotStored : SeekImportOutcome

    /** A stored value the contract does not accept; no write was attempted. */
    data class Invalid(val seconds: Int) : SeekImportOutcome

    /** The write for this direction failed; the legacy value is kept for retry. */
    data class Failed(val seconds: Int, val message: String?) : SeekImportOutcome
}

/** Per-direction result of an explicit legacy import. Never atomic. */
data class SeekImportResult(
    val back: SeekImportOutcome,
    val forward: SeekImportOutcome,
) {
    fun outcome(direction: SeekDirection): SeekImportOutcome = when (direction) {
        SeekDirection.Back -> back
        SeekDirection.Forward -> forward
    }

    val allImported: Boolean
        get() = listOf(back, forward).all { it is SeekImportOutcome.Imported || it is SeekImportOutcome.NotStored } &&
            listOf(back, forward).any { it is SeekImportOutcome.Imported }

    val anyFailed: Boolean
        get() = back is SeekImportOutcome.Failed || forward is SeekImportOutcome.Failed

    /** One line per direction, suitable for a snackbar or status row. */
    fun describe(): String = SeekDirection.entries.joinToString(" ") { direction ->
        val name = if (direction == SeekDirection.Back) "Skip back" else "Skip forward"
        when (val outcome = outcome(direction)) {
            is SeekImportOutcome.Imported -> "$name imported (${outcome.seconds}s)."
            SeekImportOutcome.NotStored -> "$name had no device value."
            is SeekImportOutcome.Invalid -> "$name value ${outcome.seconds}s is not supported."
            is SeekImportOutcome.Failed ->
                "$name (${outcome.seconds}s) failed" +
                    (outcome.message?.takeIf { it.isNotBlank() }?.let { ": $it." } ?: ".")
        }
    }
}

/** Whether the connected server supports the revision-9 seek interval keys. */
enum class SeekIntervalSupport {
    Supported,

    /** Definitive: the server predates the keys. Legacy behavior, no writes. */
    Unsupported,

    /** No answer yet (cold start before the first check or cached answer). */
    Unknown,

    /**
     * The check failed (offline, server error) and no earlier answer exists
     * for this server and profile. Behaves like [Unsupported] for playback
     * and device-local editing, but is never cached and never written to, so
     * the next successful check replaces it.
     */
    Unavailable,
}

/**
 * The profile's seek-interval state as one client sees it. Surfaces must go
 * through [video]/[audiobook] with their own legacy fallback so an older or
 * not-yet-answered server keeps the pre-revision-9 behavior.
 */
data class SeekIntervalState(
    val support: SeekIntervalSupport = SeekIntervalSupport.Unknown,
    val videoIntervals: SeekIntervalPair = SeekIntervals.DEFAULTS,
    val audiobookIntervals: SeekIntervalPair = SeekIntervals.DEFAULTS,
) {
    val isSupported: Boolean get() = support == SeekIntervalSupport.Supported

    /** Profile values when the server supports them, else [legacy]. */
    fun video(legacy: SeekIntervalPair): SeekIntervalPair = if (isSupported) videoIntervals else legacy

    /** Profile values when the server supports them, else [legacy]. */
    fun audiobook(legacy: SeekIntervalPair): SeekIntervalPair =
        if (isSupported) audiobookIntervals else legacy

    fun pair(media: SeekMedia): SeekIntervalPair = when (media) {
        SeekMedia.Video -> videoIntervals
        SeekMedia.Audiobook -> audiobookIntervals
    }

    fun with(media: SeekMedia, direction: SeekDirection, seconds: Int): SeekIntervalState =
        when (media) {
            SeekMedia.Video -> copy(videoIntervals = videoIntervals.with(direction, seconds))
            SeekMedia.Audiobook -> copy(audiobookIntervals = audiobookIntervals.with(direction, seconds))
        }

    /**
     * Device-local audiobook intervals are editable when the server lacks the
     * keys or the check failed without an earlier answer (the values playback
     * then uses). While the first check is still pending neither the local
     * nor the server value is editable.
     */
    val allowsLegacyAudiobookEditing: Boolean
        get() = support == SeekIntervalSupport.Unsupported || support == SeekIntervalSupport.Unavailable
}
