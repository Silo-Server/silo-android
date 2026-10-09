package org.siloserver.silo.android.ui.layout

internal data class AudiobookTransportLayout(
    val spacingDp: Float,
    val requiresHorizontalScroll: Boolean,
)

private const val PreferredTransportSpacingDp = 28f
private const val MinimumTransportSpacingDp = 8f
private const val ChapterTransportControlWidthDp = 278f
private const val CompactTransportControlWidthDp = 182f

internal fun resolveAudiobookTransportLayout(
    availableWidthDp: Float,
    hasChapters: Boolean,
): AudiobookTransportLayout {
    val controlWidth = if (hasChapters) {
        ChapterTransportControlWidthDp
    } else {
        CompactTransportControlWidthDp
    }
    val gapCount = if (hasChapters) 4 else 2
    val fittedSpacing = ((availableWidthDp - controlWidth) / gapCount)
        .coerceIn(MinimumTransportSpacingDp, PreferredTransportSpacingDp)

    return AudiobookTransportLayout(
        spacingDp = fittedSpacing,
        requiresHorizontalScroll = availableWidthDp < controlWidth + MinimumTransportSpacingDp * gapCount,
    )
}
