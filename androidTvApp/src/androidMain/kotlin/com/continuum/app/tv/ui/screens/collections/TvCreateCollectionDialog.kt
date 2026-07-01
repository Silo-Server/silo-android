package com.continuum.app.tv.ui.screens.collections

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.continuum.app.tv.ui.components.TvAnsiKeyboard
import com.continuum.app.tv.ui.components.TvFilterChip
import com.continuum.app.tv.ui.components.applyTvAnsiKeyboardAction
import com.continuum.app.tv.ui.screens.auth.CredentialDisplayField
import kotlinx.coroutines.delay

/**
 * Dialog for creating a new user collection. Contains a Silo-owned text field
 * bound to a local name state, a Manual/Smart type selector (mirroring the
 * phone's CreateCollectionSheet), and a Create button. The parent owns the
 * loading / error / selected-type state — we just render and call back.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvCreateCollectionDialog(
    isCreating: Boolean,
    errorMessage: String?,
    selectedType: String,
    onTypeChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var isKeyboardVisible by remember { mutableStateOf(false) }
    val nameFocusRequester = remember { FocusRequester() }
    val keyboardFirstKeyFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isKeyboardVisible) {
        if (isKeyboardVisible) {
            delay(120)
            runCatching { keyboardFirstKeyFocusRequester.requestFocus() }
        }
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
            ) {
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "New Collection",
                        style = TvCreateCollectionTextStyles.Title,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    CredentialDisplayField(
                        value = name,
                        hint = "Name",
                        enabled = !isCreating,
                        isActive = isKeyboardVisible,
                        isPassword = false,
                        passwordVisible = true,
                        focusRequester = nameFocusRequester,
                        onFocused = {},
                        onOpenKeyboard = { isKeyboardVisible = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(41.dp),
                    )
                    Text(
                        text = "Type",
                        style = TvCreateCollectionTextStyles.FieldLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvFilterChip(
                            text = "Manual",
                            selected = selectedType == "manual",
                            onClick = { onTypeChanged("manual") },
                        )
                        TvFilterChip(
                            text = "Smart",
                            selected = selectedType == "smart",
                            onClick = { onTypeChanged("smart") },
                        )
                    }
                    Text(
                        text = if (selectedType == "smart") {
                            "Automatically populated by rules."
                        } else {
                            "Manually add and organize items."
                        },
                        style = TvCreateCollectionTextStyles.Error.copy(fontWeight = FontWeight.Normal),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            style = TvCreateCollectionTextStyles.Error,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(
                                horizontal = 16.dp,
                                vertical = 6.dp,
                            ),
                        ) {
                            Text(text = "Cancel", style = TvCreateCollectionTextStyles.Button)
                        }
                        Button(
                            onClick = { if (!isCreating && name.isNotBlank()) onCreate(name) },
                            enabled = !isCreating,
                            contentPadding = PaddingValues(
                                horizontal = 16.dp,
                                vertical = 6.dp,
                            ),
                        ) {
                            Text(
                                text = if (isCreating) "Creating..." else "Create",
                                style = TvCreateCollectionTextStyles.Button,
                            )
                        }
                    }
                }
            }

            if (isKeyboardVisible) {
                TvAnsiKeyboard(
                    primaryLabel = "Create",
                    primaryEnabled = !isCreating && name.isNotBlank(),
                    enabled = !isCreating,
                    firstKeyFocusRequester = keyboardFirstKeyFocusRequester,
                    onAction = { action ->
                        name = applyTvAnsiKeyboardAction(
                            value = name,
                            action = action,
                            maxLength = TV_COLLECTION_NAME_MAX_LENGTH,
                        )
                    },
                    onPrimary = { if (!isCreating && name.isNotBlank()) onCreate(name) },
                    onDismiss = {
                        isKeyboardVisible = false
                        runCatching { nameFocusRequester.requestFocus() }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 48.dp, end = 48.dp, bottom = 28.dp),
                )
            }
        }
    }
}

private object TvCreateCollectionTextStyles {
    val Title = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    )

    val FieldLabel = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    )

    val Field = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        color = Color.White,
    )

    val Error = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    )

    val Button = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    )
}

private const val TV_COLLECTION_NAME_MAX_LENGTH = 120
