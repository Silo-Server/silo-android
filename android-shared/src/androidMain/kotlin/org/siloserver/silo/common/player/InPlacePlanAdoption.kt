package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackTimeline
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleValidationClaims
import org.siloserver.silo.model.playback.activeOriginalHttpClaims
import org.siloserver.silo.model.playback.carriesHlsSubtitleRendition

/**
 * Whether the player may show [target] on the stream it is playing before the
 * server answers the pick, instead of waiting for a replan and a remount.
 *
 * True for a track the media already carries and the server said the player
 * may switch to: a native track of an `original_http` stream
 * ([PlayerSubtitleInfo.nativeContainerTrackId] on a row other than the
 * selected one), or a rendition of an HLS stream that lists every embedded
 * text track ([PlayerSubtitleInfo.hlsRendition] with an index). Off qualifies
 * on the same streams. The pick still goes to the server as a track change;
 * its answer is applied in place when it keeps the transport
 * ([org.siloserver.silo.common.player.VideoSessionStartV3.Ready.inPlace]).
 *
 * Phone and TV both ask this before taking the local-first path, and then
 * whether Media3 lists the track.
 */
fun canSwitchSubtitleInPlace(
    plan: PlaybackExecutionPlan?,
    rows: List<PlayerSubtitleInfo>,
    committed: SubtitleIdentity,
    target: SubtitleIdentity,
): Boolean {
    if (plan == null || target == committed || committed is SubtitleIdentity.ServerBurnIn) return false
    return when (target) {
        SubtitleIdentity.Off -> when {
            plan.delivery == PlaybackDelivery.ORIGINAL_HTTP -> true
            plan.delivery.carriesHlsSubtitleRendition() -> rows.any { it.hlsRendition?.index != null }
            else -> false
        }
        is SubtitleIdentity.Embedded -> {
            val row = rows.singleOrNull { it.index == target.serverIndex && it.serverTrackId != null }
                ?: return false
            val nativeTrackId = target.containerTrackId
            val rendition = target.hlsRendition
            when {
                nativeTrackId != null -> plan.delivery == PlaybackDelivery.ORIGINAL_HTTP &&
                    row.nativeContainerTrackId == nativeTrackId
                rendition != null -> plan.delivery.carriesHlsSubtitleRendition() &&
                    rendition.index != null &&
                    row.hlsRendition == rendition
                else -> false
            }
        }
        is SubtitleIdentity.ServerSidecar,
        is SubtitleIdentity.ServerBurnIn,
        is SubtitleIdentity.Downloaded,
        is SubtitleIdentity.LocalMedia3,
        -> false
    }
}

/**
 * The part of [this] plan a mounted MediaItem is built from. A plan applied in
 * place differs from the playing one only in its subtitle decision, its plan
 * identity and its replan-time position, so it has the same key and leaves
 * the mounted item alone. Every other plan change also comes with a new mount
 * token, so keying a mount on this rather than on the whole plan only ever
 * skips an in-place adoption.
 */
fun PlaybackExecutionPlan.mediaMountKey(): PlaybackExecutionPlan = copy(
    planId = "",
    timeline = PlaybackTimeline(
        streamOriginSeconds = timeline.streamOriginSeconds,
        timelineOffsetSeconds = timeline.timelineOffsetSeconds,
    ),
    selectedTracks = selectedTracks.copy(subtitleIndex = null),
    source = source.copy(subtitleCodec = null),
    claims = claims.copy(subtitles = SubtitleValidationClaims()),
    // Compared as sets, like the server's own same-route check.
    transformations = transformations.sortedBy { "${it.executor}:${it.name}:${it.recipeVersion}" },
    appliedQuirks = appliedQuirks.sortedBy { it.id },
    runtimeCorrections = runtimeCorrections.sorted(),
    availableQualities = emptyList(),
    degradationWarnings = emptyList(),
    // Only the claim a decision reason names reaches the renderer.
    decisionTrace = activeOriginalHttpClaims(),
    bandwidthLimit = null,
    activeQuality = null,
    activeQualityReported = false,
)

/**
 * The plan to show once [adopted], a plan applied in place, replaces this
 * mounted one: [adopted]'s subtitle decision, plan identity and menus over
 * everything the MediaItem was built from. That keeps what the client changed
 * on the mount itself (a PCM retry's audio claims and decision trace, the
 * mount's timeline), so the media mount key and the stall detectors' key stay
 * the same and nothing remounts.
 */
fun PlaybackExecutionPlan.withInPlaceAdoption(adopted: PlaybackExecutionPlan): PlaybackExecutionPlan = copy(
    planId = adopted.planId,
    selectedTracks = selectedTracks.copy(subtitleIndex = adopted.selectedTracks.subtitleIndex),
    source = source.copy(subtitleCodec = adopted.source.subtitleCodec),
    claims = claims.copy(subtitles = adopted.claims.subtitles),
    availableQualities = adopted.availableQualities,
    degradationWarnings = adopted.degradationWarnings,
    bandwidthLimit = adopted.bandwidthLimit,
    activeQuality = adopted.activeQuality,
    activeQualityReported = adopted.activeQualityReported,
)

/**
 * The stall detectors' key for one mounted MediaItem. It leaves out the plan
 * id, which a plan applied in place changes while the same item keeps
 * playing; [mountToken] changes with every remount.
 */
fun mountedPlaybackKey(
    sessionId: String?,
    streamUrl: String?,
    plan: PlaybackExecutionPlan?,
    mountToken: Long,
): String = "$sessionId:$streamUrl:${plan?.decisionTrace?.size ?: 0}:$mountToken"
