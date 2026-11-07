package org.siloserver.silo.common.requests

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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
import org.siloserver.silo.common.R
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestProgress
import org.siloserver.silo.model.request.RequestStatusTint
import org.siloserver.silo.model.request.RequestStep
import org.siloserver.silo.model.request.RequestTargetSummary
import org.siloserver.silo.model.request.requestReasonText
import org.siloserver.silo.model.request.requestStatusWithDetail
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.common.i18n.text

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
    val description = progress.longText.text()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
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

/**
 * The current resources for [RequestRowCopy], read again after an in-place
 * app language change.
 */
@Composable
@ReadOnlyComposable
fun requestResources(): Resources {
    LocalConfiguration.current // recompose on a language change
    return LocalContext.current.resources
}

/** Row and caption copy shared by the phone list and the TV preview panel. */
object RequestRowCopy {
    // Built per use: this object outlives an in-place app language change.
    private val dayFormat get() = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    private val timeFormat get() = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    @StringRes
    fun mediaTypeLabel(mediaType: String): Int = when (mediaType) {
        RequestMediaType.Movie -> R.string.requests_mediaType_movie
        RequestMediaType.Series -> R.string.requests_mediaType_series
        else -> R.string.requests_mediaType_unknown
    }

    /** "Sep 24" for a server timestamp, or null when it can't be read. */
    fun day(timestamp: String?): String? = format(timestamp, dayFormat)

    /** "Sep 10 at 8:50 AM", for the detail page's step timestamps. */
    fun stamp(resources: Resources, timestamp: String?): String? {
        val day = format(timestamp, dayFormat) ?: return null
        val time = format(timestamp, timeFormat) ?: return null
        return resources.getString(R.string.requests_stepTime, day, time)
    }

    /** "Movie · Requested Sep 24", or "Movie · Added Sep 13" once it landed. */
    fun meta(resources: Resources, record: MediaRequest, progress: RequestProgress): String {
        val kind = resources.getString(mediaTypeLabel(record.mediaType))
        if (progress.display == RequestDisplayState.InLibrary) {
            day(record.completedAt)?.let { return "$kind · ${resources.getString(R.string.requests_addedOn, it)}" }
        }
        return day(record.createdAt)?.let { "$kind · ${resources.getString(R.string.requests_requestedOn, it)}" } ?: kind
    }

    /**
     * The status line under the stage track: the long label, plus the
     * per-quality summary while in flight or the reason when stuck.
     */
    fun status(record: MediaRequest, progress: RequestProgress): UiText {
        val display = progress.display
        if (display is RequestDisplayState.NeedsAttention) {
            requestReasonText(display.reason)?.let { return requestStatusWithDetail(progress.longText, it) }
        }
        if (display == RequestDisplayState.OnTheWay) {
            RequestTargetSummary.text(record.targets)?.let { return requestStatusWithDetail(progress.longText, it) }
        }
        return progress.longText
    }

    private fun format(timestamp: String?, formatter: DateTimeFormatter): String? {
        val value = timestamp?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { formatter.format(Instant.parse(value).atZone(ZoneId.systemDefault())) }.getOrNull()
    }
}
