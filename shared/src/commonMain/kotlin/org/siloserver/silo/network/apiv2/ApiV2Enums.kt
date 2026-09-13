package org.siloserver.silo.network.apiv2

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * String-backed enums for API v2. Each wire enum is an inline value class
 * over the raw string: a value the app does not know decodes successfully
 * with the wire value intact, unlike a Kotlin `enum class`, where
 * `coerceInputValues` would silently substitute the property default.
 */
@Serializable
@JvmInline
value class AccountRole(val wire: String)

@Serializable
@JvmInline
value class AvatarSource(val wire: String)

@Serializable
@JvmInline
value class QualityPreference(val wire: String)

@Serializable
@JvmInline
value class SubtitleMode(val wire: String)

@Serializable
@JvmInline
value class MaxPlaybackQuality(val wire: String)
