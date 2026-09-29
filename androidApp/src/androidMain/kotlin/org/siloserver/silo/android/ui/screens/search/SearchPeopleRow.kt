package org.siloserver.silo.android.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.theme.SiloSurfaceElevated
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.model.catalog.personInitials

/**
 * Cast and crew matching the search, as a horizontal rail of circular
 * portraits in the style of the detail page's cast rail. Tapping one opens
 * that person's page. People without a photo show their initials.
 */
@Composable
fun SearchPeopleRow(
    people: List<Person>,
    onPersonClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (people.isEmpty()) return
    val rowState = rememberLazyListState()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "People",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = SiloOnSurface,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        DeferImagePresentationWhileScrolling(rowState) {
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(end = 16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(people, key = { it.id }, contentType = { "search-person" }) { person ->
                    SearchPersonTile(person = person, onClick = { onPersonClick(person.id) })
                }
            }
        }
    }
}

@Composable
private fun SearchPersonTile(person: Person, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(96.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(SiloSurfaceElevated)
                .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (!person.photoUrl.isNullOrBlank()) {
                ThumbhashImage(
                    url = person.photoUrl,
                    thumbhash = person.photoThumbhash,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                )
            } else {
                Text(
                    text = personInitials(person.name),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SiloSecondaryText,
                    // The name below already says who this is.
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        Text(
            text = person.name,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = SiloOnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
