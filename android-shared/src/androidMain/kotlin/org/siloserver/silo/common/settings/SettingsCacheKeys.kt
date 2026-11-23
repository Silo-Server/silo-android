package org.siloserver.silo.common.settings

import org.siloserver.silo.common.store.AccountScopedKeys

/**
 * SharedPreferences key prefix for one identity's cached settings answer:
 * [tag] plus the first 24 hex digits of the identity's SHA-256, so server URLs
 * and profile ids never appear in key names. An identity naming an account
 * ([AccountScopedKeys.identity]) keeps its account token readable after the
 * tag (`<tag>@<account>.<hash>`), so signing out of the account can find and
 * delete its keys.
 */
internal fun settingsCachePrefix(tag: String, identity: String): String {
    val account = AccountScopedKeys.accountIdsIn(identity).firstOrNull()
        ?.let { AccountScopedKeys.token(it) + "." }
        .orEmpty()
    return tag + account + sha256Hex(identity).take(24)
}

private fun sha256Hex(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }
