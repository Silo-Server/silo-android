package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.ui.components.isImageAvatar
import org.siloserver.silo.common.ui.components.profileAvatarDisplayText
import org.siloserver.silo.common.ui.components.rememberProfileServerUrl
import org.siloserver.silo.common.ui.components.resolveAvatarUrl
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.SiloOnSurface

private const val PIN_LENGTH = 4

/** Compact, remote-first PIN keypad for protected TV profiles. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPinEntryDialog(
    profileName: String,
    profileAvatar: String? = null,
    onPinEntered: (String) -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
    isVerifying: Boolean = false,
) {
    var pin by remember { mutableStateOf("") }
    val latestError = remember(errorMessage) { errorMessage }
    LaunchedEffect(isVerifying, latestError) {
        if (!isVerifying && latestError != null && pin.length == PIN_LENGTH) {
            pin = ""
        }
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            val panelShape = RoundedCornerShape(17.dp)
            Column(
                modifier = Modifier
                    .width(310.dp)
                    .shadow(
                        elevation = 18.dp,
                        shape = panelShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.45f),
                        spotColor = Color.Black.copy(alpha = 0.45f),
                    )
                    .clip(panelShape)
                    .background(Color(0xFF15171C))
                    .border(0.5.dp, Color.White.copy(alpha = 0.16f), panelShape)
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProfilePinAvatar(profileName = profileName, profileAvatar = profileAvatar)
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = "Enter PIN for $profileName",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                Spacer(modifier = Modifier.height(14.dp))
                PinDots(pinLength = pin.length, error = latestError != null)

                if (latestError != null || isVerifying) {
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        text = latestError ?: "Verifying...",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                        color = if (latestError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                PinKeypad(
                    enabled = !isVerifying,
                    onDigitPressed = { digit ->
                        if (pin.length < PIN_LENGTH && !isVerifying) {
                            val next = pin + digit
                            pin = next
                            if (next.length == PIN_LENGTH) onPinEntered(next)
                        }
                    },
                    onBackspacePressed = { if (pin.isNotEmpty() && !isVerifying) pin = pin.dropLast(1) },
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    onClick = onDismiss,
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        contentColor = SiloOnSurface,
                        focusedContainerColor = FocusedContainer,
                        focusedContentColor = FocusedContent,
                        pressedContainerColor = FocusedContainer,
                        pressedContentColor = FocusedContent,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.045f),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.90f)),
                            shape = RoundedCornerShape(50),
                        ),
                    ),
                ) {
                    Text(
                        text = "Cancel",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfilePinAvatar(profileName: String, profileAvatar: String?) {
    val serverUrl = rememberProfileServerUrl()
    val avatarUrl = profileAvatar
        ?.takeIf(::isImageAvatar)
        ?.let { resolveAvatarUrl(serverUrl, it) }
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUrl != null) {
            ThumbhashImage(
                url = avatarUrl,
                thumbhash = null,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = profileAvatarDisplayText(profileAvatar, profileName),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 21.sp),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun PinDots(pinLength: Int, error: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(PIN_LENGTH) { index ->
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            error -> MaterialTheme.colorScheme.error
                            index < pinLength -> MaterialTheme.colorScheme.primary
                            else -> Color.Black.copy(alpha = 0.45f)
                        },
                    ),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PinKeypad(
    enabled: Boolean,
    onDigitPressed: (Char) -> Unit,
    onBackspacePressed: () -> Unit,
) {
    val fiveFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { fiveFocusRequester.requestFocus() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    PinKey(
                        label = digit.toString(),
                        modifier = if (digit == '5') Modifier.focusRequester(fiveFocusRequester) else Modifier,
                        onClick = { if (enabled) onDigitPressed(digit) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(modifier = Modifier.size(48.dp))
            PinKey(label = "0", onClick = { if (enabled) onDigitPressed('0') })
            PinKey(label = null, icon = Icons.AutoMirrored.Filled.Backspace, onClick = { if (enabled) onBackspacePressed() })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PinKey(
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val keyShape = RoundedCornerShape(9.dp)
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = keyShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = SiloOnSurface,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.18f)),
                shape = keyShape,
            ),
            focusedBorder = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = keyShape,
            ),
        ),
        modifier = modifier.size(48.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 32.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = if (isFocused) FocusedContent else SiloOnSurface,
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = "Backspace",
                    tint = if (isFocused) FocusedContent else SiloOnSurface,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}
