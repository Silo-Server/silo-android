package org.siloserver.silo.model.ebook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server advertisement for Kindle->EPUB conversion (GET /api/v2/capabilities/ebooks).
 * When [enabled], the server converts mobi/azw/azw3 to EPUB on read, so the
 * client can render those formats in-app instead of opening them externally.
 */
@Serializable
data class EbookConversionCapability(
    val enabled: Boolean = false,
    @SerialName("source_formats") val sourceFormats: List<String> = emptyList(),
    @SerialName("served_format") val servedFormat: String = "epub",
    val header: String = "",
    @SerialName("header_failed_value") val headerFailedValue: String = "failed",
)
