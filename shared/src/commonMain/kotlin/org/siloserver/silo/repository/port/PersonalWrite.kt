package org.siloserver.silo.repository.port

import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.siloserver.silo.network.AuthScopeSnapshot

/** One foreground, non-retryable v2 command. Never reconstructed from a legacy outbox row. */
@Serializable
sealed class PersonalWrite {
    abstract val itemId: String
    abstract val method: String
    abstract val path: String
    open val body: String? get() = null

    @Serializable
    data class Watched(override val itemId: String, val watched: Boolean) : PersonalWrite() {
        override val method get() = if (watched) "POST" else "DELETE"
        override val path get() = "/api/v2/watched/${itemId.encodeURLPathPart()}"
    }

    @Serializable
    data class Rating(override val itemId: String, val rating: Int?) : PersonalWrite() {
        override val method get() = if (rating == null) "DELETE" else "PUT"
        override val path get() = "/api/v2/ratings/${itemId.encodeURLPathPart()}"
        override val body get() = rating?.let { buildJsonObject { put("rating", it) }.toString() }
    }

    fun valid() = itemId.isNotBlank() && (this !is Rating || rating == null || rating in 1..5)
}

/** Scope stays in memory; persisted journals contain login/origin identifiers, never credentials. */
data class PersonalWriteHandle(val opId: Long, val scope: AuthScopeSnapshot, val command: PersonalWrite)

/** Captured synchronously at the UI action, before launching delayed work. */
data class PersonalWriteIntent(val command: PersonalWrite, val identityGeneration: Long, val sequence: Long)
