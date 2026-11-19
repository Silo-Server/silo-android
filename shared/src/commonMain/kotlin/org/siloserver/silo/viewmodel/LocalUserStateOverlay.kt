package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.withLastPlayedFile
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

// Local progress is a downloaded playback position still waiting to reach the
// server (`UserItemStatePort.localPlaybackProgress`), so it is newer than the
// server's answer and wins whether it is ahead or behind. Once synced there is
// none, and the server's answer, including a watched title's lack of one, stands.
fun applyLocalPlaybackProgress(
    item: SectionItem,
    progress: LocalPlaybackProgress?,
): SectionItem {
    val local = progress ?: return item
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
    val updated = (current ?: LeafItemUserData()).withLocalProgress(local)
    return detail.copy(userData = updated)
}

fun applyLocalPlaybackProgress(
    episode: EpisodeListItem,
    progress: LocalPlaybackProgress?,
): EpisodeListItem {
    val local = progress ?: return episode
    val current = episode.userData
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
    withLastPlayedFile(progress.fileId).copy(
        isInProgress = true,
        positionSeconds = progress.positionSeconds,
        durationSeconds = progress.durationSeconds ?: durationSeconds,
        lastFileId = progress.fileId,
    )
