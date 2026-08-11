package org.siloserver.silo.network

/**
 * Whether a token with [remainingMs] left should be refreshed before it is
 * spent, for a caller wanting [marginMs] of headroom, given the token's total
 * [lifetimeMs].
 *
 * Two rules, both of them about not refreshing more often than the token is
 * actually worth:
 *
 * - The margin is clamped to half the token's own lifetime. A server issuing
 *   30-second access tokens against a 60-second margin would otherwise be
 *   inside the window from the instant it issued one: every request would
 *   refresh, and every refresh rotates the refresh token — a storm that
 *   invites rate limiting and turns one transient rejection into a signed-out
 *   session. Clamped, such a token refreshes at its half-life instead.
 *
 * - A null [lifetimeMs] means we do not know what the issuer intended, which
 *   is the state of any credential stored before this field existed. Guessing
 *   with the caller's full margin is how the storm above happens, so an
 *   unknown lifetime keeps the old purely reactive behaviour until the next
 *   successful token issuance records one.
 *
 * An already-expired token is always due regardless: there is nothing left to
 * conserve, and refreshing first is strictly cheaper than the 401 that would
 * otherwise follow.
 */
internal fun shouldRefreshProactively(
    remainingMs: Long,
    lifetimeMs: Long?,
    marginMs: Long,
): Boolean {
    if (remainingMs <= 0) return true
    val lifetime = lifetimeMs ?: return false
    if (lifetime <= 0) return false
    val margin = marginMs.coerceAtLeast(0)
    return remainingMs <= minOf(margin, lifetime / 2)
}
