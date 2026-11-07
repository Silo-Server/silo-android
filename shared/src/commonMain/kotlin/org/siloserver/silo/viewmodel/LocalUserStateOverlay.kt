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
    val base = userState ?: MediaItemUserState()
    return copy(
        userState = base.copy(
            played = state.watched ?: base.played,
            isFavorite = state.favorite ?: base.isFavorite,
        ),
    )
}

private fun LeafItemUserData.withLocalProgress(progress: LocalPlaybackProgress): LeafItemUserData =
    copy(
        isInProgress = true,
        positionSeconds = progress.positionSeconds,
        durationSeconds = progress.durationSeconds ?: durationSeconds,
        lastFileId = progress.fileId,
    )
