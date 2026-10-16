package org.siloserver.silo.android.ui.screens.profiles

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.screens.auth.AuthColors
import org.siloserver.silo.android.ui.screens.libraries.libraryIcon
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.profile.ANY_RATING_LABEL
import org.siloserver.silo.model.profile.AssignableLibraries
import org.siloserver.silo.model.profile.ProfileAccessLimits
import org.siloserver.silo.model.profile.contentRatingOptions
import org.siloserver.silo.model.profile.ratingCeilingCaption
import org.siloserver.silo.model.profile.ratingIsUnderCeiling

private val PanelShape = RoundedCornerShape(16.dp)

/**
 * A profile's limits, in one panel under the Child switch: the rating ceiling
 * as a ladder, then which libraries the profile sees. Shown for every profile,
 * as in the web editor; Child only fills in kid-safe defaults.
 */
@Composable
internal fun ProfileAccessControls(
    access: ProfileAccessLimits,
    libraries: AssignableLibraries,
    onContentRatingSelected: (String?) -> Unit,
    onLibraryRestrictionsToggled: (Boolean) -> Unit,
    onLibraryToggled: (Int, Boolean) -> Unit,
    onRetryLibraries: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AuthColors.Surface, PanelShape)
            .border(1.dp, AuthColors.FieldBorder, PanelShape)
            .padding(16.dp)
            .animateContentSize(),
    ) {
        PanelLabel("Maximum rating")
        RatingLadder(
            ratings = contentRatingOptions(CONTENT_RATINGS, access.maxContentRating),
            ceiling = access.maxContentRating,
            onSelected = onContentRatingSelected,
        )
        Spacer(modifier = Modifier.height(10.dp))
        HintText(ratingCeilingCaption(access.maxContentRating))

        HorizontalDivider(
            color = AuthColors.FieldBorder,
            modifier = Modifier.padding(vertical = 16.dp),
        )

        PanelLabel("Libraries")
        LibraryModeSwitch(
            restricted = access.libraryRestrictionsEnabled,
            onRestrictedChange = onLibraryRestrictionsToggled,
        )

        if (access.libraryRestrictionsEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            when (libraries) {
                AssignableLibraries.Loading -> Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = AuthColors.Primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    HintText("Loading libraries…")
                }

                is AssignableLibraries.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) { HintText(libraries.text) }
                    TextButton(onClick = onRetryLibraries, enabled = !libraries.retrying) {
                        Text("Try Again", color = AuthColors.Primary)
                    }
                }

                is AssignableLibraries.Loaded -> if (libraries.libraries.isEmpty()) {
                    HintText("No libraries are available to assign.", Modifier.padding(vertical = 10.dp))
                } else {
                    Column {
                        libraries.libraries.forEach { library ->
                            LibraryCheckRow(
                                library = library,
                                allowed = library.id in access.allowedLibraryIds,
                                onAllowedChange = { onLibraryToggled(library.id, it) },
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
private fun RatingLadder(
    ratings: List<String>,
    ceiling: String?,
    onSelected: (String?) -> Unit,
) {
    val steps: List<String?> = ratings + null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        steps.forEachIndexed { index, rating ->
            val isCeiling = rating == ceiling
            val isAllowed = rating != null && ratingIsUnderCeiling(rating, ceiling, CONTENT_RATINGS)
            val shape = RoundedCornerShape(
                topStart = if (index == 0) 12.dp else 4.dp,
                bottomStart = if (index == 0) 12.dp else 4.dp,
                topEnd = if (index == steps.lastIndex) 12.dp else 4.dp,
                bottomEnd = if (index == steps.lastIndex) 12.dp else 4.dp,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(
                        when {
                            isCeiling -> AuthColors.Primary
                            isAllowed -> AuthColors.Primary.copy(alpha = 0.16f)
                            else -> AuthColors.Background
                        },
                    )
                    .border(1.dp, if (isCeiling || isAllowed) Color.Transparent else AuthColors.FieldBorder, shape)
                    .selectable(
                        selected = isCeiling,
                        role = Role.RadioButton,
                        onClick = { onSelected(rating) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = rating ?: ANY_RATING_LABEL,
                    fontSize = 12.sp,
                    fontWeight = if (isCeiling) FontWeight.SemiBold else FontWeight.Medium,
                    color = when {
                        isCeiling -> AuthColors.Background
                        isAllowed -> AuthColors.OnSurface
                        else -> AuthColors.OnSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** Every library, or only the ones picked below: the allowlist on/off. */
@Composable
private fun LibraryModeSwitch(
    restricted: Boolean,
    onRestrictedChange: (Boolean) -> Unit,
) {
    val trackShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(AuthColors.Background, trackShape)
            .border(1.dp, AuthColors.FieldBorder, trackShape)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        listOf(false to "All libraries", true to "Choose libraries").forEach { (option, label) ->
            val selected = option == restricted
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) AuthColors.Primary else Color.Transparent)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onRestrictedChange(option) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) AuthColors.Background else AuthColors.OnSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LibraryCheckRow(
    library: UserLibrary,
    allowed: Boolean,
    onAllowedChange: (Boolean) -> Unit,
) {
    val tint = if (allowed) AuthColors.OnSurface else AuthColors.OnSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .toggleable(value = allowed, role = Role.Checkbox, onValueChange = onAllowedChange)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = libraryIcon(library.type),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = library.name,
            fontSize = 14.sp,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (allowed) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (allowed) AuthColors.Primary else AuthColors.FieldBorderFocused,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = AuthColors.OnSurface,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = AuthColors.OnSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}
