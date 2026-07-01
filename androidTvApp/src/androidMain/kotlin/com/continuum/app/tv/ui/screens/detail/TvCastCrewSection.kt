package com.continuum.app.tv.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import com.continuum.app.common.ui.components.ThumbhashImage
import com.continuum.app.model.catalog.CastMember
import com.continuum.app.tv.ui.theme.DarkSurfaceElevated
import com.continuum.app.tv.ui.theme.continuumCardDefaults

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
    firstItemFocusRequester: FocusRequester? = null,
    firstItemCardModifier: Modifier = Modifier,
    onDirectionDown: (() -> Boolean)? = null,
    onDirectionUp: (() -> Boolean)? = null,
    onCastMemberClick: (CastMember) -> Unit = {},
) {
    if (cast.isEmpty()) return
    val photoSize = 100.dp

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvDetailSectionHeader(eyebrow = "Cast", title = "& Crew")

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties {
                    if (firstItemFocusRequester != null) {
                        enter = { firstItemFocusRequester }
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
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            itemsIndexed(
                cast.take(24),
                key = { idx, member -> "${member.personId ?: member.name}-${member.order}-$idx" },
                contentType = { _, _ -> "cast-member" },
            ) { index, member ->
                TvCastCard(
                    member = member,
                    photoSize = photoSize,
                    focusRequester = firstItemFocusRequester.takeIf { index == 0 },
                    cardModifier = if (index == 0) firstItemCardModifier else Modifier,
                    onClick = { onCastMemberClick(member) },
                )
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
    val cardFocus = continuumCardDefaults(shape = shape, focusedScale = 1.05f)
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
                fontSize = 13.sp,
                lineHeight = 15.sp,
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
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                ),
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
