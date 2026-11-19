package org.siloserver.silo.common.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.currentNumberLocale
import org.siloserver.silo.common.i18n.pluralStringResourceWithNames
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.model.personal.CollectionCountState

/**
 * The count badge on a collection card. An older server's 0 can mean the
 * smart count isn't computed yet, so it reads "Smart" unless [exact] or the
 * server marks the count available.
 */
@Composable
fun collectionCountBadge(count: Int?, state: CollectionCountState, exact: Boolean = false): String = when {
    state == CollectionCountState.Unavailable -> stringResource(CommonR.string.collections_countUnavailable)
    count != null && (count > 0 || exact || state == CollectionCountState.Exact) ->
        LocaleFormat.groupedCount(count, currentNumberLocale())
    else -> stringResource(CommonR.string.collections_type_smart)
}

/** "N items" under a collection card's name, or null when there's no count to show. */
@Composable
fun collectionCountLine(count: Int?, state: CollectionCountState): String? = when {
    state == CollectionCountState.Unavailable -> stringResource(CommonR.string.collections_countUnavailable)
    count != null -> pluralStringResource(CommonR.plurals.collections_itemCount, count, count, LocaleFormat.groupedCount(count))
    else -> null
}

/** "Kind - N items" under a collection in the Collections list; the kind alone when an older server reports none. */
@Composable
fun collectionRowSubtitle(kind: String, count: Int?, state: CollectionCountState): String = when {
    state == CollectionCountState.Unavailable ->
        stringResourceWithNames(CommonR.string.collections_rowSubtitleCountUnavailable, kind)
    count != null && (count > 0 || state == CollectionCountState.Exact) ->
        pluralStringResourceWithNames(CommonR.plurals.collections_rowSubtitle, count, kind)
    else -> kind
}
