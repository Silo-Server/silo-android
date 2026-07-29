package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.R
import org.siloserver.silo.android.ui.screens.profiles.ProfileAvatar
import org.siloserver.silo.model.profile.Profile

// Height of the floating top bar's body, excluding the status-bar inset
// (6dp top + 42dp action row + 28dp bottom). Callers add WindowInsets.statusBars
// so tab content clears the bar regardless of status-bar height.
val MainAppHeaderBodyHeight = 76.dp

@Composable
fun MainAppTopBar(
    activeProfile: Profile?,
    isProfileLoading: Boolean,
    onSearchClick: () -> Unit,
    onRequestsClick: (() -> Unit)? = null,
    onWatchTogetherClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    onSignOutClick: () -> Unit,
    leadingContent: @Composable () -> Unit = {
        SiloWordmark()
    },
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0.42f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0f),
                    ),
                ),
            )
            .padding(
                top = statusBarPadding.calculateTopPadding() + 6.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = 28.dp,
            ),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                contentAlignment = Alignment.CenterStart,
            ) {
                leadingContent()
            }

            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderActionButton(
                    onClick = onSearchClick,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Search",
                    )
                }

                Box {
                    HeaderActionButton(
                        onClick = { menuExpanded = true },
                    ) {
                        if (activeProfile != null) {
                            ProfileAvatar(
                                avatar = activeProfile.avatar,
                                name = activeProfile.name,
                                size = 34.dp,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Outlined.Person,
                                    contentDescription = "Account and menu",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (onRequestsClick != null) {
                            DropdownMenuItem(
                                text = { Text("Requests") },
                                onClick = {
                                    menuExpanded = false
                                    onRequestsClick()
                                },
                            )
                        }
                        if (onWatchTogetherClick != null) {
                            DropdownMenuItem(
                                text = { Text("Watch Together") },
                                onClick = {
                                    menuExpanded = false
                                    onWatchTogetherClick()
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                menuExpanded = false
                                onSettingsClick()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Switch Profile") },
                            onClick = {
                                menuExpanded = false
                                onSwitchProfileClick()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Switch Server") },
                            onClick = {
                                menuExpanded = false
                                onSwitchServerClick()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Sign Out",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onSignOutClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderActionButton(
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.06f),
        ),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
fun SiloWordmark(
    modifier: Modifier = Modifier,
    width: Dp = 72.dp,
) {
    androidx.compose.foundation.Image(
        painter = painterResource(id = R.drawable.silo_wordmark),
        contentDescription = "Silo",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .width(width)
            .height(width * 0.52f),
    )
}
