package org.siloserver.silo.tv.ui.screens.watchparty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.tvControlSemantics
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.watchtogether.WATCH_PARTY_CODE_ALPHABET
import org.siloserver.silo.watchtogether.WATCH_PARTY_CODE_LENGTH
import org.siloserver.silo.watchtogether.canDismissRoomEntry
import org.siloserver.silo.watchtogether.normalizeWatchPartyCode

/**
 * Pure D-pad join-code accumulator. Appends only characters from the room
 * code alphabet (uppercasing letters; 0, 1, I and O never appear in a code),
 * caps at [LENGTH], and supports backspace/clear. Kept free of Android types
 * so it is unit-testable without Robolectric.
 */
data class JoinCodeState(val code: String = "") {
    /** A complete, well-formed code, normalized the same way as a typed or pasted one. */
    val normalized: String? get() = normalizeWatchPartyCode(code)
    val isComplete: Boolean get() = normalized != null
    fun append(c: Char): JoinCodeState {
        if (code.length >= LENGTH) return this
        val up = c.uppercaseChar()
        return if (up in WATCH_PARTY_CODE_ALPHABET) copy(code = code + up) else this
    }
    fun backspace() = if (code.isEmpty()) this else copy(code = code.dropLast(1))
    fun clear() = JoinCodeState()
    companion object { const val LENGTH = WATCH_PARTY_CODE_LENGTH }
}

/** Keys laid out in the focusable code-alphabet grid, eight per row. */
private val JoinCodeKeys: List<Char> = WATCH_PARTY_CODE_ALPHABET.toList()
private const val KEYS_PER_ROW = 8

/**
 * D-pad Watch Party join-by-code dialog on the party's card: one slot per
 * code character, a focusable pill-key grid of the code alphabet (each key
 * appends into a [JoinCodeState]), Delete (backspace), and Join party, which
 * acts once the code is complete and nothing is in flight. [onJoin] receives
 * the normalized code.
 */
@Composable
fun TvJoinCodeDialog(
    isBusy: Boolean,
    error: String?,
    onJoin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var state by remember { mutableStateOf(JoinCodeState()) }
    val firstKeyFocus = remember { FocusRequester() }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = {
            if (canDismissRoomEntry(isBusy)) onDismiss()
        },
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = canDismissRoomEntry(isBusy),
            dismissOnClickOutside = canDismissRoomEntry(isBusy),
            clippingEnabled = false,
        ),
    ) {
        // A scrim and the party's card: the hub's text sits right behind the keypad.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(start = 36.dp, top = 30.dp, end = 36.dp, bottom = 30.dp),
            contentAlignment = Alignment.Center,
        ) {
            val panelShape = RoundedCornerShape(15.dp)
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .background(color = DarkSurfaceElevated, shape = panelShape)
                    .border(1.dp, PartyChromeBorder, panelShape)
                    .padding(horizontal = 22.dp, vertical = 18.dp)
                    .then(rememberTvDialogInitialFocus(firstKeyFocus)),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvPartyEyebrow("Join a Watch Party")
                Text(
                    text = "Enter the party code",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SiloOnSurface,
                )

                // One slot per character; the next one to fill is outlined.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (0 until JoinCodeState.LENGTH).forEach { i ->
                        val char = state.code.getOrNull(i)
                        val next = i == state.code.length
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                                .border(
                                    width = if (next) 1.5.dp else 1.dp,
                                    color = if (next) SiloOnSurface.copy(alpha = 0.7f) else PartyChromeBorder,
                                    shape = RoundedCornerShape(8.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = char?.toString() ?: "",
                                fontSize = 20.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = SiloOnSurface,
                            )
                        }
                    }
                }

                // Focusable code-alphabet grid.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    JoinCodeKeys.chunked(KEYS_PER_ROW).forEachIndexed { rowIndex, rowKeys ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowKeys.forEachIndexed { colIndex, ch ->
                                JoinCodeKey(
                                    char = ch,
                                    // Joining is in flight, not a dead end — the
                                    // grid keeps its focus so the ring survives a
                                    // failed join.
                                    controlState = TvControlState.transient(!isBusy),
                                    onClick = { state = state.append(ch) },
                                    modifier = if (rowIndex == 0 && colIndex == 0) {
                                        Modifier
                                            .weight(1f)
                                            .focusRequester(firstKeyFocus)
                                    } else {
                                        Modifier.weight(1f)
                                    },
                                )
                            }
                            // Pad the final partial row so keys keep a consistent width.
                            repeat(KEYS_PER_ROW - rowKeys.size) {
                                Box(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    TvPartyButton(
                        label = "Delete",
                        kind = TvPartyButtonKind.Secondary,
                        state = TvControlState.transient(!isBusy && state.code.isNotEmpty()),
                        onClick = { state = state.backspace() },
                        height = 34.dp,
                    )
                    TvPartyButton(
                        label = if (isBusy) "Joining…" else "Join party",
                        state = TvControlState.transient(state.isComplete && !isBusy),
                        onClick = { state.normalized?.let(onJoin) },
                        height = 34.dp,
                        fontSize = 15.sp,
                    )
                }

                error?.let { message -> TvPartyBanner(message = message, warning = true) }
            }
        }
    }
}

/** Single focusable key tile in the join-code grid. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun JoinCodeKey(
    char: Char,
    controlState: TvControlState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(100.dp)
    val enabled = controlState.actionable

    Surface(
        onClick = { controlState.perform(onClick) },
        enabled = controlState.focusable,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        // Dimmed from `actionable`, not from the Surface's disabled slots: the
        // key stays focusable while a join is in flight, so it never enters the
        // disabled colour path.
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) {
                PartyChromeFill
            } else {
                Color.White.copy(alpha = 0.03f)
            },
            contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.42f),
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
            disabledContainerColor = Color.White.copy(alpha = 0.03f),
            disabledContentColor = Color.White.copy(alpha = 0.38f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = shape,
            ),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = Color.White.copy(alpha = 0.18f),
                elevation = 14.dp,
            ),
        ),
        modifier = modifier
            .height(30.dp)
            .tvControlSemantics(controlState),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = char.toString(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (isFocused) FocusedContent else Color.White,
            )
        }
    }
}
