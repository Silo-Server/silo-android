package org.siloserver.silo.tv.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * Right-edge vertical A–Z jump rail for the library Browse grid, following
 * tvOS `TVAlphabetRail.swift`: holding the remote's d-pad through tens of
 * thousands of titles is not a real navigation strategy, so the rail lets the
 * user jump straight to a title prefix.
 *
 * Entries: `All`, `#`, then A–Z. `#` jumps to titles whose sort-title begins
 * with a non-alpha character. While no letter holds focus the rail collapses
 * to a slim, condensed cluster of dots at the screen edge (one dot per few
 * letters, so the expansion into the full letter column reads as a real
 * transformation); focus entering it expands the dots into letter chips.
 * Letters are monospaced so every advance width is equal and the column reads
 * as an even rail; the selected / focused entry inverts to a white rounded
 * chip, and `All` renders as a standard app pill.
 *
 * Every entry is ONE persistent focusable Surface whose visuals swap between
 * dot and chip — collapsed/expanded must NOT be separate composables, or the
 * focused dot node would leave composition the instant expansion starts and
 * focus would snap away to the nearest surviving scope (the top nav).
 *
 * On select it calls [onSelect] with the chosen prefix (null for "All", "#"
 * for the symbol bucket, else the letter), which the screen routes to the
 * browse name-prefix filter / grid jump.
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvAlphabetRail(
    selected: String?,
    onSelect: (String?) -> Unit,
    /**
     * Shell hook overriding D-pad Up while the rail holds focus. The shell's
     * content box consumes EVERY Up key in the preview phase (ancestors run
     * before descendants there), so a key handler on the rail itself never
     * sees the event — the only way to hold on "All" instead of escaping to
     * the top menu is to register this fallback: on a letter it moves focus
     * up the rail; on "All" it swallows the move.
     */
    onUpFallbackChanged: ((((Boolean) -> Boolean)?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val letters = remember {
        buildList {
            add("All")
            add("#")
            ('A'..'Z').forEach { add(it.toString()) }
        }
    }

    var expanded by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState(
        targetValue = if (expanded) 96.dp else 18.dp,
        animationSpec = tween(durationMillis = 180),
        label = "alphabetRailWidth",
    )
    val allFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var allEntryFocused by remember { mutableStateOf(false) }
    var railHadFocus by remember { mutableStateOf(false) }

    // While the rail holds focus, D-pad Up is handled here: step up the rail,
    // but HOLD on "All" (never escape to the nav bar). The shell's fallback
    // slot reconciles BY IDENTITY (re-announcing your own lambda relinquishes;
    // null is ignored — see TvMainShell.onContentUpFallback), so the rail must
    // keep ONE stable lambda and re-announce it on focus loss/disposal. The
    // old fresh-lambda + null pattern left a stale always-true fallback
    // registered forever, killing Up-to-menu-bar for the whole library.
    val railUpFallback = remember {
        { _: Boolean ->
            if (!allEntryFocused) {
                focusManager.moveFocus(FocusDirection.Up)
            }
            true
        }
    }
    DisposableEffect(onUpFallbackChanged) {
        onDispose {
            // Relinquish only if the rail still owns the slot (it re-announces
            // on focus loss already); announcing while NOT the owner would
            // REGISTER the lambda instead.
            if (railHadFocus) onUpFallbackChanged?.invoke(railUpFallback)
        }
    }

    LazyColumn(
        modifier = modifier
            .width(railWidth)
            .fillMaxHeight()
            // The margins live OUTSIDE the scroll viewport so scrolled letters
            // clip at the "All" line instead of drawing up over the nav bar
            // (and off the bottom edge). Expanded gets extra clearance.
            .padding(
                top = if (expanded) 110.dp else 72.dp,
                bottom = if (expanded) 80.dp else 48.dp,
            )
            // Entering the rail always lands on "All" at the top, not
            // whatever letter happens to sit beside the launching card.
            .focusProperties { onEnter = { allFocusRequester.requestFocus() } }
            // Expand while focus is anywhere in the rail; collapse to the edge
            // peek once focus leaves. hasFocus stays true moving between letters,
            // so there's no collapse/expand flicker during traversal.
            .onFocusChanged { state ->
                expanded = state.hasFocus
                // Same stable lambda both ways: registering takes the slot,
                // re-announcing it on focus loss relinquishes it (identity
                // protocol — null would be ignored by the shell). Announce on
                // TRANSITIONS only: onFocusChanged can re-fire while hasFocus
                // stays true, and a same-owner re-announce means relinquish.
                if (state.hasFocus != railHadFocus) {
                    railHadFocus = state.hasFocus
                    onUpFallbackChanged?.invoke(railUpFallback)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        // Collapsed entries pack tight so the dot cluster stays small and the
        // expansion into full-size chips lands with some drama.
        verticalArrangement = Arrangement.spacedBy(if (expanded) 4.dp else 1.dp, Alignment.CenterVertically),
    ) {
        items(letters.size) { index ->
            val letter = letters[index]
            val isSelected = when (letter) {
                "All" -> selected == null
                "#" -> selected == "#"
                else -> selected == letter
            }
            LetterButton(
                letter = letter,
                isSelected = isSelected,
                expanded = expanded,
                focusRequester = allFocusRequester.takeIf { letter == "All" },
                onFocused = if (letter == "All") {
                    { focused -> allEntryFocused = focused }
                } else {
                    null
                },
                // Every other entry draws a dot while collapsed (plus the
                // selected one, so the current position always shows). The
                // in-between entries stay as invisible focus targets.
                showCollapsedDot = index % 2 == 0,
                onClick = {
                    onSelect(
                        when (letter) {
                            "All" -> null
                            "#" -> "#"
                            else -> letter
                        },
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LetterButton(
    letter: String,
    isSelected: Boolean,
    expanded: Boolean,
    focusRequester: FocusRequester?,
    onFocused: ((Boolean) -> Unit)?,
    showCollapsedDot: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isAll = letter == "All"
    val chip = isFocused || isSelected

    val shape = when {
        !expanded -> CircleShape
        isAll -> RoundedCornerShape(999.dp)
        else -> RoundedCornerShape(8.dp)
    }
    val containerColor = when {
        !expanded -> Color.Transparent
        isAll && isFocused -> Color.White.copy(alpha = 0.94f)
        isAll && isSelected -> Color.White.copy(alpha = 0.22f)
        isAll -> Color.White.copy(alpha = 0.08f)
        chip -> Color.White
        else -> Color.Transparent
    }
    val contentColor = when {
        !expanded -> Color.Transparent
        isFocused -> Color.Black
        isAll && isSelected -> Color.White
        isSelected -> Color.Black
        isAll -> Color.White.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.55f)
    }

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = when {
                !expanded -> Color.Transparent
                isAll -> Color.White.copy(alpha = 0.94f)
                else -> Color.White
            },
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (expanded && isAll) 1.05f else 1f),
        modifier = when {
            !expanded -> Modifier.size(8.dp)
            isAll -> Modifier
            else -> Modifier.size(42.dp)
        }.then(
            if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
        ).then(
            if (onFocused != null) {
                Modifier.onFocusChanged { onFocused(it.isFocused) }
            } else {
                Modifier
            },
        ),
    ) {
        when {
            !expanded -> Box(modifier = Modifier.size(8.dp), contentAlignment = Alignment.Center) {
                if (showCollapsedDot || isSelected) {
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 6.dp else 4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (isSelected) 0.9f else 0.35f)),
                    )
                }
            }
            isAll -> Text(
                text = "All",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                ),
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
            else -> Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = letter,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}
