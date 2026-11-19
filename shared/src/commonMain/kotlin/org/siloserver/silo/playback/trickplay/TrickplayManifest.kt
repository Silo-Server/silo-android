package org.siloserver.silo.playback.trickplay

import org.siloserver.silo.network.apiv2.WatchTrickplayV2
import org.siloserver.silo.network.resolveArtworkUrl
import org.siloserver.silo.network.apiv2.checkedPositiveId
import org.siloserver.silo.util.parseRfc3339ToEpochMillis

/**
 * Seek-bar previews the server published for one file: JPEG sprite sheets of
 * [columns] x [rows] thumbnails, one thumbnail per [intervalMs] of media time
 * (see silo-server `docs/architecture/trickplay.md`). Every sheet has the
 * full grid, black after the last thumbnail.
 */
data class TrickplayManifest(
    val fileId: Int,
    val intervalMs: Long,
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
    val columns: Int,
    val rows: Int,
    val count: Int,
    /** One absolute URL per sheet, in order. Each carries its own authorization. */
    val sheetUrls: List<String>,
    /** When the sheet URLs stop working; null when the server's time did not parse. */
    val expiresAtEpochMs: Long?,
) {
    /** The thumbnail for [seconds] of media time, the last one past the end; null for a time outside the file. */
    fun tileAt(seconds: Double): TrickplayTile? {
        if (!seconds.isFinite() || seconds < 0) return null
        val position = seconds * 1000 / intervalMs
        val index = if (position >= count - 1) count - 1 else position.toInt()
        val perSheet = columns * rows
        val cell = index % perSheet
        return TrickplayTile(
            sheet = index / perSheet,
            x = (cell % columns) * thumbnailWidth,
            y = (cell / columns) * thumbnailHeight,
            width = thumbnailWidth,
            height = thumbnailHeight,
        )
    }

    /** Whether [other] cuts the same thumbnails, so its sheet URLs can stand in for these. */
    fun hasSameLayout(other: TrickplayManifest): Boolean =
        sheetUrls.size == other.sheetUrls.size &&
            copy(sheetUrls = other.sheetUrls, expiresAtEpochMs = other.expiresAtEpochMs) == other

    override fun toString(): String =
        "TrickplayManifest(fileId=$fileId, intervalMs=$intervalMs, ${thumbnailWidth}x$thumbnailHeight, " +
            "${columns}x$rows, count=$count, sheets=${sheetUrls.size}, expiresAtEpochMs=$expiresAtEpochMs)"

    companion object {
        /**
         * Validates [v2] and resolves its sheet URLs against [base], the URL
         * the manifest was read from. Null when the manifest cannot be cut
         * safely, so the player keeps its text-only preview.
         */
        fun from(v2: WatchTrickplayV2, base: String): TrickplayManifest? {
            val fileId = runCatching { checkedPositiveId(v2.fileId) }.getOrNull() ?: return null
            val geometry = listOf(v2.thumbnailWidth, v2.thumbnailHeight, v2.tileColumns, v2.tileRows)
            if (v2.intervalMs <= 0 || v2.thumbnailCount <= 0 || geometry.any { it !in 1..MAX_SHEET_SIDE }) return null
            // Every factor is now at most MAX_SHEET_SIDE, so these products fit a Long.
            if (v2.tileColumns * v2.thumbnailWidth > MAX_SHEET_SIDE || v2.tileRows * v2.thumbnailHeight > MAX_SHEET_SIDE) return null
            if (v2.thumbnailCount > Int.MAX_VALUE) return null
            val perSheet = v2.tileColumns * v2.tileRows
            // Thumbnails are cut by index, so every sheet must be there, once.
            val sorted = v2.sheets.sortedBy { it.index }
            if (sorted.size.toLong() != (v2.thumbnailCount + perSheet - 1) / perSheet) return null
            if (sorted.withIndex().any { (position, sheet) -> sheet.index != position.toLong() }) return null
            val sheetUrls = sorted.map { sheet ->
                runCatching { resolveArtworkUrl(sheet.url, base) }.getOrNull()?.takeIf(::isHttpUrl) ?: return null
            }
            return TrickplayManifest(
                fileId = fileId,
                intervalMs = v2.intervalMs,
                thumbnailWidth = v2.thumbnailWidth.toInt(),
                thumbnailHeight = v2.thumbnailHeight.toInt(),
                columns = v2.tileColumns.toInt(),
                rows = v2.tileRows.toInt(),
                count = v2.thumbnailCount.toInt(),
                sheetUrls = sheetUrls,
                expiresAtEpochMs = parseRfc3339ToEpochMillis(v2.expiresAt),
            )
        }

        /** The largest sheet side, in pixels, a player decodes. The server keeps sheets within 3200. */
        private const val MAX_SHEET_SIDE = 8_192L

        private fun isHttpUrl(url: String): Boolean {
            val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
            return scheme == "http" || scheme == "https"
        }
    }
}

/** One thumbnail: its sheet and its rectangle within that sheet, in the manifest's pixels. */
data class TrickplayTile(val sheet: Int, val x: Int, val y: Int, val width: Int, val height: Int)
