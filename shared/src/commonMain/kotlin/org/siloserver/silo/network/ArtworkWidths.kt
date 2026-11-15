package org.siloserver.silo.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import kotlin.math.roundToInt

/**
 * The width, in device pixels, a screen draws each artwork slot at. The server
 * answers a slot with the narrowest cached size at least that wide on the
 * image's own ladder, or the original. A null slot keeps the server's default.
 * Slots are named for the response field: [backdrop] governs `backdrop_url`
 * even when an episode's backdrop holds its still.
 */
data class ArtworkWidths(
    val poster: Int? = null,
    val backdrop: Int? = null,
    val logo: Int? = null,
    val still: Int? = null,
    val photo: Int? = null,
    /** The server that advertised these widths; null when not bound to one. */
    val serverId: String? = null,
) {
    internal val params: List<Pair<String, Int>>
        get() = listOfNotNull(
            poster?.let { "poster_width" to it },
            backdrop?.let { "backdrop_width" to it },
            logo?.let { "logo_width" to it },
            still?.let { "still_width" to it },
            photo?.let { "photo_width" to it },
        ).map { (name, px) -> name to px.coerceIn(1, MAX_PX) }

    val isEmpty: Boolean get() = params.isEmpty()

    /** Stable fragment for request and cache keys; empty when no slot is set. */
    val key: String get() = params.joinToString("&") { (name, px) -> "$name=$px" }

    /** Keeps the slots named in [slots], as `slot_width_params` names them. */
    fun only(slots: Set<String>) = ArtworkWidths(
        poster = poster.takeIf { "poster" in slots },
        backdrop = backdrop.takeIf { "backdrop" in slots },
        logo = logo.takeIf { "logo" in slots },
        still = still.takeIf { "still" in slots },
        photo = photo.takeIf { "photo" in slots },
    )

    companion object {
        /** The widest value the server accepts. */
        const val MAX_PX = 7680

        /** Widths for slots laid out in dp on a screen of [density]. */
        fun ofDp(
            density: Float,
            poster: Float? = null,
            backdrop: Float? = null,
            logo: Float? = null,
            still: Float? = null,
            photo: Float? = null,
        ): ArtworkWidths {
            fun px(dp: Float?) = dp?.let { (it * density).roundToInt() }
            return ArtworkWidths(px(poster), px(backdrop), px(logo), px(still), px(photo))
        }
    }
}

/**
 * Sends [widths] as the per-slot width query parameters, unless another server
 * than the request's [scope] advertised them: a server that never did rejects
 * the read.
 */
internal fun HttpRequestBuilder.artworkWidths(widths: ArtworkWidths?, scope: AuthScopeSnapshot?) {
    if (widths?.serverId != null && widths.serverId != scope?.serverId) return
    widths?.params?.forEach { (name, px) -> parameter(name, px) }
}
