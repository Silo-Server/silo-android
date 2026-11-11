package org.siloserver.silo.model.server

import kotlinx.serialization.Serializable

/**
 * One saved login on one saved server.
 *
 * [localAccountId] is a UUID made on this device when the account is saved and
 * never changes, so every local key (credential slots, Room rows, preference
 * keys, download paths) uses it rather than the server's user id. The server's
 * id, [serverUserId], is only an attribute for finding duplicates: it is set at
 * sign-in, and filled in by the first verified `/me` for accounts migrated
 * from the one-account-per-server layout, which never stored it.
 *
 * Mirrors silo-apple's `SavedSignIn` (docs/superpowers/specs
 * 2026-11-02-multi-account-design.md §5).
 */
@Serializable
data class AccountEntry(
    val localAccountId: String,
    val serverId: String,
    val serverUserId: String? = null,
    val username: String? = null,
    /** The profile this account last used; restored when the account is chosen again. */
    val profileId: String? = null,
    /** The account's profiles as last fetched, so the picker renders offline. */
    val cachedProfiles: List<CachedProfile> = emptyList(),
    /**
     * How many profiles the account may have, as its server last said
     * (`max_profiles` on the profile list). Null when it did not say: an older
     * server, or one that could not read the limit.
     */
    val maxProfiles: Int? = null,
    val addedAtEpochMs: Long = 0L,
    val lastUsedAtEpochMs: Long = 0L,
    /**
     * The server refused this account's credentials (expired or revoked). Not
     * a sign-out: the account and its downloads stay until someone signs in
     * again or signs out.
     */
    val needsSignIn: Boolean = false,
    /**
     * This account was made by migrating its server's one-account session, so
     * local data written before accounts existed (rows and files with no
     * account) belongs to it. Adoption passes are idempotent and key off this.
     */
    val adoptsLegacyData: Boolean = false,
)

/** The picker's offline copy of one profile. */
@Serializable
data class CachedProfile(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val avatarUrl: String? = null,
    val isPrimary: Boolean = false,
    val hasPin: Boolean = false,
    val isChild: Boolean = false,
)
