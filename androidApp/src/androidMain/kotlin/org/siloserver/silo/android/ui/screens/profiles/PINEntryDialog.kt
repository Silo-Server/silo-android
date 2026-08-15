package org.siloserver.silo.android.ui.screens.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.siloserver.silo.android.ui.screens.auth.AuthColors
import org.siloserver.silo.common.ui.components.ProfileAvatarRef

private const val PIN_LENGTH = 4

/**
 * Modal dialog for entering a 4-digit profile PIN.
 *
 * @param profileName Name displayed in the title.
 * @param isLoading Whether the PIN is being verified.
 * @param error Error message to display (e.g. "Incorrect PIN").
 * @param onPinComplete Called with the full 4-digit PIN when the user finishes entering.
 * @param onDismiss Called when the user cancels.
 */
@Composable
fun PINEntryDialog(
    profileName: String,
    profileAvatar: ProfileAvatarRef,
    isLoading: Boolean,
    error: String?,
    onPinComplete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Deliberately NOT rememberSaveable: saved-instance state is serialized by
    // the OS across configuration change and process death, which would put the
    // raw PIN in system-managed storage well beyond the request that needs it.
    // Losing four digits on rotation is the correct trade.
    var pin by remember { mutableStateOf("") }

    // Clear pin on new error so the user can re-enter.
    LaunchedEffect(error) {
        if (error != null) {
            pin = ""
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        // iOS phone presents PIN entry as a sheet over the black background.
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = AuthColors.Background,
        ) {
            // VStack(spacing: 32) header / dots / pad / cancel.
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Header: avatar (64) + label, spacing 10.
                ProfileAvatar(
                    avatar = profileAvatar,
                    name = profileName,
                    size = 64.dp,
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Enter PIN for $profileName",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = AuthColors.OnSurface,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(32.dp))

                // PIN dots row (size 20, spacing 20).
                PinDots(filledCount = pin.length)

                if (error != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = error,
                        fontSize = 13.sp,
                        color = AuthColors.Error,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Number pad
                NumberPad(
                    enabled = !isLoading,
                    onDigit = { digit ->
                        if (pin.length < PIN_LENGTH) {
                            pin += digit
                            if (pin.length == PIN_LENGTH) {
                                onPinComplete(pin)
                            }
                        }
                    },
                    onBackspace = {
                        if (pin.isNotEmpty()) {
                            pin = pin.dropLast(1)
                        }
                    },
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Cancel stays live during verification (the user must be able
                // to back out of a slow round trip), and the ViewModel's
                // generation guard makes that abandon the in-flight answer
                // rather than commit it late.
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Cancel",
                        color = AuthColors.Primary,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

// iOS siloSurfaceVariant (#0E0F12) — used for empty PIN dots and pad keys.
private val SurfaceVariant = Color(0xFF0E0F12)

@Composable
private fun PinDots(filledCount: Int) {
    // iOS phone: dotSize 20, spacing 20; empty dots are a filled surfaceVariant.
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(PIN_LENGTH) { index ->
            val filled = index < filledCount
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (filled) AuthColors.Primary else SurfaceVariant),
            )
        }
    }
}

@Composable
private fun NumberPad(
    enabled: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "back"),
    )

    // iOS pad: 3-column grid, key size 64, spacing 16.
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (row in rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (key in row) {
                    when (key) {
                        "" -> {
                            // Empty slot keeps the "0" centered (iOS Color.clear).
                            Spacer(modifier = Modifier.size(64.dp))
                        }

                        "back" -> {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceVariant)
                                    .then(
                                        if (enabled) {
                                            Modifier.clickable(onClick = onBackspace)
                                        } else {
                                            Modifier
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Backspace",
                                    tint = if (enabled) AuthColors.OnSurface else AuthColors.OnSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        else -> {
                            NumberPadKey(
                                digit = key,
                                enabled = enabled,
                                onClick = { onDigit(key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberPadKey(
    digit: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(SurfaceVariant)
            .then(
                if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // iOS siloPIN: 32 bold monospaced.
        Text(
            text = digit,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (enabled) AuthColors.OnSurface else AuthColors.OnSurfaceVariant,
        )
    }
}
