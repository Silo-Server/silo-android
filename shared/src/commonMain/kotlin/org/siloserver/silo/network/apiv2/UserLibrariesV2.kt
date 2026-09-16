package org.siloserver.silo.network.apiv2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.personal.UserLibrary

/** Complete configuration collection; it is not a cursor browse endpoint. */
@Serializable
internal data class UserLibrariesV2(val items: List<UserLibraryV2>, val page: PageInfo? = null) {
    fun project(): List<UserLibrary> {
        require(page?.hasMore != true && page?.nextCursor.isNullOrBlank())
        require(items.map { it.id }.distinct().size == items.size)
        return items.map { row -> UserLibrary(checkedPositiveId(row.id), row.name, row.type, row.sortOrder, row.posterUrl) }
    }
}

@Serializable
internal data class UserLibraryV2(
    @Serializable(with = DetailStringIdSerializer::class) val id: String,
    val name: String,
    val type: String,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("poster_url") val posterUrl: String? = null,
)
