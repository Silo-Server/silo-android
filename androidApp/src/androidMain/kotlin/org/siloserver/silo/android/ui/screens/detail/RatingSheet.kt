package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.theme.DarkSurface
import org.siloserver.silo.common.ui.ClearRatingColor
import org.siloserver.silo.common.ui.UserStarColor
import org.siloserver.silo.domain.UserStarRating

/**
 * Five tappable stars and, on a rated title, a way to clear them. A tap saves
 * and closes; tapping the current rating just closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingSheet(
    currentRating: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = DarkSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserStarRating.choices.forEach { star ->
                    val filled = currentRating != null && star <= currentRating
                    IconButton(
                        onClick = { onPick(star) },
                        modifier = Modifier
                            .size(56.dp)
                            .semantics { selected = star == currentRating },
                    ) {
                        Icon(
                            imageVector = if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = UserStarRating.title(star),
                            tint = if (filled) UserStarColor else Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
            if (currentRating != null) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onPick(null) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = UserStarRating.CLEAR_TITLE,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = ClearRatingColor,
                    )
                }
            }
        }
    }
}
