package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated

/**
 * The hero's clamped overview and entry point to its full-description popup.
 *
 * The overview defaults to a 3-line clamp at tvOS÷2 sizing (26pt regular →
 * 13sp); constrained hero layouts can supply a smaller [collapsedMaxLines].
 * Pressing OK/Select opens a window-level, scrollable popup instead of
 * expanding the page and moving the detail controls beneath it.
 *
 * This is a **focusable leaf** — the hero's only text focus stop, reachable by
 * pressing Up from the action row, and actionable so it never feels "stuck".
 * It owns its own focus visuals: no chrome at rest, on focus a faint dark
 * `DarkSurfaceElevated@0.55` fill (`RoundedRectangle(8.dp)`, no border) so the
 * white text stays readable. The system halo
 * is suppressed (`indication = null`), matching the squared-control idiom.
 *
 * [previewText] can include compact context (for example the focused episode
 * name) while [overview] remains the clean full text displayed in the popup.
 */
@Composable
internal fun TvExpandableSynopsis(
    overview: String,
    tagline: String?,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = 3,
    dialogTitle: String? = null,
    previewText: String = overview,
) {
    require(collapsedMaxLines > 0) { "collapsedMaxLines must be positive" }

    var showFullSynopsis by remember(overview, dialogTitle) { mutableStateOf(false) }
    val synopsisFocus = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(4.dp)

    Column(
        modifier = modifier
            .widthIn(max = 600.dp)
            .then(
                if (isFocused) {
                    Modifier.background(
                        color = DarkSurfaceElevated.copy(alpha = 0.55f),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .focusRequester(synopsisFocus)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { showFullSynopsis = true }
            // Keep the synopsis text on the same leading edge as the title,
            // episode hierarchy, and metadata rows. The old 10dp horizontal
            // inset made the description visibly drift right.
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        // tvOS: 26pt regular → 13sp, +2 per design review (2026-07-11).
        Text(
            text = previewText,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            color = Color.White.copy(alpha = 0.82f),
            textAlign = TextAlign.Start,
            maxLines = collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (showFullSynopsis) {
        TvSynopsisDialog(
            title = dialogTitle,
            overview = overview,
            tagline = tagline,
            onDismiss = { showFullSynopsis = false },
        )
        DisposableEffect(Unit) {
            onDispose {
                synopsisFocus.claimFocusOrReport(
                    target = "detail_synopsis",
                    action = "popup_dismissed",
                )
            }
        }
    }
}

@Composable
private fun TvSynopsisDialog(
    title: String?,
    overview: String,
    tagline: String?,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var popupHasFocus by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scrollScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(50)
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = focus::requestFocus,
            isFocused = { popupHasFocus },
        )
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.66f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .fillMaxHeight(0.72f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                    .border(
                        0.6.dp,
                        Color.White.copy(alpha = 0.14f),
                        RoundedCornerShape(18.dp),
                    )
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type == KeyEventType.KeyUp &&
                                (event.key == Key.Back || event.key == Key.Escape ||
                                    event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                                onDismiss()
                                true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                                scrollScope.launch {
                                    scrollState.animateScrollTo(
                                        (scrollState.value + 180).coerceAtMost(scrollState.maxValue),
                                    )
                                }
                                true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                                scrollScope.launch {
                                    scrollState.animateScrollTo(
                                        (scrollState.value - 180).coerceAtLeast(0),
                                    )
                                }
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .focusRequester(focus)
                        .onFocusChanged { popupHasFocus = it.hasFocus }
                        .focusable()
                        .padding(horizontal = 32.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    title?.trim()?.takeIf { it.isNotEmpty() }?.let { heading ->
                        Text(
                            text = heading,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = 24.sp,
                                lineHeight = 28.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = Color.White,
                        )
                    }
                    tagline?.trim()?.takeIf { it.isNotEmpty() }?.let { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Normal,
                            fontSize = 16.sp,
                            lineHeight = 21.sp,
                            color = Color.White.copy(alpha = 0.82f),
                        )
                    }
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 17.sp,
                            lineHeight = 25.sp,
                        ),
                        color = Color.White.copy(alpha = 0.86f),
                    )
                }
            }
        }
    }
}
