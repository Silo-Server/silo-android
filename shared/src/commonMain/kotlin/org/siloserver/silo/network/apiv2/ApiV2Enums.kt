package org.siloserver.silo.network.apiv2

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * String-backed enums for the native API v2 pilot.
 *
 * Each wire enum is an inline value class over the raw string with a typed
 * [Known] accessor. A value the app does not know decodes successfully and is
 * observable as `known == null` with the raw wire value intact — unlike a
 * Kotlin `enum class`, where `coerceInputValues` would silently substitute the
 * property default and the app could never tell the difference.
 */
@Serializable
@JvmInline
value class AccountRole(val wire: String) {
    enum class Known(val wire: String) { ADMIN("admin"), USER("user") }

    val known: Known? get() = Known.entries.firstOrNull { it.wire == wire }
    val isAdmin: Boolean get() = known == Known.ADMIN

    companion object {
        val ADMIN = AccountRole(Known.ADMIN.wire)
        val USER = AccountRole(Known.USER.wire)
    }
}

@Serializable
@JvmInline
value class AvatarSource(val wire: String) {
    enum class Known(val wire: String) { NONE("none"), PRESET("preset"), UPLOAD("upload") }

    val known: Known? get() = Known.entries.firstOrNull { it.wire == wire }
}

@Serializable
@JvmInline
value class QualityPreference(val wire: String) {
    enum class Known(val wire: String) { AUTO("auto"), ORIGINAL("original") }

    val known: Known? get() = Known.entries.firstOrNull { it.wire == wire }
}

@Serializable
@JvmInline
value class SubtitleMode(val wire: String) {
    enum class Known(val wire: String) { AUTO("auto"), ALWAYS("always"), OFF("off") }

    val known: Known? get() = Known.entries.firstOrNull { it.wire == wire }
}

@Serializable
@JvmInline
value class MaxPlaybackQuality(val wire: String) {
    enum class Known(val wire: String) { P1080("1080p"), P2160("2160p") }

    val known: Known? get() = Known.entries.firstOrNull { it.wire == wire }
}

@Serializable
@JvmInline
value class ProgressStatus(val wire: String) {
    enum class Known(val wire: String) { IN_PROGRESS("in_progress"), COMPLETED("completed") }

    val known: Known? get() = Known.entries.firstOrNull { it.wire == wire }

    companion object {
        val IN_PROGRESS = ProgressStatus(Known.IN_PROGRESS.wire)
        val COMPLETED = ProgressStatus(Known.COMPLETED.wire)
    }
}
