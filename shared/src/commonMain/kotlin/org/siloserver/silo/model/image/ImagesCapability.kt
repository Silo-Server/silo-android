package org.siloserver.silo.model.image

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `image_size` values the server contract defines. */
object ImageSize {
    const val SMALL = "small"
    const val MEDIUM = "medium"
    const val LARGE = "large"
    const val ORIGINAL = "original"

    /** The query parameter name the server reads the variant from. */
    const val PARAM = "image_size"
}

/**
 * Server advertisement for image-variant selection (GET /api/v1/images/capability).
 *
 * When present, the server accepts an `image_size` query parameter on the
 * catalog, detail, watch and section endpoints and bakes the chosen variant
 * into every image URL it returns. Servers that predate the feature answer 404,
 * which the client treats as "feature off" — the parameter is then omitted and
 * the server's default sizing applies.
 *
 * [widths] maps an image role (`poster`, `still`, `logo`, `backdrop`) to the
 * pixel width each named size resolves to. It is advertisement only: the client
 * never reconstructs URLs, it just names a size and uses what comes back.
 */
@Serializable
data class ImagesCapability(
    @SerialName("schema_version") val schemaVersion: Int = 0,
    val param: String = ImageSize.PARAM,
    val sizes: List<String> = emptyList(),
    val widths: Map<String, Map<String, Int>> = emptyMap(),
    @SerialName("original_max_width_px") val originalMaxWidthPx: Int? = null,
) {
    /** Whether [size] is one the server says it can serve. */
    fun supports(size: String): Boolean = param == ImageSize.PARAM && size in sizes
}
