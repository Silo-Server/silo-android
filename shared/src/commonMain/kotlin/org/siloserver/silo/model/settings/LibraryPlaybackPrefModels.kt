package org.siloserver.silo.model.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LibraryPlaybackPref(
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("library_id") val libraryId: Int,
    @SerialName("audio_language") val audioLanguage: String? = null,
    @SerialName("subtitle_language") val subtitleLanguage: String? = null,
    @SerialName("subtitle_mode") val subtitleMode: String? = null,
    @SerialName("show_forced_subtitles") val showForcedSubtitles: Boolean? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class LibraryPlaybackPrefsResponse(
    val preferences: List<LibraryPlaybackPref> = emptyList(),
)

@Serializable
data class LibraryPlaybackPrefRequest(
    @SerialName("audio_language") val audioLanguage: String? = null,
    @SerialName("subtitle_language") val subtitleLanguage: String? = null,
    @SerialName("subtitle_mode") val subtitleMode: String? = null,
    @SerialName("show_forced_subtitles") val showForcedSubtitles: Boolean? = null,
)
