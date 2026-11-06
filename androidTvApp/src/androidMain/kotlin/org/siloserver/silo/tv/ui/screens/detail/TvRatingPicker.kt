package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.ClearRatingColor
import org.siloserver.silo.common.ui.UserStarColor
import org.siloserver.silo.domain.UserStarRating
import org.siloserver.silo.tv.ui.components.tvDialogSurface
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved

/**
 * Five stars in a row. Left and Right move through them and fill the preview,
 * OK saves, Back closes without saving, and Down reaches Clear Rating on a
 * rated title. Focus starts on the saved rating, and Up from Clear Rating
 * returns to it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRatingPicker(
    currentRating: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val savedStarFocus = remember { FocusRequester() }
    val savedStar = currentRating ?: UserStarRating.choices.first
    var focusedStar by remember { mutableStateOf<Int?>(null) }
    val preview = focusedStar ?: currentRating ?: 0

    // The popup hands focus to its first star as it opens, so ask for the saved
    // star until it holds focus. OK on the wrong star would overwrite the rating.
    LaunchedEffect(Unit) {
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = savedStarFocus::requestFocus,
            isFocused = { focusedStar == savedStar },
        )
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .tvDialogSurface(RoundedCornerShape(20.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UserStarRating.choices.forEach { star ->
                        TvRatingStar(
                            star = star,
                            filled = star <= preview,
                            selected = star == currentRating,
                            onFocused = { focusedStar = star },
                            onClick = { onPick(star) },
                            modifier = if (star == savedStar) Modifier.focusRequester(savedStarFocus) else Modifier,
                        )
                    }
                }
                if (currentRating != null) {
                    TvClearRatingRow(
                        onFocused = { focusedStar = null },
                        onClick = { onPick(null) },
                        modifier = Modifier.focusProperties { up = savedStarFocus },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvRatingStar(
    star: Int,
    filled: Boolean,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resting = if (filled) UserStarColor else Color.White.copy(alpha = 0.9f)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = resting,
            // Yellow is hard to read on the white focus tile.
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { this.selected = selected },
    ) {
        Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = UserStarRating.title(star),
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvClearRatingRow(
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = ClearRatingColor,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFFB3261E),
            pressedContainerColor = Color.White,
            pressedContentColor = Color(0xFFB3261E),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = UserStarRating.CLEAR_TITLE,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
