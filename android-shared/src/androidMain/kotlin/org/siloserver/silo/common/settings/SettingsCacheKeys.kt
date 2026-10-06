package org.siloserver.silo.common.settings

/**
 * SharedPreferences key prefix for one identity's cached settings answer:
 * [tag] plus the first 24 hex digits of the identity's SHA-256, so server URLs
 * and profile ids never appear in key names.
 */
internal fun settingsCachePrefix(tag: String, identity: String): String =
    tag + sha256Hex(identity).take(24)

private fun sha256Hex(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }
