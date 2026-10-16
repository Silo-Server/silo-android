package org.siloserver.silo.tv.ui.screens.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.profile.ANY_RATING_LABEL
import org.siloserver.silo.model.profile.AssignableLibraries
import org.siloserver.silo.model.profile.ProfileAccessLimits
import org.siloserver.silo.model.profile.contentRatingOptions
import org.siloserver.silo.model.profile.ratingCeilingCaption
import org.siloserver.silo.model.profile.ratingIsUnderCeiling
import org.siloserver.silo.tv.ui.components.TvHeroActionPill
import org.siloserver.silo.tv.ui.components.TvPillVariant
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.shell.TvLibraryTabType
import org.siloserver.silo.tv.ui.util.visibleOnTv

/**
 * D-pad links through the profile limits, which sit between the Child switch
 * and the submit pill. [restrict] is the selected "All libraries / Choose
 * libraries" option. [rows] holds one requester per library row, or one for
 * Try Again after a failed load.
 */
internal class TvProfileAccessFocus(
    val child: FocusRequester,
    val submit: FocusRequester,
    val rating: FocusRequester,
    val restrict: FocusRequester,
    val rows: List<FocusRequester>,
) {
    val beforeSubmit: FocusRequester get() = rows.lastOrNull() ?: restrict
}

/** Libraries the TV offers: book libraries stay off TV (AGENTS.md). */
private fun AssignableLibraries.shownOnTv(): List<UserLibrary> =
    (this as? AssignableLibraries.Loaded)?.libraries?.visibleOnTv().orEmpty()

@Composable
internal fun rememberTvProfileAccessFocus(
    access: ProfileAccessLimits,
    libraries: AssignableLibraries,
    childFocusRequester: FocusRequester,
    submitFocusRequester: FocusRequester,
): TvProfileAccessFocus {
    val rating = remember { FocusRequester() }
    val restrict = remember { FocusRequester() }
    val rowCount = when {
        !access.libraryRestrictionsEnabled -> 0
        libraries is AssignableLibraries.Failed -> 1
        else -> libraries.shownOnTv().size
    }
    val rows = remember(rowCount) { List(rowCount) { FocusRequester() } }
    return TvProfileAccessFocus(childFocusRequester, submitFocusRequester, rating, restrict, rows)
}

private val PanelShape = RoundedCornerShape(16.dp)

/**
 * A profile's limits on TV, in one panel under the Child switch: the rating
 * ceiling as a ladder, then which libraries the profile sees. Shown for every
 * profile, as on the phone and the web; Child only fills in kid-safe defaults.
 */
@Composable
internal fun TvProfileAccessControls(
    access: ProfileAccessLimits,
    libraries: AssignableLibraries,
    focus: TvProfileAccessFocus,
    callbacks: TvProfileFormCallbacks,
) {
    // Try Again disappears once a retry succeeds; hand its focus to the list.
    // Removing the focused button also reports a focus loss, so only a loss
    // while the failure is still on screen (the user moved away) cancels it.
    var retryHandoff by remember { mutableStateOf(false) }
    val currentLibraries by rememberUpdatedState(libraries)
    val loaded = libraries is AssignableLibraries.Loaded
    LaunchedEffect(loaded) {
        if (loaded && retryHandoff) {
            retryHandoff = false
            (focus.rows.firstOrNull() ?: focus.restrict)
                .claimFocusOrReport(target = "profile_libraries", action = "retry_loaded")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), PanelShape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), PanelShape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        TvPanelLabel("Maximum rating")
        TvRatingLadder(access = access, focus = focus, onSelected = callbacks.onContentRatingSelected)
        TvPanelHint(ratingCeilingCaption(access.maxContentRating), Modifier.padding(top = 10.dp))

        Box(
            modifier = Modifier
                .padding(vertical = 18.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.10f)),
        )

        TvPanelLabel("Libraries")
        TvLibraryModeSwitch(
            restricted = access.libraryRestrictionsEnabled,
            focus = focus,
            onRestrictedChange = callbacks.onLibraryRestrictionsToggled,
        )

        if (!access.libraryRestrictionsEnabled) return@Column
        when (libraries) {
            AssignableLibraries.Loading -> TvPanelHint("Loading libraries…", Modifier.padding(top = 14.dp))

            is AssignableLibraries.Failed -> Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) { TvPanelHint(libraries.text) }
                // Stays mounted and enabled while retrying, so focus doesn't jump.
                TvHeroActionPill(
                    label = if (libraries.retrying) "Loading…" else "Try Again",
                    icon = Icons.Filled.Refresh,
                    variant = TvPillVariant.Hollow,
                    heightOverride = 32.dp,
                    horizontalPaddingOverride = 14.dp,
                    labelStyle = MaterialTheme.typography.labelSmall,
                    focusRequester = focus.rows.first(),
                    upFocusRequester = focus.restrict,
                    downFocusRequester = focus.submit,
                    modifier = Modifier.onFocusChanged { state ->
                        if (state.isFocused) {
                            retryHandoff = true
                        } else if (currentLibraries is AssignableLibraries.Failed) {
                            retryHandoff = false
                        }
                    },
                    onClick = { if (!libraries.retrying) callbacks.onRetryLibraries() },
                )
            }

            is AssignableLibraries.Loaded -> {
                val shown = libraries.shownOnTv()
                if (shown.isEmpty()) {
                    TvPanelHint("No libraries are available to assign.", Modifier.padding(top = 14.dp))
                } else {
                    Column(
                        modifier = Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        shown.forEachIndexed { index, library ->
                            val allowed = library.id in access.allowedLibraryIds
                            TvLibraryCheckRow(
                                library = library,
                                allowed = allowed,
                                onToggle = { callbacks.onLibraryToggled(library.id, !allowed) },
                                modifier = Modifier
                                    .focusRequester(focus.rows[index])
                                    .focusProperties {
                                        up = if (index == 0) focus.restrict else focus.rows[index - 1]
                                        down = focus.rows.getOrNull(index + 1) ?: focus.submit
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The ratings as one connected scale, ending in Any (no ceiling). The ceiling
 * is solid, the ratings under it are tinted to show they're allowed too, and
 * the ones above stay empty.
 */
@Composable
private fun TvRatingLadder(
    access: ProfileAccessLimits,
    focus: TvProfileAccessFocus,
    onSelected: (String?) -> Unit,
) {
    val steps: List<String?> = contentRatingOptions(TV_CONTENT_RATINGS, access.maxContentRating) + null
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        steps.forEachIndexed { index, rating ->
            // Entering the row lands on the selected step.
            val isCeiling = rating == access.maxContentRating
            TvSegment(
                label = rating ?: ANY_RATING_LABEL,
                emphasis = when {
                    isCeiling -> SegmentEmphasis.Selected
                    rating != null && ratingIsUnderCeiling(rating, access.maxContentRating, TV_CONTENT_RATINGS) ->
                        SegmentEmphasis.Included
                    else -> SegmentEmphasis.None
                },
                shape = ladderShape(index, steps.lastIndex),
                onClick = { onSelected(rating) },
                modifier = Modifier
                    .weight(1f)
                    .then(if (isCeiling) Modifier.focusRequester(focus.rating) else Modifier)
                    .focusProperties {
                        up = focus.child
                        down = focus.restrict
                    },
            )
        }
    }
}

/** Every library, or only the ones picked below: the allowlist on/off. */
@Composable
private fun TvLibraryModeSwitch(
    restricted: Boolean,
    focus: TvProfileAccessFocus,
    onRestrictedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(false to "All libraries", true to "Choose libraries").forEachIndexed { index, (option, label) ->
            val selected = option == restricted
            TvSegment(
                label = label,
                emphasis = if (selected) SegmentEmphasis.Selected else SegmentEmphasis.None,
                shape = ladderShape(index, 1),
                onClick = { onRestrictedChange(option) },
                modifier = Modifier
                    .weight(1f)
                    .then(if (selected) Modifier.focusRequester(focus.restrict) else Modifier)
                    .focusProperties {
                        up = focus.rating
                        down = focus.rows.firstOrNull() ?: focus.submit
                    },
            )
        }
    }
}

private enum class SegmentEmphasis { Selected, Included, None }

/** Rounded outer ends, tight inner joins, so the steps read as one scale. */
private fun ladderShape(index: Int, lastIndex: Int): Shape {
    val outer = 14.dp
    val inner = 5.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        bottomStart = if (index == 0) outer else inner,
        topEnd = if (index == lastIndex) outer else inner,
        bottomEnd = if (index == lastIndex) outer else inner,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSegment(
    label: String,
    emphasis: SegmentEmphasis,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (container, content) = when (emphasis) {
        SegmentEmphasis.Selected -> Color.White to Color.Black
        SegmentEmphasis.Included -> Color.White.copy(alpha = 0.18f) to Color.White
        SegmentEmphasis.None -> Color.White.copy(alpha = 0.03f) to Color.White.copy(alpha = 0.62f)
    }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = if (emphasis == SegmentEmphasis.Selected) Color.White else Color.White.copy(alpha = 0.26f),
            focusedContentColor = if (emphasis == SegmentEmphasis.Selected) Color.Black else Color.White,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    if (emphasis == SegmentEmphasis.None) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                ),
                shape = shape,
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)),
                shape = shape,
            ),
        ),
        // A selected step is already white, so focus also lifts it with a glow.
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.45f), elevation = 14.dp),
        ),
        modifier = modifier.height(48.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = if (emphasis == SegmentEmphasis.Selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLibraryCheckRow(
    library: UserLibrary,
    allowed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val tint = if (allowed || isFocused) Color.White else Color.White.copy(alpha = 0.62f)
    Surface(
        onClick = onToggle,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.10f),
            focusedContentColor = Color.White,
            pressedContainerColor = Color.White.copy(alpha = 0.14f),
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, Color.Transparent), shape = shape),
            focusedBorder = Border(border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)), shape = shape),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = TvLibraryTabType.entries.firstOrNull { it.matches(library) }?.icon
                    ?: Icons.Filled.VideoLibrary,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = library.name,
                fontSize = 20.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (allowed) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (allowed) "Allowed" else "Not allowed",
                tint = if (allowed) Color.White else Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun TvPanelLabel(text: String) {
    Text(
        text = text,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.88f),
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun TvPanelHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        color = Color.White.copy(alpha = 0.55f),
        modifier = modifier,
    )
}
