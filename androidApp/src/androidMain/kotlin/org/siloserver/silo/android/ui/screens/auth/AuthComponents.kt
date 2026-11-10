package org.siloserver.silo.android.ui.screens.auth

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.siloKeyboardActions
import org.siloserver.silo.android.R
import org.siloserver.silo.common.R as CommonR

/** Silo brand colors used across auth screens. */
object AuthColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF111214)
    val Primary = Color(0xFFF5F2EC)
    val PrimaryVariant = Color(0xFFE4DFD5)
    val OnBackground = Color(0xFFF5F2EC)
    val OnSurface = Color(0xFFF5F2EC)
    val OnSurfaceVariant = Color(0xFFAFA89F)
    val Error = Color(0xFFEF5350)
    val FieldBorder = Color(0xFF252629)
    val FieldBorderFocused = Color(0x80F5F2EC)
    val StageHighlight = Color(0x22FFFFFF)
}

@Composable
fun AuthStage(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF111214),
                        AuthColors.Background,
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AuthColors.StageHighlight,
                            Color.Transparent,
                        ),
                        radius = 900f,
                    ),
                ),
        )
        content()
    }
}

/**
 * App logo / wordmark displayed at the top of auth screens.
 */
@Composable
fun SiloLogo(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(id = R.drawable.silo_wordmark),
            contentDescription = "Silo", // i18n-ignore
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(CommonR.string.signIn_tagline),
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            color = AuthColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Styled text field used across all auth screens.
 *
 * @param value Current text value.
 * @param onValueChange Callback when text changes.
 * @param label Label displayed above and inside the field.
 * @param error Optional error message shown below the field.
 * @param keyboardType Keyboard type hint (default text).
 * @param imeAction IME action button (default Next).
 * @param onImeAction Callback invoked when the IME action fires.
 * @param singleLine Whether the field is single-line (default true).
 * @param modifier Modifier applied to the outer Column.
 */
@Composable
fun SiloTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    singleLine: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            isError = error != null,
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = siloKeyboardActions(onImeAction),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AuthColors.OnSurface,
                unfocusedTextColor = AuthColors.OnSurface,
                focusedBorderColor = AuthColors.FieldBorderFocused,
                unfocusedBorderColor = AuthColors.FieldBorder,
                errorBorderColor = AuthColors.Error,
                focusedLabelColor = AuthColors.Primary,
                unfocusedLabelColor = AuthColors.OnSurfaceVariant,
                errorLabelColor = AuthColors.Error,
                cursorColor = AuthColors.Primary,
                focusedContainerColor = AuthColors.Surface,
                unfocusedContainerColor = AuthColors.Surface,
                errorContainerColor = AuthColors.Surface,
                focusedPlaceholderColor = AuthColors.OnSurfaceVariant,
                unfocusedPlaceholderColor = AuthColors.OnSurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Text(
                text = error,
                color = AuthColors.Error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

/**
 * Primary action button with loading state.
 *
 * When [isLoading] is true the button shows a spinner and is disabled.
 *
 * @param text Button label.
 * @param onClick Callback on click.
 * @param modifier Modifier applied to the Button.
 * @param isLoading Whether to show the loading spinner.
 * @param enabled Whether the button is enabled (ignoring loading state).
 */
@Composable
fun SiloButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    enabled: Boolean = true,
) {
    val effectiveEnabled = enabled && !isLoading

    val containerColor by animateColorAsState(
        targetValue = if (effectiveEnabled) AuthColors.Primary else AuthColors.Primary.copy(alpha = 0.45f),
        label = "buttonColor",
    )

    Button(
        onClick = onClick,
        enabled = effectiveEnabled,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.Black,
            disabledContainerColor = containerColor,
            disabledContentColor = Color.Black.copy(alpha = 0.65f),
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Color.Black,
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Inline error banner displayed at the top of a form when an API call fails.
 */
@Composable
fun AuthErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AuthColors.Surface, RoundedCornerShape(18.dp))
            .padding(vertical = 12.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = AuthColors.Error,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
