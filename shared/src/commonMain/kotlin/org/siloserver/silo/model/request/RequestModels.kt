package org.siloserver.silo.model.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object RequestMediaType {
    const val Movie = "movie"
    const val Series = "series"
    const val Audiobook = "audiobook"
    const val Ebook = "ebook"
    const val All = "all"
}

object RequestStatus {
    const val Pending = "pending"
    const val Approved = "approved"
    const val Queued = "queued"
    const val Downloading = "downloading"
    const val Completed = "completed"
    const val Failed = "failed"
}

object RequestOutcome {
    const val Active = "active"
    const val Declined = "declined"
    const val Cancelled = "cancelled"
    const val Failed = "failed"
}

/**
 * Values of [RequestState.reason]: why a title cannot be requested. They are
 * codes for deciding what to offer, not text; show [requestReasonCopy] instead.
 */
object RequestReason {
    const val AlreadyRequested = "already_requested"
    const val AlreadyAvailable = "already_available"
    const val RequestsDisabled = "requests_disabled"
    const val Blocked = "blocked"
    const val QuotaExceeded = "quota_exceeded"
}

object RequestAvailability {
    const val Missing = "missing"
    const val Available = "available"
}

/**
 * Values of the server's `state`: the one state to show a user for a request,
 * derived from its status, outcome, servers and library presence. It says what
 * `status` and `outcome` cannot: a finished download the library has not
 * picked up yet is `processing`, not `available`. Older servers omit it, and a
 * value this client does not know falls back to `status` and `outcome`.
 */
object RequestUserState {
    const val Pending = "pending"
    const val Approved = "approved"
    const val Processing = "processing"
    /** Some of a season request's seasons are in the library, not all. */
    const val PartiallyAvailable = "partially_available"
    const val Available = "available"
    const val Declined = "declined"
    const val Cancelled = "cancelled"
    const val Failed = "failed"
}

@Serializable
data class RequestState(
    val status: String? = null,
    /** The active request's user-facing [RequestUserState]; absent on older servers. */
    val state: String? = null,
    val requestable: Boolean = false,
    val reason: String = "",
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
data class RequestMediaResult(
    @SerialName("media_type") val mediaType: String,
    @SerialName("tmdb_id") val tmdbId: Int,
    val title: String,
    val year: Int? = null,
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val popularity: Double? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val availability: String = RequestAvailability.Missing,
    @SerialName("library_content_id") val libraryContentId: String? = null,
    val request: RequestState = RequestState(),
)

@Serializable
data class RequestMediaPage(
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_results") val totalResults: Int = 0,
    val results: List<RequestMediaResult> = emptyList(),
)

@Serializable
data class RequestDiscoverySection(
    val key: String,
    val title: String,
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_results") val totalResults: Int = 0,
    val results: List<RequestMediaResult> = emptyList(),
)

@Serializable
data class RequestsDiscoverResponse(
    @kotlinx.serialization.Required @SerialName("items") val sections: List<RequestDiscoverySection> = emptyList(),
)

@Serializable
data class RequestCastMember(
    val name: String,
    val character: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 0,
)

@Serializable
data class RequestMediaDetail(
    @SerialName("media_type") val mediaType: String,
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("imdb_id") val imdbId: String = "",
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    val title: String,
    @SerialName("original_title") val originalTitle: String = "",
    val tagline: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val year: Int? = null,
    val runtime: Int? = null,
    val genres: List<String> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val status: String = "",
    val homepage: String = "",
    @SerialName("content_rating") val contentRating: String = "",
    @SerialName("production_companies") val productionCompanies: List<String> = emptyList(),
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    val networks: List<String> = emptyList(),
    val cast: List<RequestCastMember> = emptyList(),
    val director: String = "",
    val creators: List<String> = emptyList(),
    val recommendations: List<RequestMediaResult> = emptyList(),
    val availability: String = RequestAvailability.Missing,
    @SerialName("library_content_id") val libraryContentId: String? = null,
    val request: RequestState = RequestState(),
)

@Serializable
data class CreateMediaRequest(
    @SerialName("media_type") val mediaType: String,
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    @SerialName("imdb_id") val imdbId: String = "",
    val title: String,
    val year: Int? = null,
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
)

@Serializable
data class RequestTarget(
    val id: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("integration_id") val integrationId: String = "",
    @SerialName("integration_kind") val integrationKind: String = "",
    @SerialName("instance_name") val instanceName: String = "",
    val quality: String = "",
    @SerialName("is_anime") val isAnime: Boolean = false,
    @SerialName("external_id") val externalId: String = "",
    @SerialName("external_status") val externalStatus: String = "",
    val status: String = RequestStatus.Pending,
    @SerialName("last_error") val lastError: String = "",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class MediaRequest(
    val id: String,
    val provider: String = "",
    @SerialName("media_type") val mediaType: String,
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    @SerialName("imdb_id") val imdbId: String = "",
    val title: String,
    val year: Int? = null,
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val status: String,
    val outcome: String,
    /** What to show the user ([RequestUserState]); absent on older servers. */
    val state: String? = null,
    @SerialName("requested_by_user_id") val requestedByUserId: String? = null,
    @SerialName("requested_by_profile_id") val requestedByProfileId: String = "",
    @SerialName("integration_kind") val integrationKind: String = "",
    @SerialName("is_anime") val isAnime: Boolean = false,
    val targets: List<RequestTarget> = emptyList(),
    @SerialName("external_id") val externalId: String = "",
    @SerialName("external_status") val externalStatus: String = "",
    @SerialName("library_content_id") val libraryContentId: String? = null,
    /** Admins only: why the last submission to a download server failed. */
    @SerialName("last_error") val lastError: String = "",
    /** Why the request was declined or withdrawn, when a reason was given. */
    @SerialName("outcome_reason") val outcomeReason: String = "",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("approved_at") val approvedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
) {
    val isActive: Boolean get() = outcome == RequestOutcome.Active
}

@Serializable
data class RequestsListResponse(
    @kotlinx.serialization.Required @SerialName("items") val requests: List<MediaRequest> = emptyList(),
    @kotlinx.serialization.Required val page: org.siloserver.silo.network.apiv2.PageInfo = org.siloserver.silo.network.apiv2.PageInfo(hasMore = false),
)

/**
 * `GET /api/v2/requests/status`. The contract requires `allowed` and `state`;
 * they decode as optional so a response without them fails closed.
 */
@Serializable
data class RequestsFeatureStatus(
    @SerialName("requests_enabled") val requestsEnabled: Boolean,
    val allowed: Boolean? = null,
    val state: String? = null,
) {
    /** The feature is on and this profile may use it; a blocked account sees `allowed: false`. */
    val isAvailable: Boolean
        get() = requestsEnabled && allowed == true && state == "available"
}

/** `GET /api/v2/admin/requests/capabilities`: whether the request integration is configured. */
@Serializable
data class AdminRequestCapabilities(
    val available: Boolean = false,
)

/** Moderation actions an admin can take on someone's request (`POST /api/v2/admin/requests/{id}/…`). */
enum class AdminRequestAction(val path: String) {
    Approve("approve"),
    Decline("decline"),
    Retry("retry"),
}

@Serializable
data class AdminRequestActionBody(
    val reason: String? = null,
)
