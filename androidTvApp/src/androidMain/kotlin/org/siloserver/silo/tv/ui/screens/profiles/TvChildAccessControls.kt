package org.siloserver.silo.tv.ui.screens.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.profile.AssignableLibraries
import org.siloserver.silo.model.profile.NO_RATING_LIMIT_LABEL
import org.siloserver.silo.model.profile.ProfileAccessLimits
import org.siloserver.silo.model.profile.contentRatingOptions
import org.siloserver.silo.tv.ui.components.TvHeroActionPill
import org.siloserver.silo.tv.ui.components.TvPillVariant
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.util.visibleOnTv

/**
 * D-pad links through the child limits, which sit between the Child switch
 * and the submit pill. [rows] holds one requester per library row, or one for
 * Try Again after a failed load.
 */
internal class TvChildAccessFocus(
    val child: FocusRequester,
    val submit: FocusRequester,
    val rating: FocusRequester,
    val restrict: FocusRequester,
    val rows: List<FocusRequester>,
    private val isChild: Boolean,
) {
    val afterChild: FocusRequester get() = if (isChild) rating else submit
    val beforeSubmit: FocusRequester get() = if (isChild) rows.lastOrNull() ?: restrict else child
}

/** Libraries the TV offers: book libraries stay off TV (AGENTS.md). */
private fun AssignableLibraries.shownOnTv(): List<UserLibrary> =
    (this as? AssignableLibraries.Loaded)?.libraries?.visibleOnTv().orEmpty()

@Composable
internal fun rememberTvChildAccessFocus(
    access: ProfileAccessLimits,
    libraries: AssignableLibraries,
    childFocusRequester: FocusRequester,
    submitFocusRequester: FocusRequester,
): TvChildAccessFocus {
    val rating = remember { FocusRequester() }
    val restrict = remember { FocusRequester() }
    val rowCount = when {
        !access.libraryRestrictionsEnabled -> 0
        libraries is AssignableLibraries.Failed -> 1
        else -> libraries.shownOnTv().size
    }
    val rows = remember(rowCount) { List(rowCount) { FocusRequester() } }
    return TvChildAccessFocus(childFocusRequester, submitFocusRequester, rating, restrict, rows, access.isChild)
}

/**
 * A child profile's limits on TV: a row of rating chips and the library
 * allowlist, matching the phone and the iOS create form.
 */
@Composable
internal fun TvChildAccessControls(
    access: ProfileAccessLimits,
    libraries: AssignableLibraries,
    focus: TvChildAccessFocus,
    callbacks: TvProfileFormCallbacks,
) {
    val ratings = contentRatingOptions(TV_CONTENT_RATINGS, access.maxContentRating)
    // Entering the row lands on the stored rating, or the first chip when none is set.
    val entryRating = access.maxContentRating ?: ratings.first()
    TvProfileFormSection(
        title = "Maximum content rating",
        trailing = if (access.maxContentRating == null) {
            { TvAccessHint(NO_RATING_LIMIT_LABEL) }
        } else {
            null
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ratings.forEach { rating ->
                TvChoiceChip(
                    label = rating,
                    isSelected = rating == access.maxContentRating,
                    onSelect = { callbacks.onContentRatingSelected(rating) },
                    modifier = Modifier
                        .then(if (rating == entryRating) Modifier.focusRequester(focus.rating) else Modifier)
                        .focusProperties {
                            up = focus.child
                            down = focus.restrict
                        },
                )
            }
        }
    }

    TvSwitchRow(
        title = "Restrict Libraries",
        subtitle = "Show only the libraries you choose",
        isOn = access.libraryRestrictionsEnabled,
        onToggle = { callbacks.onLibraryRestrictionsToggled(!access.libraryRestrictionsEnabled) },
        modifier = Modifier
            .focusRequester(focus.restrict)
            .focusProperties {
                up = focus.rating
                down = focus.rows.firstOrNull() ?: focus.submit
            },
    )

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

    if (!access.libraryRestrictionsEnabled) return
    when (libraries) {
        AssignableLibraries.Loading -> TvAccessHint("Loading libraries…", Modifier.padding(horizontal = 16.dp))

        is AssignableLibraries.Failed -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) { TvAccessHint(libraries.text) }
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
                TvAccessHint("No libraries are available to assign.", Modifier.padding(horizontal = 16.dp))
            } else {
                TvProfileFormSection(title = "Allowed libraries") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        shown.forEachIndexed { index, library ->
                            val allowed = library.id in access.allowedLibraryIds
                            TvSwitchRow(
                                title = library.name,
                                isOn = allowed,
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

@Composable
private fun TvAccessHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        color = Color.White.copy(alpha = 0.55f),
        modifier = modifier,
    )
}
