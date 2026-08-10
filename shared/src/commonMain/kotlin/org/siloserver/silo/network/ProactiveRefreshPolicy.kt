package org.siloserver.silo.network

/**
 * Whether a token with [remainingMs] left should be refreshed before it is
 * spent, for a caller wanting [marginMs] of headroom, given the token's total
 * [lifetimeMs].
 *
 * The margin is clamped to half the token's own lifetime. A server issuing
 * 30-second access tokens against a 60-second margin would otherwise be inside
 * the window from the instant it was issued: every request would refresh, and
 * every refresh rotates the refresh token — a storm that invites rate limiting
 * and turns one transient rejection into a signed-out session. Clamping makes
 * such a token refresh at its half-life instead, which is still proactive but
 * once per token rather than once per request.
 *
 * A null or non-positive [lifetimeMs] means the issuer never told us how long
 * the token was meant to live, so the caller's margin stands unmodified.
 */
internal fun shouldRefreshProactively(
    remainingMs: Long,
    lifetimeMs: Long?,
    marginMs: Long,
): Boolean {
    val margin = marginMs.coerceAtLeast(0)
    val effectiveMargin = if (lifetimeMs != null && lifetimeMs > 0) {
        minOf(margin, lifetimeMs / 2)
    } else {
        margin
    }
    return remainingMs <= effectiveMargin
}
