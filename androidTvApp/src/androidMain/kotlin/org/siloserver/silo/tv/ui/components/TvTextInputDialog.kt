package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.siloserver.silo.common.R as CommonR

/**
 * Generic single-field text-input dialog for TV. Text entry intentionally uses
 * the platform IME so Fire TV / Google TV can offer their own remote app,
 * voice, and QR/phone input affordances.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvTextInputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onConfirm: (text: String) -> Unit,
    onDismiss: () -> Unit,
    initialValue: String = "",
    isBusy: Boolean = false,
    errorMessage: String? = null,
    allowBlank: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var text by remember { mutableStateOf(initialValue) }
    val fieldFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val canConfirm = !isBusy && (allowBlank || text.isNotBlank())
    val submit = {
        if (canConfirm) onConfirm(text)
    }

    LaunchedEffect(Unit) {
        delay(120)
        runCatching { fieldFocusRequester.requestFocus() }
        keyboardController?.show()
    }
    TvHideStockImeOnDispose()

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 19.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { updated ->
                            text = normalizeDialogText(updated, keyboardType)
                        },
                        label = { Text(label) },
                        singleLine = true,
                        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                        enabled = !isBusy,
                        visualTransformation = if (keyboardType.isPasswordKeyboardType()) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboardType,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .tvImeAwareFieldContext()
                            .focusRequester(fieldFocusRequester),
                        colors = tvOutlinedTextFieldColors(),
                    )
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(text = stringResource(CommonR.string.common_cancel), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = submit,
                            enabled = canConfirm,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = if (isBusy) "..." else confirmLabel,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun normalizeDialogText(value: String, keyboardType: KeyboardType): String {
    val trimmed = value.take(TV_DIALOG_TEXT_MAX_LENGTH)
    return if (keyboardType == KeyboardType.Number || keyboardType == KeyboardType.NumberPassword) {
        trimmed.filter { it.isDigit() }
    } else {
        trimmed
    }
}

private fun KeyboardType.isPasswordKeyboardType(): Boolean =
    this == KeyboardType.Password || this == KeyboardType.NumberPassword

private const val TV_DIALOG_TEXT_MAX_LENGTH = 200
