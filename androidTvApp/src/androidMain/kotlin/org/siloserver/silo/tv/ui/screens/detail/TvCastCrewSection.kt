package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.CastMember
import org.siloserver.silo.tv.ui.theme.TvRailScrollBehavior
import org.siloserver.silo.tv.ui.theme.tvRailPinOnFocus
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.siloCardDefaults

internal fun restoredRailIndex(lastFocusedIndex: Int, itemCount: Int): Int? =
    if (itemCount <= 0) null else lastFocusedIndex.coerceIn(0, itemCount - 1)

/**
 * Horizontal rail of circular cast portraits, matching the tvOS
 * `TVDetailCastRail`. Cards lift on focus; the whole rail is a `focusGroup`
 * so seasons-rail / hero focus arithmetic stays clean. Cards lift on focus
 * and invoke [onCastMemberClick] when selected. The caller decides whether a
 * member has a routable `person_id`; members without one can still render as
 * display-only credits.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun TvCastCrewSection(
    cast: List<CastMember>,
    modifier: Modifier = Modifier,
    /**
     * Safe-area inset applied INSIDE the rail (header padding + LazyRow
     * contentPadding) instead of on the section, so the leftmost card's focus
     * ring can render into the inset instead of being clipped by the rail's
     * viewport edge (QA 2026-07-08).
     */
    horizontalContentPadding: Dp = 0.dp,
    firstItemFocusRequester: FocusRequester? = null,
    firstItemCardModifier: Modifier = Modifier,
    onDirectionDown: (() -> Boolean)? = null,
    onDirectionUp: (() -> Boolean)? = null,
    /**
     * Attaches [restoreFocusRequester] to the card at this index so the caller
     * can put focus back on the cast member that pushed a person page.
     */
    restoreFocusIndex: Int = -1,
    restoreFocusRequester: FocusRequester? = null,
    /** Fires when the restore-target card actually gains focus, so callers
     *  can end their restore window immediately instead of holding it open
     *  (and re-requesting) for a fixed number of frames. */
    onRestoreCardFocused: (() -> Unit)? = null,
    onCastMemberClick: (index: Int, member: CastMember) -> Unit = { _, _ -> },
) {
    if (cast.isEmpty()) return
    val photoSize = 100.dp
    var lastFocusedIndex by rememberSaveable { mutableIntStateOf(-1) }
    val rememberedEntryRequester = remember { FocusRequester() }
    val castListState = rememberLazyListState()
    // One list per cast snapshot: a fresh take() per composition re-keys the
    // LazyRow interval on every focus move.
    val visibleCast = remember(cast) { cast.take(24) }
    val rememberedEntryIndex = restoredRailIndex(lastFocusedIndex, visibleCast.size)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvDetailSectionHeader(
            title = "Cast & Crew",
            modifier = Modifier.padding(horizontal = horizontalContentPadding),
        )

        TvRailScrollBehavior {
        LazyRow(
            state = castListState,
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties {
                    // While a person-page return is pending, entry into the
                    // rail (default focus, D-pad, programmatic) must land on
                    // the launch card — the enter redirect otherwise sends
                    // every entry to card 0 and silently rolls back direct
                    // requests to other cards.
                    val enterTarget = restoreFocusRequester
                        ?: rememberedEntryRequester.takeIf { rememberedEntryIndex != null }
                        ?: firstItemFocusRequester
                    if (enterTarget != null) {
                        enter = { enterTarget }
                    }
                }
                .then(
                    if (onDirectionDown != null || onDirectionUp != null) {
                        Modifier.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else {
                                when (event.key) {
                                    Key.DirectionDown -> onDirectionDown?.invoke() ?: false
                                    Key.DirectionUp -> onDirectionUp?.invoke() ?: false
                                    else -> false
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(
                horizontal = horizontalContentPadding,
                vertical = 12.dp,
            ),
        ) {
            itemsIndexed(
                visibleCast,
                key = { idx, member -> "${member.personId ?: member.name}-${member.order}-$idx" },
                contentType = { _, _ -> "cast-member" },
            ) { index, member ->
                TvCastCard(
                    member = member,
                    photoSize = photoSize,
                    focusRequester = firstItemFocusRequester.takeIf { index == 0 },
                    cardModifier = (if (index == 0) firstItemCardModifier else Modifier)
                        .then(
                            if (index == rememberedEntryIndex) {
                                Modifier.focusRequester(rememberedEntryRequester)
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (restoreFocusRequester != null && index == restoreFocusIndex) {
                                Modifier.focusRequester(restoreFocusRequester)
                            } else {
                                Modifier
                            },
                        )
                        .tvRailPinOnFocus(castListState, index, horizontalContentPadding)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                lastFocusedIndex = index
                                if (restoreFocusRequester != null && index == restoreFocusIndex) {
                                    onRestoreCardFocused?.invoke()
                                }
                            }
                        },
                    onClick = { onCastMemberClick(index, member) },
                )
            }
        }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvCastCard(
    member: CastMember,
    photoSize: Dp,
    focusRequester: FocusRequester?,
    cardModifier: Modifier,
    onClick: () -> Unit = {},
) {
    val shape = CircleShape
    val cardFocus = siloCardDefaults(shape = shape, focusedScale = 1.05f)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Column(
        modifier = Modifier.width(photoSize),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = CardDefaults.shape(shape = shape),
            scale = cardFocus.scale,
            border = cardFocus.border,
            glow = cardFocus.glow,
            modifier = cardModifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .size(photoSize),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkSurfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                if (!member.photoUrl.isNullOrBlank()) {
                    ThumbhashImage(
                        url = member.photoUrl,
                        thumbhash = member.photoThumbhash,
                        contentDescription = member.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(photoSize * 0.4f),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = member.name,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
            ),
            color = if (isFocused) Color.White else Color.White.copy(alpha = 0.88f),
            // Reserve two lines so single- and two-line names bottom-align,
            // mirroring tvOS `lineLimit(2, reservesSpace: true)`.
            minLines = 2,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!member.character.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = member.character!!,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                ),
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
