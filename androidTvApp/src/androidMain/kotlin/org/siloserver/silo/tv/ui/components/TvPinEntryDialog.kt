package org.siloserver.silo.tv.ui.components

import org.siloserver.silo.common.ui.marquee.MarqueeBackdrop
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeProfileAvatar
import org.siloserver.silo.common.ui.marquee.MarqueeScrim
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButton
import org.siloserver.silo.tv.ui.components.marquee.TvMarqueeButtonKind
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.tvControlSemantics
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.SiloOnSurface

private const val PIN_LENGTH = 4
private val PinKeySize = 50.dp

/**
 * Full-screen, remote-first PIN prompt over the brand light: the person's
 * avatar, four dots, and a round keypad (silo-apple `PINEntryView` on tvOS).
 * A wrong PIN turns the dots red and clears for another try.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPinEntryDialog(
    profile: Profile,
    onPinEntered: (String) -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
    isVerifying: Boolean = false,
    prompt: String = "Enter your PIN",
) {
    var pin by remember { mutableStateOf("") }
    val fiveFocusRequester = remember { FocusRequester() }
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
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            MarqueeBackdrop(mirrored = true)
            MarqueeScrim(MarqueeScrimStyle.Ambient)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Column(
                modifier = Modifier
                    // Retry-until-focused initial grab targeting the "5" key
                    // (issue #64's fix, now shared). Exits as soon as ANYTHING
                    // in the dialog holds focus, so a user who reaches Cancel
                    // before the first grab lands is never yanked back.
                    .then(rememberTvDialogInitialFocus(fiveFocusRequester)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MarqueeProfileAvatar(profile, size = 80.dp, baseSize = 110.dp, showsBadges = false)
                Text(
                    text = profile.name,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MarqueeColors.Ink,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(top = 11.dp)
                        .semantics { contentDescription = "Enter PIN for ${profile.name}" },
                )
                PinDots(pinLength = pin.length, error = latestError != null && pin.isEmpty(), modifier = Modifier.padding(top = 17.dp))
                Box(Modifier.padding(top = 9.dp).height(17.dp), contentAlignment = Alignment.Center) {
                    when {
                        latestError != null -> Text(latestError, fontSize = 14.sp, color = MarqueeColors.Error)
                        isVerifying -> Text("Checking…", fontSize = 14.sp, color = MarqueeColors.InkSecondary)
                        else -> Text(prompt, fontSize = 14.sp, color = MarqueeColors.InkSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(15.dp))
                PinKeypad(
                    fiveFocusRequester = fiveFocusRequester,
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
                // Not disabled while the PIN is checked: on TV a disabled
                // control loses focus. The view model drops a late answer.
                TvMarqueeButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    kind = TvMarqueeButtonKind.Plain,
                    compact = true,
                    modifier = Modifier.padding(top = 15.dp),
                )
            }
        }
    }
}

@Composable
private fun PinDots(pinLength: Int, error: Boolean, modifier: Modifier = Modifier) {
    val color = if (error) MarqueeColors.Error else MarqueeColors.Ink
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(PIN_LENGTH) { index ->
            val filled = error || index < pinLength
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(if (filled) color else Color.Transparent)
                    .border(1.5.dp, color.copy(alpha = if (filled) 1f else 0.7f), CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PinKeypad(
    fiveFocusRequester: FocusRequester,
    enabled: Boolean,
    onDigitPressed: (Char) -> Unit,
    onBackspacePressed: () -> Unit,
) {
    // Verification is in flight, not a structural dead end: the keys stay
    // focusable so the ring survives the round trip. Dropping the whole keypad
    // out of the focus graph would strand a rejected PIN with a dead D-pad —
    // the initial-focus policy is one-shot and never re-fires.
    val keyState = TvControlState.transient(enabled)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { digit ->
                    PinKey(
                        label = digit.toString(),
                        controlState = keyState,
                        modifier = if (digit == '5') Modifier.focusRequester(fiveFocusRequester) else Modifier,
                        onClick = { onDigitPressed(digit) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(modifier = Modifier.size(PinKeySize))
            PinKey(label = "0", controlState = keyState, onClick = { onDigitPressed('0') })
            PinKey(
                label = null,
                controlState = keyState,
                icon = Icons.AutoMirrored.Filled.Backspace,
                onClick = onBackspacePressed,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PinKey(
    label: String?,
    controlState: TvControlState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val keyShape = CircleShape
    Surface(
        onClick = { controlState.perform(onClick) },
        enabled = controlState.focusable,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = keyShape),
        // The keypad deliberately carries no dimmed treatment while verifying
        // (the panel shows its own progress), so the resting and disabled
        // slots are the same colours either way.
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.13f),
            contentColor = SiloOnSurface,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
            disabledContainerColor = Color.White.copy(alpha = 0.13f),
            disabledContentColor = SiloOnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                shape = keyShape,
            ),
            focusedBorder = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = keyShape,
            ),
        ),
        modifier = modifier
            .size(PinKeySize)
            .tvControlSemantics(controlState),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Normal,
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
