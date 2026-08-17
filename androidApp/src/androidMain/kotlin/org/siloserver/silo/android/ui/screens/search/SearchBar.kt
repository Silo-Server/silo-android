package org.siloserver.silo.android.ui.screens.search

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/** Builds the free-form speech recogniser intent used by voice search. */
private fun voiceSearchIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Search Silo")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

/**
 * Remembers whether this device can service a speech-recognition intent.
 *
 * Hoisted out of [SearchBar] because the empty state also words itself
 * differently when there is no microphone affordance to point at. The state is
 * mutable so a launch that still fails with [ActivityNotFoundException] can
 * retire the affordance for the rest of the session.
 */
@Composable
fun rememberVoiceSearchAvailability(): MutableState<Boolean> {
    val context = LocalContext.current
    return remember(context) {
        val available = SpeechRecognizer.isRecognitionAvailable(context) ||
            context.packageManager.resolveActivity(voiceSearchIntent(), 0) != null
        mutableStateOf(available)
    }
}

/**
 * Search text field with an icon, placeholder, voice input, and clear button.
 *
 * The trailing area is always `[mic][clear-if-non-empty]` so voice search stays
 * one tap away regardless of what has been typed.
 *
 * @param query The current search query text.
 * @param onQueryChanged Callback as the user types.
 * @param onClear Callback when the clear button is tapped.
 * @param onVoiceQuery Callback with a recognised spoken query, already trimmed
 *   and guaranteed non-blank. Implementations should search immediately.
 * @param voiceAvailable Whether the microphone affordance should be shown.
 * @param onVoiceUnavailable Called when launching the recogniser failed, so the
 *   caller can retire the affordance.
 * @param autoFocus Whether to auto-focus the text field on first composition.
 * @param modifier Compose modifier.
 */
@Composable
fun SearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit,
    onVoiceQuery: (String) -> Unit,
    voiceAvailable: Boolean,
    onVoiceUnavailable: () -> Unit,
    autoFocus: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    if (autoFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (spoken.isNotBlank()) {
            // Deliberately no focus request: the results should be visible
            // straight away rather than hidden behind the keyboard.
            keyboardController?.hide()
            onVoiceQuery(spoken)
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusRequester(focusRequester),
        placeholder = {
            Text(
                text = "Search movies, shows, and more",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (voiceAvailable) {
                    IconButton(
                        onClick = {
                            try {
                                voiceLauncher.launch(voiceSearchIntent())
                            } catch (_: ActivityNotFoundException) {
                                onVoiceUnavailable()
                                Toast.makeText(
                                    context,
                                    "Voice search isn't available on this device",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Search by voice",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(
            onSearch = { keyboardController?.hide() },
        ),
    )
}
