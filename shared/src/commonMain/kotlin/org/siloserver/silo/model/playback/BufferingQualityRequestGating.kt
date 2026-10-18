package org.siloserver.silo.model.playback

/*
 * The first row of the buffering-quality *Wire contract* (the server repo's
 * `docs/design/2026-10-17-buffering-quality.md`): the server rejects unknown
 * fields, so `bandwidth_estimate_source` and `learned_limit` go out, and
 * `buffering_quality_v1` joins `client_features`, only when the server
 * advertises the feature. Against any other server the fields are stripped;
 * `bandwidth_estimate_kbps` is an older field and stays.
 *
 * Pure and idempotent, so a request can pass through more than once.
 */

/** The start request to send to a server advertising [serverFeatures]. */
fun PlaybackStartRequestV3.gatedForBufferingQuality(
    serverFeatures: Collection<String>,
): PlaybackStartRequestV3 =
    if (BUFFERING_QUALITY_V1_FEATURE in serverFeatures) {
        copy(clientFeatures = clientFeatures.withBufferingQualityFeature())
    } else {
        copy(
            clientFeatures = clientFeatures.withoutBufferingQualityFeature(),
            learnedLimit = null,
        )
    }

/** The replan request to send to a server advertising [serverFeatures]. */
fun PlaybackReplanRequestV3.gatedForBufferingQuality(
    serverFeatures: Collection<String>,
): PlaybackReplanRequestV3 =
    if (BUFFERING_QUALITY_V1_FEATURE in serverFeatures) {
        copy(
            clientFeatures = clientFeatures.withBufferingQualityFeature(),
            // A source without an estimate is a 400.
            bandwidthEstimateSource = bandwidthEstimateSource.takeIf { bandwidthEstimateKbps != null },
        )
    } else {
        copy(
            clientFeatures = clientFeatures.withoutBufferingQualityFeature(),
            bandwidthEstimateSource = null,
            learnedLimit = null,
        )
    }

private fun List<String>.withBufferingQualityFeature(): List<String> =
    if (BUFFERING_QUALITY_V1_FEATURE in this) this else this + BUFFERING_QUALITY_V1_FEATURE

private fun List<String>.withoutBufferingQualityFeature(): List<String> =
    if (BUFFERING_QUALITY_V1_FEATURE in this) filterNot { it == BUFFERING_QUALITY_V1_FEATURE } else this
