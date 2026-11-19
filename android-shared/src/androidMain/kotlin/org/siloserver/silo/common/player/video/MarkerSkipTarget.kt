package org.siloserver.silo.common.player.video

import org.siloserver.silo.domain.player.MarkerKind
import org.siloserver.silo.domain.player.MarkerTimeline

enum class ManualMarkerKind { Recap, Credits }
data class MarkerSkipTarget(val kind: ManualMarkerKind, val endSeconds: Double)

/**
 * The recap or credits occurrence playing at [position], skipped to its own
 * end. Recap wins if provider ranges overlap.
 */
fun markerSkipTarget(position: Double, markers: MarkerTimeline): MarkerSkipTarget? {
    markers.active(MarkerKind.Recap, position)?.let { return MarkerSkipTarget(ManualMarkerKind.Recap, it.end) }
    markers.active(MarkerKind.Credits, position)?.let { return MarkerSkipTarget(ManualMarkerKind.Credits, it.end) }
    return null
}
