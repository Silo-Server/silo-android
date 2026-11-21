package org.siloserver.silo.overlays

/**
 * Flat data bag passed to overlay renderers. Each field is optional —
 * extractors fill what's available and the registry's `getValue` lambda
 * returns `null` when the data isn't there. The shape mirrors web's
 * `OverlayData` and iOS `OverlayData`.
 */
data class OverlayData(
    // tech (from OverlaySummary)
    val resolution: String? = null,
    val hdr: String? = null,
    val audio: String? = null,
    val audioChannels: String? = null,
    val videoCodec: String? = null,
    val container: String? = null,
    val aspectRatio: String? = null,
    val releaseType: String? = null,
    val edition: String? = null,
    val multiAudio: Boolean? = null,
    val multiSub: Boolean? = null,
    // ratings / metadata (from item fields)
    val ratingImdb: Double? = null,
    val ratingTmdb: Double? = null,
    val ratingRtCritic: Int? = null,
    val ratingRtAudience: Int? = null,
    val contentRating: String? = null,
    val year: Int? = null,
    val runtime: Int? = null,
    val originalLanguage: String? = null,
    val studio: String? = null,
    val network: String? = null,
    // ribbons (data sources pending)
    val showStatus: String? = null,
    // Series: complete released seasons / released seasons.
    // Season: episodes in the library / known episodes.
    val availabilityHave: Int? = null,
    val availabilityTotal: Int? = null,
    val imdbTop250: Int? = null,
    val rtCertifiedFresh: Boolean? = null,
)
