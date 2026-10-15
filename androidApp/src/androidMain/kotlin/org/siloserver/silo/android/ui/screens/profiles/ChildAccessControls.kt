package org.siloserver.silo.android.ui.screens.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.screens.auth.AuthColors
import org.siloserver.silo.model.profile.AssignableLibraries
import org.siloserver.silo.model.profile.NO_RATING_LIMIT_LABEL
import org.siloserver.silo.model.profile.ProfileAccessLimits
import org.siloserver.silo.model.profile.contentRatingOptions

/**
 * A child profile's limits, shown under the Child switch: the rating ceiling
 * and the library allowlist, as on the iOS create form.
 */
@Composable
internal fun ChildAccessControls(
    access: ProfileAccessLimits,
    libraries: AssignableLibraries,
    onContentRatingSelected: (String) -> Unit,
    onLibraryRestrictionsToggled: (Boolean) -> Unit,
    onLibraryToggled: (Int, Boolean) -> Unit,
    onRetryLibraries: () -> Unit,
) {
    Column {
        DropdownField(
            label = "Max Content Rating",
            selected = access.maxContentRating ?: NO_RATING_LIMIT_LABEL,
            options = contentRatingOptions(CONTENT_RATINGS, access.maxContentRating),
            onSelected = onContentRatingSelected,
        )

        Spacer(modifier = Modifier.height(16.dp))

        SwitchRow(
            label = "Restrict Libraries",
            checked = access.libraryRestrictionsEnabled,
            onCheckedChange = onLibraryRestrictionsToggled,
            subtitle = "Show only the libraries you choose",
        )

        if (access.libraryRestrictionsEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            when (libraries) {
                AssignableLibraries.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = AuthColors.Primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    HintText("Loading libraries…")
                }

                is AssignableLibraries.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) { HintText(libraries.text) }
                    TextButton(onClick = onRetryLibraries, enabled = !libraries.retrying) {
                        Text("Try Again", color = AuthColors.Primary)
                    }
                }

                is AssignableLibraries.Loaded -> if (libraries.libraries.isEmpty()) {
                    HintText("No libraries are available to assign.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionHeader("Allowed Libraries")
                        libraries.libraries.forEach { library ->
                            SwitchRow(
                                label = library.name,
                                checked = library.id in access.allowedLibraryIds,
                                onCheckedChange = { onLibraryToggled(library.id, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = AuthColors.OnSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}
