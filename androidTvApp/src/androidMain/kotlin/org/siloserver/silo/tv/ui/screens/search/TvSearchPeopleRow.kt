package org.siloserver.silo.tv.ui.screens.search

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.ui.CastCrewCredit
import org.siloserver.silo.common.ui.CastCrewGroup
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.tv.ui.components.TvSectionHeader
import org.siloserver.silo.tv.ui.screens.detail.TvCastCard
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.TvRailScrollBehavior
import org.siloserver.silo.tv.ui.theme.tvRailPinOnFocus

/**
 * Cast and crew matching the search, drawn with the detail page's circular
 * cast cards so a person looks the same wherever they appear. Selecting one
 * opens that person's page.
 *
 * Rendered inside the search header, which the grid's contentPadding already
 * insets. The row bleeds back into that gutter so the first card can scale on
 * focus without being clipped by the row's edge.
 */
@Composable
internal fun TvSearchPeopleRow(
    people: List<Person>,
    firstItemFocusRequester: FocusRequester,
    onPersonClick: (Person, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Applied to every card, so UP and DOWN route the same from any position. */
    cardModifier: Modifier = Modifier,
    restoreItemIndex: Int = -1,
    restoreItemFocusRequester: FocusRequester? = null,
    onItemFocusChanged: (Person, Int, Boolean) -> Unit = { _, _, _ -> },
) {
    if (people.isEmpty()) return
    val rowState = rememberLazyListState()
    val members = remember(people) {
        people.map {
            CastCrewCredit(
                group = CastCrewGroup.Cast,
                name = it.name,
                caption = null,
                personId = it.id.toString(),
                photoUrl = it.photoUrl,
                photoThumbhash = it.photoThumbhash,
            )
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TvSectionHeader(title = "People")
        TvRailScrollBehavior {
            LazyRow(
                state = rowState,
                modifier = Modifier
                    .bleedStart(Spacing.safeArea)
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = PaddingValues(start = Spacing.safeArea, end = Spacing.safeArea, top = 12.dp, bottom = 4.dp),
            ) {
                itemsIndexed(
                    people,
                    key = { _, person -> person.id },
                    contentType = { _, _ -> "search-person" },
                ) { index, person ->
                    val isRestoreTarget = restoreItemFocusRequester != null && index == restoreItemIndex
                    TvCastCard(
                        credit = members[index],
                        photoSize = 100.dp,
                        // The restore target wins the slot when it is this card:
                        // index zero can be both.
                        focusRequester = if (isRestoreTarget) {
                            restoreItemFocusRequester
                        } else {
                            firstItemFocusRequester.takeIf { index == 0 }
                        },
                        cardModifier = cardModifier
                            .tvRailPinOnFocus(rowState, index, Spacing.safeArea)
                            .onFocusChanged { onItemFocusChanged(person, index, it.hasFocus) },
                        onClick = { onPersonClick(person, index) },
                    )
                }
            }
        }
    }
}
