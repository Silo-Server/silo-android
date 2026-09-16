package org.siloserver.silo.model.ebook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class EbookReaderProgress(
    @SerialName("content_id") val contentId: String? = null,
    @SerialName("file_id") val fileId: Int? = null,
    val location: String? = null,
    val progress: Double = 0.0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SaveEbookProgressRequest(
    @SerialName("file_id") val fileId: Int,
    val location: String,
    val progress: Double,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class EbookReaderConfig(
    @SerialName("content_id") val contentId: String? = null,
    val config: JsonObject = JsonObject(emptyMap()),
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SaveEbookReaderConfigRequest(
    val config: JsonObject,
)

@Serializable
data class EbookAnnotationListResponse(
    val items: List<EbookAnnotation> = emptyList(),
)

@Serializable
data class EbookAnnotation(
    val id: String,
    val etag: String? = null,
    @SerialName("content_id") val contentId: String,
    val kind: String,
    @SerialName("cfi_range") val cfiRange: String? = null,
    val location: String? = null,
    @SerialName("selected_text") val selectedText: String? = null,
    val note: String? = null,
    val style: String? = null,
    val color: String? = null,
    val metadata: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SaveEbookAnnotationRequest(
    val kind: String,
    @SerialName("cfi_range") val cfiRange: String? = null,
    val location: String? = null,
    @SerialName("selected_text") val selectedText: String? = null,
    val note: String? = null,
    val style: String? = null,
    val color: String? = null,
    val metadata: JsonElement? = null,
)

fun localBookmarkAnnotation(
    id: String,
    contentId: String,
    location: String,
    createdAt: String? = null,
): EbookAnnotation =
    EbookAnnotation(
        id = id,
        contentId = contentId,
        kind = "bookmark",
        location = location,
        createdAt = createdAt,
    )
