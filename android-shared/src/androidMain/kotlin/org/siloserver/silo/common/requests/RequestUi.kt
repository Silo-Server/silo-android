package org.siloserver.silo.common.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestProgress
import org.siloserver.silo.model.request.RequestStatusTint
import org.siloserver.silo.model.request.RequestStep
import org.siloserver.silo.model.request.RequestTargetSummary
import org.siloserver.silo.model.request.requestReasonCopy

/**
 * The request status colors, shared by phone and TV and matching the Apple
 * clients. Status text stays monochrome; the dot and the current stage are the
 * only chromatic elements.
 */
object RequestColors {
    val Amber = Color(0xFFF59E0B)
    val Sky = Color(0xFF38BDF8)
    val Emerald = Color(0xFF34D399)
    val Rose = Color(0xFFFB7185)
}

fun RequestStatusTint.color(neutral: Color): Color = when (this) {
    RequestStatusTint.Amber -> RequestColors.Amber
    RequestStatusTint.Sky -> RequestColors.Sky
    RequestStatusTint.Emerald -> RequestColors.Emerald
    RequestStatusTint.Rose -> RequestColors.Rose
    RequestStatusTint.Neutral -> neutral
}

/**
 * Four-segment stage track: steps behind the request are solid, the step it's
 * on carries the status tint, and the rest stay dim.
 */
@Composable
fun RequestStageTrack(
    progress: RequestProgress,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    doneColor: Color = Color(0xFFEDEDED).copy(alpha = 0.82f),
    restColor: Color = Color.White.copy(alpha = 0.14f),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = progress.longLabel },
        horizontalArrangement = Arrangement.spacedBy(height),
    ) {
        RequestStep.entries.forEach { step ->
            val fill = when {
                step == progress.currentStep -> progress.tint.color(restColor)
                step.ordinal < progress.completedSteps -> doneColor
                else -> restColor
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .background(fill, RoundedCornerShape(50)),
            )
        }
    }
}

/** Row and caption copy shared by the phone list and the TV preview panel. */
object RequestRowCopy {
    private val dayFormat = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    private val stampFormat = DateTimeFormatter.ofPattern("MMM d 'at' h:mm a", Locale.getDefault())

    fun mediaTypeLabel(mediaType: String): String = when (mediaType) {
        RequestMediaType.Movie -> "Movie"
        RequestMediaType.Series -> "Series"
        else -> "Title"
    }

    /** "Sep 24" for a server timestamp, or null when it can't be read. */
    fun day(timestamp: String?): String? = format(timestamp, dayFormat)

    /** "Sep 10 at 8:50 AM", for the detail page's step timestamps. */
    fun stamp(timestamp: String?): String? = format(timestamp, stampFormat)

    /** "Movie · Requested Sep 24", or "Movie · Added Sep 13" once it landed. */
    fun meta(record: MediaRequest, progress: RequestProgress): String {
        val kind = mediaTypeLabel(record.mediaType)
        if (progress.display == RequestDisplayState.InLibrary) {
            day(record.completedAt)?.let { return "$kind · Added $it" }
        }
        return day(record.createdAt)?.let { "$kind · Requested $it" } ?: kind
    }

    /**
     * The status line under the stage track: the long label, plus the
     * per-quality summary while in flight or the reason when stuck.
     */
    fun status(record: MediaRequest, progress: RequestProgress): String {
        val display = progress.display
        if (display is RequestDisplayState.NeedsAttention) {
            requestReasonCopy(display.reason)?.let { return "${progress.longLabel} · $it" }
        }
        if (display == RequestDisplayState.OnTheWay) {
            RequestTargetSummary.text(record.targets)?.let { return "${progress.longLabel} · $it" }
        }
        return progress.longLabel
    }

    /** "Series · 1080p · 4K" for admin rows. */
    fun kindAndQuality(record: MediaRequest): String =
        listOfNotNull(mediaTypeLabel(record.mediaType), RequestTargetSummary.qualities(record.targets)).joinToString(" · ")

    private fun format(timestamp: String?, formatter: DateTimeFormatter): String? {
        val value = timestamp?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { formatter.format(Instant.parse(value).atZone(ZoneId.systemDefault())) }.getOrNull()
    }
}
