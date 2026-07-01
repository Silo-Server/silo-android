package com.continuum.app.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private const val PIN_LENGTH = 4

/**
 * Modal 4-digit PIN entry keypad for PIN-protected profiles. Uses a 3x4 grid
 * of focusable [Card] keys plus a backspace key. Auto-submits once all 4
 * digits are entered — TVs don't need a separate submit button, and the
 * auto-submit UX reduces D-pad trips by one.
 *
 * Error state lights up the 4 dots red; the caller sets [errorMessage] after
 * a failed `verifyPin` and the dialog clears the entry on the next key press
 * so the user can retry.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPinEntryDialog(
    profileName: String,
    onPinEntered: (String) -> Unit,
    onDismiss: () -> Unit,
    errorMessage: String? = null,
    isVerifying: Boolean = false,
) {
    var pin by remember { mutableStateOf("") }

    // Reset pin automatically when the caller shows a fresh error.
    val latestError = remember(errorMessage) { errorMessage }
    if (latestError != null && pin.length == PIN_LENGTH) {
        pin = ""
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                onClick = {},
                shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
                modifier = Modifier.padding(24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 28.dp, vertical = 24.dp)
                        .width(260.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Enter PIN",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 22.sp),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = profileName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    PinDots(
                        pinLength = pin.length,
                        error = latestError != null,
                    )

                    if (latestError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = latestError,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (isVerifying) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Verifying...",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    PinKeypad(
                        onDigitPressed = { digit ->
                            if (pin.length < PIN_LENGTH && !isVerifying) {
                                val next = pin + digit
                                pin = next
                                if (next.length == PIN_LENGTH) onPinEntered(next)
                            }
                        },
                        onBackspacePressed = {
                            if (pin.isNotEmpty()) pin = pin.dropLast(1)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PinDots(pinLength: Int, error: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(PIN_LENGTH) { i ->
            val filled = i < pinLength
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            error -> MaterialTheme.colorScheme.error
                            filled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PinKeypad(
    onDigitPressed: (Char) -> Unit,
    onBackspacePressed: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Rows 1-9.
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { d ->
                    PinKey(label = d.toString(), onClick = { onDigitPressed(d) })
                }
            }
        }
        // Bottom row: 0 + backspace (blank slot on the left keeps the grid aligned).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(modifier = Modifier.size(48.dp))
            PinKey(label = "0", onClick = { onDigitPressed('0') })
            PinKey(
                label = null,
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
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
        modifier = Modifier.size(48.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 25.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = "Backspace",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// Suppress unused parameter warning.
@Suppress("unused")
private val _unused: PaddingValues? = null
