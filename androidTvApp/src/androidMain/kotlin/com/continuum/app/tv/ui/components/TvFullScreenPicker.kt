package com.continuum.app.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.continuum.app.tv.ui.theme.DarkOnPrimary
import com.continuum.app.tv.ui.theme.FocusedContainer
import com.continuum.app.tv.ui.theme.FocusedContent
import com.continuum.app.tv.ui.theme.Spacing

data class TvFullScreenPickerOption(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvFullScreenPicker(
    title: String,
    options: List<TvFullScreenPickerOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val initialFocus = remember { FocusRequester() }
    val focusTargetId = options.firstOrNull { it.id == selectedId }?.id
        ?: options.firstOrNull()?.id

    LaunchedEffect(title, focusTargetId) {
        runCatching { initialFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.86f)),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 18.sp, lineHeight = 21.sp),
                    color = Color.White,
                    modifier = Modifier.padding(top = 64.dp, bottom = 16.dp),
                )

                LazyColumn(
                    modifier = Modifier.width(340.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(options, key = { it.id }) { option ->
                        TvFullScreenPickerRow(
                            option = option,
                            selected = option.id == selectedId,
                            onClick = { onSelect(option.id) },
                            modifier = if (option.id == focusTargetId) {
                                Modifier.focusRequester(initialFocus)
                            } else {
                                Modifier
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "Press Back to cancel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.padding(bottom = 48.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvFullScreenPickerRow(
    option: TvFullScreenPickerOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(7.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            option.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isFocused) DarkOnPrimary else Color.White,
                )
                Spacer(modifier = Modifier.width(20.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 17.sp, lineHeight = 20.sp),
                    color = if (isFocused) DarkOnPrimary else Color.White,
                )
                option.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                        color = if (isFocused) {
                            DarkOnPrimary.copy(alpha = 0.68f)
                        } else {
                            Color.White.copy(alpha = 0.6f)
                        },
                    )
                }
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = if (isFocused) DarkOnPrimary else Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
