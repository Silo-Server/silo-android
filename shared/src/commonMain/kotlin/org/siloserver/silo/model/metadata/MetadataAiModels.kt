package org.siloserver.silo.model.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How viewer-facing description translation surfaces on detail screens.
 * Mirrors silo-apple's MetadataAIStatus.onView; the server emits
 * `off | button | auto` and unknown future values coerce to [Off] (the
 * shared client Json uses coerceInputValues).
 */
@Serializable
enum class MetadataAiOnView {
    @SerialName("off")
    Off,

    @SerialName("button")
    Button,

    @SerialName("auto")
    Auto,
}

/** Profile-scoped v2 capability, with enabled projected from its state. */
@Serializable
data class MetadataAiStatus(
    val enabled: Boolean = false,
    val state: String = "not_configured",
    val revision: String = "",
    @SerialName("on_view") val onView: MetadataAiOnView = MetadataAiOnView.Off,
)

/** Viewer on-view translation action body. */
@Serializable
data class TranslateDescriptionRequest(
    @SerialName("target_language") val targetLanguage: String,
)

/** Bare 202 receipt; a recently failed job may be reused without new work. */
@Serializable
data class MetadataTranslationJob(
    @Serializable(with = org.siloserver.silo.network.apiv2.DetailStringIdSerializer::class) val id: String,
    @SerialName("target_kind") val targetKind: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("target_language") val targetLanguage: String,
    val status: String,
    @SerialName("include_children") val includeChildren: Boolean = false,
    @SerialName("source_language") val sourceLanguage: String = "",
    val engine: String = "",
    val model: String = "",
    val progress: Double = 0.0,
    @SerialName("progress_message") val progressMessage: String = "",
    @SerialName("fields_done") val fieldsDone: Int = 0,
    @SerialName("fields_total") val fieldsTotal: Int = 0,
    val force: Boolean = false,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)
