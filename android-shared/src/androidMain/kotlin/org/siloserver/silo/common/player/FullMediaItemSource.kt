package org.siloserver.silo.common.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TimelineWithUpdatedMediaItem
import androidx.media3.exoplayer.source.WrappingMediaSource

/**
 * Reports [mediaItem], sidecars included, for a source built from a copy of
 * it.
 *
 * The media source factory builds an item's content from a copy with its
 * subtitle configurations removed and merges each sidecar beside it.
 * `MergingMediaSource` reports its first child's item, so the player's
 * timeline, and `Player.getMediaItemAt`, would name the copy. Anything that
 * rebuilds the playlist from the player, such as the subtitle sync reprepare,
 * then drops the sidecars. That also changes the merged track layout a text
 * selection points at, so the selection is lost too.
 */
@UnstableApi
internal class FullMediaItemSource(
    child: MediaSource,
    private val mediaItem: MediaItem,
) : WrappingMediaSource(child) {

    override fun getMediaItem(): MediaItem = mediaItem

    /** An update would reach the children with the full item; let the player rebuild the source instead. */
    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean = false

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        refreshSourceInfo(TimelineWithUpdatedMediaItem.create(newTimeline, mediaItem))
    }
}
