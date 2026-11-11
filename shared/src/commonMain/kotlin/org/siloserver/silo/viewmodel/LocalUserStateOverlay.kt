package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.repository.port.LocalContentState
import org.siloserver.silo.repository.port.LocalPlaybackProgress

fun applyLocalStateOverlay(
    sections: List<ResolvedSection>,
    contentStates: Map<String, LocalContentState>,
    progressStates: Map<String, LocalPlaybackProgress>,
): List<ResolvedSection> {
    if (contentStates.isEmpty() && progressStates.isEmpty()) return sections
    return sections.map { section ->
        section.copy(
            items = section.items.map { item ->
                val state = contentStates[item.contentId]
                val progress = progressStates[item.contentId]
                item
                    .withLocalContentState(state)
                    .let { applyLocalPlaybackProgress(it, progress) }
            },
        )
    }
}

fun applyLocalPlaybackProgress(
    item: SectionItem,
    progress: LocalPlaybackProgress?,
): SectionItem {
    val local = progress ?: return item
    if (!localProgressWins(currentPosition = item.positionSeconds, localPosition = local.positionSeconds)) {
        return item
    }
    return item.copy(
        positionSeconds = local.positionSeconds,
        durationSeconds = local.durationSeconds ?: item.durationSeconds,
    )
}

fun applyLocalPlaybackProgress(
    detail: ItemDetail,
    progress: LocalPlaybackProgress?,
): ItemDetail {
    val local = progress ?: return detail
    val current = detail.userData
    if (!localProgressWins(currentPosition = current?.positionSeconds, localPosition = local.positionSeconds)) {
        return detail
    }
    val updated = (current ?: LeafItemUserData()).withLocalProgress(local)
    return detail.copy(userData = updated)
}

fun applyLocalPlaybackProgress(
    episode: EpisodeListItem,
    progress: LocalPlaybackProgress?,
): EpisodeListItem {
    val local = progress ?: return episode
    val current = episode.userData
    if (!localProgressWins(currentPosition = current?.positionSeconds, localPosition = local.positionSeconds)) {
        return episode
    }
    val updated = (current ?: LeafItemUserData()).withLocalProgress(local)
    return episode.copy(userData = updated)
}

private fun SectionItem.withLocalContentState(state: LocalContentState?): SectionItem {
    if (state == null) return this
    return copy(userState = userState.withLocalContentState(state))
}

/**
 * [this] server state with the local projection laid over it. A local
 * `watched` value replaces `played`, as it always has. The episode counts are
 * dropped only while that watched change is still unacknowledged
 * ([LocalContentState.watchedPending]): until then they describe the state
 * before the change. Once the server has it, its counts are the newer answer
 * and stay, so a series marked unwatched and then partly rewatched shows its
 * episodes left.
 */
fun MediaItemUserState?.withLocalContentState(state: LocalContentState): MediaItemUserState {
    val base = this ?: MediaItemUserState()
    val watched = when {
        state.watched == null -> base
        state.watchedPending -> base.withPlayed(state.watched)
        else -> base.copy(played = state.watched)
    }
    return watched.copy(isFavorite = state.favorite ?: base.isFavorite)
}

private fun LeafItemUserData.withLocalProgress(progress: LocalPlaybackProgress): LeafItemUserData =
    copy(
        isInProgress = true,
        positionSeconds = progress.positionSeconds,
        durationSeconds = progress.durationSeconds ?: durationSeconds,
        lastFileId = progress.fileId,
    )

private fun localProgressWins(currentPosition: Double?, localPosition: Double?): Boolean {
    val local = localPosition?.takeIf { it.isFinite() && it > 0.0 } ?: return false
    val current = currentPosition?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    return local > current
}
