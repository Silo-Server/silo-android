package org.siloserver.silo.common.player.seek

import org.siloserver.silo.model.playback.PlaybackTimeline
import org.siloserver.silo.model.playback.PlaybackTimelineV3

/**
 * Coordinate to retain when a player item is replaced or the server must
 * create a new transport anchored at a requested source position.
 */
enum class PlaybackSeekRestoration {
    PlayerPosition,
    SourcePosition,
    Unknown,
}

/** Why a seek cannot be executed against the currently mounted Media3 item. */
enum class ServerReanchorReason {
    UnknownRestoration,
    InvalidTimelineMapping,
    InvalidSeekWindow,
    OutsideSeekWindow,
    UnknownSeekWindow,
}

/**
 * Result of evaluating a source/movie-time seek against the active transport.
 *
 * [NativeSeek] is safe to pass to `Player.seekTo`. [ServerReanchor] must be
 * sent back through protocol V3 using [targetSourcePositionSeconds]; a player
 * seek against the current item would either be outside its known window or
 * rely on timeline semantics the client cannot prove.
 */
sealed interface PlaybackSeekDecision {
    val targetSourcePositionSeconds: Double
    val restoration: PlaybackSeekRestoration

    data class NativeSeek(
        override val targetSourcePositionSeconds: Double,
        val targetPlayerPositionSeconds: Double,
        override val restoration: PlaybackSeekRestoration,
    ) : PlaybackSeekDecision

    data class ServerReanchor(
        override val targetSourcePositionSeconds: Double,
        override val restoration: PlaybackSeekRestoration,
        val reason: ServerReanchorReason,
    ) : PlaybackSeekDecision
}

/**
 * Coordinates to use when a protocol-v3 replan replaces the mounted Media3
 * item. The replan request carries a source/movie position, while Media3
 * seeks within the returned plan's local timeline.
 */
data class PlaybackReplanMountPosition(
    val playerPositionSeconds: Double,
    val sourcePositionSeconds: Double,
)

/** Maps a Media3-local position onto the source/movie timeline. */
fun PlaybackTimeline.sourcePositionForPlayer(playerPositionSeconds: Double): Double? =
    mapNonNegativePosition(playerPositionSeconds, timelineOffsetSeconds, Double::plus)

/** Maps a source/movie position onto the currently mounted Media3 timeline. */
fun PlaybackTimeline.playerPositionForSource(sourcePositionSeconds: Double): Double? =
    mapNonNegativePosition(sourcePositionSeconds, timelineOffsetSeconds, Double::minus)

/**
 * Restores the source position sent with a replan on the returned timeline.
 *
 * A subtitle-only replan may deliberately reuse an append-only HLS transport
 * whose origin predates the current playhead. In that case
 * [playerStartSeconds] describes the transport's default entry point, not the
 * viewer's requested position. Falling back to it unconditionally rewinds the
 * movie to the beginning of the retained manifest window.
 */
fun PlaybackTimeline.replanMountPositionForSource(
    sourcePositionSeconds: Double,
): PlaybackReplanMountPosition = resolveReplanMountPosition(
    requestedSourcePositionSeconds = sourcePositionSeconds,
    sourceStartSeconds = sourceStartSeconds,
    playerStartSeconds = playerStartSeconds,
    timelineOffsetSeconds = timelineOffsetSeconds,
)

/** Protocol-v3 wire-timeline counterpart used before the plan is projected. */
fun PlaybackTimelineV3.replanMountPositionForSource(
    sourcePositionSeconds: Double,
): PlaybackReplanMountPosition = resolveReplanMountPosition(
    requestedSourcePositionSeconds = sourcePositionSeconds,
    sourceStartSeconds = sourceStartSeconds,
    playerStartSeconds = playerStartSeconds,
    timelineOffsetSeconds = timelineOffsetSeconds,
)

private fun resolveReplanMountPosition(
    requestedSourcePositionSeconds: Double,
    sourceStartSeconds: Double,
    playerStartSeconds: Double,
    timelineOffsetSeconds: Double,
): PlaybackReplanMountPosition {
    val restoredPlayerPosition = mapNonNegativePosition(
        requestedSourcePositionSeconds,
        timelineOffsetSeconds,
        Double::minus,
    )
    if (restoredPlayerPosition != null) {
        return PlaybackReplanMountPosition(
            playerPositionSeconds = restoredPlayerPosition,
            sourcePositionSeconds = requestedSourcePositionSeconds,
        )
    }

    val fallbackPlayerPosition = playerStartSeconds
        .takeIf { it.isFinite() && it >= 0.0 }
        ?: 0.0
    val fallbackSourcePosition = sourceStartSeconds
        .takeIf { it.isFinite() && it >= 0.0 }
        ?: mapNonNegativePosition(
            fallbackPlayerPosition,
            timelineOffsetSeconds,
            Double::plus,
        )
        ?: 0.0
    return PlaybackReplanMountPosition(
        playerPositionSeconds = fallbackPlayerPosition,
        sourcePositionSeconds = fallbackSourcePosition,
    )
}

/** Parses the closed protocol-V3 restoration vocabulary conservatively. */
fun PlaybackTimeline.seekRestorationMode(): PlaybackSeekRestoration = when (seekRestoration) {
    "player_position" -> PlaybackSeekRestoration.PlayerPosition
    "source_position" -> PlaybackSeekRestoration.SourcePosition
    else -> PlaybackSeekRestoration.Unknown
}

/**
 * Chooses between a native Media3 seek and a protocol-V3 server reanchor.
 *
 * Seek-window bounds are source/movie-time coordinates. A complete valid
 * window proves that a target inside it is locally seekable even when
 * [PlaybackTimeline.canSeekAnywhere] is false. With no complete window, the
 * server's explicit `can_seek_anywhere` claim is required; a false claim plus
 * an absent or partial window is deliberately treated as unknown and reanchored.
 * Known bounds remain authoritative even when `canSeekAnywhere` is true.
 *
 * Exception: the caller may prove local seekability itself by passing
 * [mountedSeekableSourceRange] — the source-time extent the currently mounted
 * transport provably covers (e.g. an append-only HLS manifest's produced head,
 * or a stream the player reports as seekable with a known length). A target
 * inside that extent is a plain `Player.seekTo` even when the published window
 * is open-ended; only targets the mounted transport cannot serve reanchor.
 */
fun PlaybackTimeline.decideSeek(
    targetSourcePositionSeconds: Double,
    mountedSeekableSourceRange: ClosedRange<Double>? = null,
): PlaybackSeekDecision {
    require(targetSourcePositionSeconds.isFinite() && targetSourcePositionSeconds >= 0.0) {
        "Seek target must be a finite, non-negative source position."
    }

    val restoration = seekRestorationMode()
    if (restoration == PlaybackSeekRestoration.Unknown) {
        return PlaybackSeekDecision.ServerReanchor(
            targetSourcePositionSeconds = targetSourcePositionSeconds,
            restoration = restoration,
            reason = ServerReanchorReason.UnknownRestoration,
        )
    }

    val window = seekWindow()
    if (window.invalid) {
        return PlaybackSeekDecision.ServerReanchor(
            targetSourcePositionSeconds = targetSourcePositionSeconds,
            restoration = restoration,
            reason = ServerReanchorReason.InvalidSeekWindow,
        )
    }
    if (window.excludes(targetSourcePositionSeconds)) {
        return PlaybackSeekDecision.ServerReanchor(
            targetSourcePositionSeconds = targetSourcePositionSeconds,
            restoration = restoration,
            reason = ServerReanchorReason.OutsideSeekWindow,
        )
    }
    if (!window.complete && !canSeekAnywhere) {
        if (mountedSeekableSourceRange?.contains(targetSourcePositionSeconds) != true) {
            return PlaybackSeekDecision.ServerReanchor(
                targetSourcePositionSeconds = targetSourcePositionSeconds,
                restoration = restoration,
                reason = ServerReanchorReason.UnknownSeekWindow,
            )
        }
    }

    val playerPosition = playerPositionForSource(targetSourcePositionSeconds)
        ?: return PlaybackSeekDecision.ServerReanchor(
            targetSourcePositionSeconds = targetSourcePositionSeconds,
            restoration = restoration,
            reason = ServerReanchorReason.InvalidTimelineMapping,
        )

    return PlaybackSeekDecision.NativeSeek(
        targetSourcePositionSeconds = targetSourcePositionSeconds,
        targetPlayerPositionSeconds = playerPosition,
        restoration = restoration,
    )
}

private data class SourceSeekWindow(
    val startSeconds: Double?,
    val endSeconds: Double?,
    val invalid: Boolean,
) {
    val complete: Boolean
        get() = !invalid && startSeconds != null && endSeconds != null

    fun excludes(targetSeconds: Double): Boolean =
        startSeconds?.let { targetSeconds < it } == true ||
            endSeconds?.let { targetSeconds > it } == true
}

private fun PlaybackTimeline.seekWindow(): SourceSeekWindow {
    val start = seekWindowStartSeconds
    val end = seekWindowEndSeconds
    val invalid = start?.let { !it.isFinite() || it < 0.0 } == true ||
        end?.let { !it.isFinite() || it < 0.0 } == true ||
        (start != null && end != null && start > end)
    return SourceSeekWindow(start, end, invalid)
}

private inline fun mapNonNegativePosition(
    positionSeconds: Double,
    offsetSeconds: Double,
    operation: (Double, Double) -> Double,
): Double? {
    if (!positionSeconds.isFinite() || positionSeconds < 0.0 || !offsetSeconds.isFinite()) {
        return null
    }
    return operation(positionSeconds, offsetSeconds)
        .takeIf { it.isFinite() && it >= 0.0 }
}
