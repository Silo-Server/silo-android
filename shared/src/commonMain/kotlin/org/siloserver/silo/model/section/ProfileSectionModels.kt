package org.siloserver.silo.model.section

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One row of a page as the acting profile sees it in the customization screen
 * (`GET /api/v2/profile/sections/settings`): the admin definition with the
 * profile's overrides applied, hidden rows included. [id] is the admin
 * section id, or the override's own id for a row the profile built
 * ([isCustom]).
 */
@Serializable
data class ProfileSectionSetting(
    val id: String,
    @SerialName("section_type") val sectionType: String = "",
    val title: String = "",
    @SerialName("default_title") val defaultTitle: String = "",
    val featured: Boolean = false,
    @SerialName("item_limit") val itemLimit: Int = 0,
    val hidden: Boolean = false,
    @SerialName("is_custom") val isCustom: Boolean = false,
    val customized: Boolean = false,
    val position: Int = 0,
    val config: JsonObject? = null,
)

/**
 * One saved profile override (`GET /api/v2/profile/sections`), also the shape
 * written back by `PUT /api/v2/profile/sections`. Only the members the write
 * accepts are modelled, so a read row re-encodes as a valid write: the
 * server rejects unknown members such as `created_at`. Null members are
 * omitted on the wire, which the server reads as "no override" ([position],
 * [featured], [itemLimit]) or "none saved" ([config], [userConfig]).
 */
@Serializable
data class SectionOverride(
    val id: String = "",
    @SerialName("section_id") val sectionId: String = "",
    val position: Int? = null,
    val hidden: Boolean = false,
    val removed: Boolean = false,
    @SerialName("section_type") val sectionType: String = "",
    val title: String = "",
    val featured: Boolean? = null,
    @SerialName("item_limit") val itemLimit: Int? = null,
    val config: JsonObject? = null,
    @SerialName("is_user_added") val isUserAdded: Boolean = false,
    @SerialName("user_section_type") val userSectionType: String = "",
    @SerialName("user_title") val userTitle: String = "",
    @SerialName("user_config") val userConfig: JsonObject? = null,
)
