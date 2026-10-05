package org.siloserver.silo.android.ui.screens.profiles

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.marquee.MarqueeIconButton
import org.siloserver.silo.common.ui.marquee.marqueeShake
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import org.siloserver.silo.common.ui.marquee.MarqueeProfileAvatar
import org.siloserver.silo.common.ui.marquee.MarqueeScrim
import org.siloserver.silo.common.ui.marquee.MarqueeScrimStyle
import org.siloserver.silo.model.profile.Profile

private const val PIN_LENGTH = 4

/**
 * Full-screen PIN prompt over the brand light: the person's avatar, four
 * dots, and a keypad laid out like the system passcode screen. A wrong PIN
 * keeps the prompt open, turns the dots red, shakes them and clears for
 * another try (silo-apple `PINEntryView`).
 *
 * Back stays live during verification so a slow round trip can be left; the
 * view model drops an answer for a prompt that's gone.
 *
 * @param errorCount bumped on each rejection so the dots shake and the entry clears.
 */
@Composable
fun PINEntryOverlay(
    profile: Profile,
    isVerifying: Boolean,
    error: String?,
    errorCount: Int,
    onPinComplete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Deliberately NOT rememberSaveable: saved-instance state is serialized by
    // the OS across configuration change and process death, which would put the
    // raw PIN in system-managed storage well beyond the request that needs it.
    // Losing four digits on rotation is the correct trade.
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(errorCount) { if (errorCount > 0) pin = "" }
    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            // Swallow taps so the picker underneath can't be reached.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        MarqueeScrim(MarqueeScrimStyle.Ambient)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            // A short screen can't fit the full layout (about 690dp); the
            // compact one keeps the keypad whole, and scrolling is the last resort.
            val compact = maxHeight < 690.dp
            val scrolls = maxHeight < 560.dp
            Column(
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxSize()
                    .then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp)) {
                    MarqueeIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onDismiss)
                }
                Spacer(if (scrolls) Modifier.height(8.dp) else Modifier.weight(1f))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = "Enter PIN for ${profile.name}" },
                ) {
                    MarqueeProfileAvatar(profile, size = if (compact) 64.dp else 88.dp, showsBadges = false, modifier = Modifier.padding(bottom = 8.dp))
                    Text(
                        profile.name,
                        color = MarqueeColors.Ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                PinDots(
                    filled = pin.length,
                    error = error != null && pin.isEmpty(),
                    modifier = Modifier.padding(top = if (compact) 14.dp else 22.dp).marqueeShake(errorCount),
                )

                Box(Modifier.padding(top = if (compact) 10.dp else 14.dp).height(22.dp), contentAlignment = Alignment.Center) {
                    when {
                        error != null -> Text(error, color = MarqueeColors.Error, fontSize = 15.sp, textAlign = TextAlign.Center)
                        isVerifying -> CircularProgressIndicator(color = MarqueeColors.Ink, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else -> Text("Enter your PIN", color = MarqueeColors.InkSecondary, fontSize = 15.sp)
                    }
                }

                NumberPad(
                    enabled = !isVerifying,
                    rowSpacing = if (compact) 10.dp else 16.dp,
                    modifier = Modifier.padding(top = if (compact) 18.dp else 28.dp),
                    onDigit = { digit ->
                        if (pin.length < PIN_LENGTH && !isVerifying) {
                            pin += digit
                            if (pin.length == PIN_LENGTH) onPinComplete(pin)
                        }
                    },
                    onBackspace = { if (pin.isNotEmpty() && !isVerifying) pin = pin.dropLast(1) },
                )

                Spacer(if (scrolls) Modifier.height(8.dp) else Modifier.weight(1f))
                if (!compact) {
                    Text(
                        "Forgot your PIN? The account admin can reset it.",
                        color = MarqueeColors.InkTertiary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PinDots(filled: Int, error: Boolean, modifier: Modifier = Modifier) {
    val color = if (error) MarqueeColors.Error else MarqueeColors.Ink
    Row(
        modifier.semantics { contentDescription = "$filled of $PIN_LENGTH digits entered" },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(PIN_LENGTH) { index ->
            val isFilled = index < filled || error
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (isFilled) color else Color.Transparent)
                    .border(1.5.dp, color.copy(alpha = if (isFilled) 1f else 0.7f), CircleShape),
            )
        }
    }
}

private val Letters = mapOf(2 to "ABC", 3 to "DEF", 4 to "GHI", 5 to "JKL", 6 to "MNO", 7 to "PQRS", 8 to "TUV", 9 to "WXYZ")

@Composable
private fun NumberPad(
    enabled: Boolean,
    rowSpacing: Dp,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                for (column in 1..3) {
                    val digit = row * 3 + column
                    PadKey(enabled, "$digit", onClick = { onDigit("$digit") }) {
                        PadDigit("$digit", Letters[digit])
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            Spacer(Modifier.size(PadKeySize))
            PadKey(enabled, "0", onClick = { onDigit("0") }) { PadDigit("0", null) }
            PadKey(enabled, "Delete", onClick = onBackspace, filled = false) {
                Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = null, tint = MarqueeColors.Ink, modifier = Modifier.size(24.dp))
            }
        }
    }
}

private val PadKeySize = 76.dp

@Composable
private fun PadDigit(digit: String, letters: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(digit, color = MarqueeColors.Ink, fontSize = 34.sp, lineHeight = 36.sp)
        if (letters != null) {
            Text(
                letters,
                color = MarqueeColors.Ink.copy(alpha = 0.62f),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                lineHeight = 10.sp,
            )
        }
    }
}

@Composable
private fun PadKey(
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    filled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    Box(
        Modifier
            .size(PadKeySize)
            .clip(CircleShape)
            .then(
                if (filled) {
                    Modifier
                        .background(Color.White.copy(alpha = if (pressed) 0.3f else 0.13f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
