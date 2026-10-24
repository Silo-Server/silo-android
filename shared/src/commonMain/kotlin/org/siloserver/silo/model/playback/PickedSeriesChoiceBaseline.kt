package org.siloserver.silo.model.playback

import org.siloserver.silo.model.catalog.ItemDetail

/**
 * The effective audio of the item a detail-page audio pick was made on, per
 * version. The page lays its own pick over loaded details until a refreshed
 * detail answers differently ([supersededBy]): the server then holds the
 * pick, or a newer choice made elsewhere.
 */
data class PickedAudioBaseline(val contentId: String, val effectiveAudio: List<Int?>) {
    fun supersededBy(detail: ItemDetail): Boolean =
        detail.contentId == contentId && detail.versions.map { it.effectiveAudioTrackIndex } != effectiveAudio

    companion object {
        fun of(detail: ItemDetail) = PickedAudioBaseline(detail.contentId, detail.versions.map { it.effectiveAudioTrackIndex })
    }
}

/** [detail]'s effective subtitle fields, the baseline a detail-page subtitle pick is laid over. */
fun effectiveSubtitleFieldsOf(detail: ItemDetail) = EffectiveSubtitleFields(
    language = detail.effectiveSubtitleLanguage,
    mode = detail.effectiveSubtitleMode,
    signature = detail.effectiveSubtitleTrackSignature,
)
