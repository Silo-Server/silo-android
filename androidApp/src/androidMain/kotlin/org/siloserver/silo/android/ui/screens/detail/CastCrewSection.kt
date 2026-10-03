package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.common.ui.CastCrewCredit
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.ui.components.verticalText
import org.siloserver.silo.common.ui.personInitials

/**
 * The detail page's Cast & Crew row: directors (or series creators), then
 * writers, then cast, separated by thin labelled dividers. Every credit uses
 * the same [CastTile], so crew and cast cards stay the same size. Tappable
 * cards route to person detail when a `person_id` is available.
 */
@Composable
fun CastCrewSection(
    credits: List<CastCrewCredit>,
    onPersonClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (credits.isEmpty()) return

    // iOS PhoneCastRail: cardSpacing 14, cardWidth 96, photo 76.
    val rowState = rememberLazyListState()
    DeferImagePresentationWhileScrolling(rowState) {
    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = SafePadding),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        // A writer-director or actor-director can appear in two groups, so
        // the name alone is not a unique key.
        itemsIndexed(
            credits,
            key = { index, credit -> "${credit.group}_${index}_${credit.personId ?: credit.name}" },
            contentType = { _, _ -> "cast-member" },
        ) { _, credit ->
            // The divider lives inside the card's item so it never becomes a
            // tappable or separately keyed row entry.
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                credit.dividerLabel?.let { CastCrewDivider(label = it) }
                CastTile(
                    photoUrl = credit.photoUrl,
                    photoThumbhash = credit.photoThumbhash,
                    name = credit.name,
                    role = credit.caption,
                    onClick = credit.personId?.let { id -> { onPersonClick(id) } },
                )
            }
        }
    }
    }
}

/** Thin vertical rule with a small rotated group label, e.g. "WRITERS". */
@Composable
private fun CastCrewDivider(label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(76.dp),
    ) {
        Text(
            text = label.uppercase(),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = SiloSecondaryText,
            maxLines = 1,
            modifier = Modifier.verticalText(),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.16f)),
        )
    }
}

@Composable
private fun CastTile(
    photoUrl: String?,
    photoThumbhash: String?,
    name: String,
    role: String?,
    onClick: (() -> Unit)?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(96.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        // iOS PhoneCastRail photo: 76pt circle, white-0.10 stroke.
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
        ) {
            ThumbhashImage(
                url = photoUrl,
                thumbhash = photoThumbhash,
                contentDescription = name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
            if (photoUrl.isNullOrBlank()) {
                Text(
                    text = personInitials(name),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SiloSecondaryText,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Name 12pt semibold, character 11pt regular (secondary).
            Text(
                text = name,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = DetailPrimaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (!role.isNullOrBlank()) {
                Text(
                    text = role,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Normal,
                    color = SiloSecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
