package org.siloserver.silo.model.playback

import org.siloserver.silo.model.catalog.WatchDetail

/**
 * The audio and subtitle the viewer picked during this playback session, for
 * the series (or movie) [seriesKey] — carried to the next episode, a version
 * switch or a restart. They are described by track identity, so each target
 * file resolves its own equivalent; a position on the old file is never
 * reused. Null [audio] or [subtitle] means no pick of that kind was made.
 *
 * Carrying a choice never saves it: the pick itself was saved as the series
 * choice when it was made. This covers a pick that has not landed or could not
 * be saved (a download, a refused write).
 */
data class CarriedSeriesTrackChoices(
    val seriesKey: String,
    val audio: SeriesAudioChoice? = null,
    val subtitle: SeriesSubtitleChoice? = null,
    /**
     * When set, the choices apply to this item only, whatever series key it
     * reports — a version switch, whose item may have played without its
     * series known.
     */
    val contentId: String? = null,
)

/**
 * [this] with [carried] laid over its effective fields, when it belongs to the
 * same series (or is the very item [CarriedSeriesTrackChoices.contentId] names).
 */
fun WatchDetail.withCarriedSeriesTrackChoices(carried: CarriedSeriesTrackChoices?): WatchDetail {
    carried ?: return this
    val applies = carried.contentId?.let { it == contentId }
        ?: (seriesTrackChoiceKey(seriesId, contentId) == carried.seriesKey)
    if (!applies) return this
    return withPendingSeriesSubtitleChoice(carried.subtitle?.let(::PendingSeriesSubtitleChoice))
        .withPendingSeriesAudioChoice(carried.audio?.let(::PendingSeriesAudioChoice))
}

/** [this] with a new pick recorded; a pick for another series starts over. */
fun CarriedSeriesTrackChoices?.withPick(
    seriesKey: String,
    audio: SeriesAudioChoice? = null,
    subtitle: SeriesSubtitleChoice? = null,
): CarriedSeriesTrackChoices {
    val base = this?.takeIf { it.seriesKey == seriesKey } ?: CarriedSeriesTrackChoices(seriesKey)
    return base.copy(audio = audio ?: base.audio, subtitle = subtitle ?: base.subtitle)
}
