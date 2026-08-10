package org.siloserver.silo.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half-life clamp. A fixed margin against a token shorter than the margin
 * puts every request inside the refresh window, and every refresh rotates the
 * refresh token.
 */
class ProactiveRefreshPolicyTest {

    private val margin = 60_000L

    @Test
    fun refreshesInsideTheMarginForANormalLifetime() {
        assertTrue(
            shouldRefreshProactively(
                remainingMs = 30_000,
                lifetimeMs = 3_600_000,
                marginMs = margin,
            ),
        )
    }

    @Test
    fun leavesAHealthyTokenAlone() {
        assertFalse(
            shouldRefreshProactively(
                remainingMs = 3_000_000,
                lifetimeMs = 3_600_000,
                marginMs = margin,
            ),
        )
    }

    @Test
    fun aFreshlyIssuedShortTokenIsNotAlreadyDue() {
        // expires_in=30 against a 60s margin: without the clamp this is true
        // the instant the server issues it, so every request refreshes and
        // every refresh rotates - a storm.
        assertFalse(
            shouldRefreshProactively(
                remainingMs = 30_000,
                lifetimeMs = 30_000,
                marginMs = margin,
            ),
            "a token that has not been spent yet must not already be due",
        )
    }

    @Test
    fun aShortTokenStillRefreshesAtItsHalfLife() {
        assertTrue(
            shouldRefreshProactively(
                remainingMs = 15_000,
                lifetimeMs = 30_000,
                marginMs = margin,
            ),
            "clamping must not disable proactive refresh, only delay it to half-life",
        )
    }

    @Test
    fun anUnknownLifetimeKeepsTheCallersMargin() {
        assertTrue(shouldRefreshProactively(30_000, lifetimeMs = null, marginMs = margin))
        assertFalse(shouldRefreshProactively(90_000, lifetimeMs = null, marginMs = margin))
    }

    @Test
    fun anExpiredTokenIsAlwaysDue() {
        assertTrue(shouldRefreshProactively(-5_000, lifetimeMs = 30_000, marginMs = margin))
        assertTrue(shouldRefreshProactively(0, lifetimeMs = 30_000, marginMs = margin))
    }

    @Test
    fun aNonPositiveLifetimeIsTreatedAsUnknownRatherThanClampingToZero() {
        assertTrue(
            shouldRefreshProactively(30_000, lifetimeMs = 0, marginMs = margin),
            "a zero lifetime is the server telling us nothing useful, not a zero margin",
        )
    }
}
