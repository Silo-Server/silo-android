package org.siloserver.silo.tv.ui.components

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

/**
 * Canonical control metrics for the TV auth/onboarding forms (server setup,
 * sign-in, sign-up, first-run setup). Every text field and primary action in
 * that flow sizes from here so the steps read as one surface.
 */
object TvAuthFormDefaults {
    val FieldHeight = 56.dp
    val PrimaryButtonHeight = 60.dp
}

@Composable
fun tvOutlinedTextFieldColors(
    focusedContainerColor: Color = Color.White.copy(alpha = 0.04f),
    unfocusedContainerColor: Color = Color.White.copy(alpha = 0.03f),
    focusedBorderColor: Color = Color.White.copy(alpha = 0.92f),
    unfocusedBorderColor: Color = Color.White.copy(alpha = 0.22f),
): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color.White.copy(alpha = 0.56f),
    errorTextColor = Color.White,
    focusedLabelColor = Color.White,
    unfocusedLabelColor = Color.White.copy(alpha = 0.78f),
    disabledLabelColor = Color.White.copy(alpha = 0.46f),
    errorLabelColor = MaterialTheme.colorScheme.error,
    focusedPlaceholderColor = Color.White.copy(alpha = 0.64f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.56f),
    disabledPlaceholderColor = Color.White.copy(alpha = 0.38f),
    errorPlaceholderColor = Color.White.copy(alpha = 0.64f),
    cursorColor = Color.White,
    errorCursorColor = MaterialTheme.colorScheme.error,
    focusedBorderColor = focusedBorderColor,
    unfocusedBorderColor = unfocusedBorderColor,
    disabledBorderColor = Color.White.copy(alpha = 0.16f),
    errorBorderColor = MaterialTheme.colorScheme.error,
    focusedLeadingIconColor = Color.White.copy(alpha = 0.76f),
    unfocusedLeadingIconColor = Color.White.copy(alpha = 0.64f),
    disabledLeadingIconColor = Color.White.copy(alpha = 0.38f),
    errorLeadingIconColor = Color.White.copy(alpha = 0.76f),
    focusedTrailingIconColor = Color.White.copy(alpha = 0.76f),
    unfocusedTrailingIconColor = Color.White.copy(alpha = 0.64f),
    disabledTrailingIconColor = Color.White.copy(alpha = 0.38f),
    errorTrailingIconColor = Color.White.copy(alpha = 0.76f),
    focusedContainerColor = focusedContainerColor,
    unfocusedContainerColor = unfocusedContainerColor,
    disabledContainerColor = unfocusedContainerColor.copy(alpha = 0.55f),
    errorContainerColor = focusedContainerColor,
)
