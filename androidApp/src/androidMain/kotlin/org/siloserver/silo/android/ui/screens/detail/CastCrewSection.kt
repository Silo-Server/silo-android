package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.CastMember
import org.siloserver.silo.model.catalog.CrewMember

/**
 * Horizontal scrolling row of cast members with circular portraits,
 * name, and character. Tappable rows route to person detail when a
 * `person_id` is available.
 */
@Composable
fun CastCrewSection(
    cast: List<CastMember>,
    @Suppress("UNUSED_PARAMETER") crew: List<CrewMember>,
    onPersonClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cast.isEmpty()) return

    // iOS PhoneCastRail: cardSpacing 14, cardWidth 96, photo 76.
    val rowState = rememberLazyListState()
    DeferImagePresentationWhileScrolling(rowState) {
    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = SafePadding),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(
            cast,
            key = { "${it.name}_${it.character}_${it.order}" },
            contentType = { "cast-member" },
        ) { member ->
            CastTile(
                photoUrl = member.photoUrl,
                photoThumbhash = member.photoThumbhash,
                name = member.name,
                role = member.character,
                onClick = member.personId?.let { id -> { onPersonClick(id) } },
            )
        }
    }
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
