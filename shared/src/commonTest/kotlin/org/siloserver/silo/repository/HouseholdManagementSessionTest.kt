package org.siloserver.silo.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.profile.VerifyPinResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText

class HouseholdManagementSessionTest {
    private val primary = Profile(id = "1", name = "Parent", isPrimary = true, hasPin = true)
    private val stale = ApiResult.Error(403, "profile_verification_required", "")
    private val pins = mutableListOf<String>()
    private val session = HouseholdManagementSession(
        verifyPin = { _, pin ->
            pins += pin
            ApiResult.Success(
                if (pin == "1234") VerifyPinResponse(valid = true, profileToken = "fresh") else VerifyPinResponse(valid = false),
            )
        },
        captureScope = { null },
    )

    @Test fun staleTokenReasksPinAndRetriesOnce() = runTest {
        session.begin(primary, "old", null)
        val tokensSent = mutableListOf<String?>()
        val result = async {
            session.run<Unit> { manager ->
                tokensSent += manager?.profileToken
                if (manager?.profileToken == "old") stale else ApiResult.Success(Unit)
            }
        }
        runCurrent()
        assertEquals(primary, assertNotNull(session.reverify.value).profile)
        session.submitPin("0000")
        runCurrent()
        assertEquals(UiText.Key(SharedString.PinWrong), session.reverify.value?.error)
        session.submitPin("1234")
        runCurrent()
        assertIs<ApiResult.Success<Unit>>(result.await())
        assertEquals<List<String?>>(listOf("old", "fresh"), tokensSent)
        assertEquals(listOf("0000", "1234"), pins)
        assertEquals("fresh", session.manager?.profileToken)
        assertNull(session.reverify.value)
        assertTrue(session.isActive.value)
    }

    @Test fun beginVerifiedAsksForThePrimaryPinFirst() = runTest {
        val started = async { session.beginVerified(primary, null) }
        runCurrent()
        assertEquals(primary, assertNotNull(session.reverify.value).profile)
        assertNull(session.manager?.profileToken)
        session.submitPin("0000")
        runCurrent()
        assertEquals(UiText.Key(SharedString.PinWrong), session.reverify.value?.error)
        session.submitPin("1234")
        runCurrent()
        assertTrue(started.await())
        assertEquals("fresh", session.manager?.profileToken)
        assertNull(session.reverify.value)
    }

    @Test fun beginVerifiedCancelledLeavesNoSession() = runTest {
        val started = async { session.beginVerified(primary, null) }
        runCurrent()
        session.cancelReverify()
        runCurrent()
        assertFalse(started.await())
        assertFalse(session.isActive.value)
        assertNull(session.manager)
        assertTrue(pins.isEmpty())
    }

    @Test fun beginVerifiedWithoutAPinStartsAtOnce() = runTest {
        assertTrue(session.beginVerified(primary.copy(hasPin = false), null))
        assertNull(session.reverify.value)
        assertTrue(session.isActive.value)
    }

    @Test fun cancelEndsTheSessionWithoutRetrying() = runTest {
        session.begin(primary, "old", null)
        var calls = 0
        val result = async { session.run<Unit> { calls++; stale } }
        runCurrent()
        session.cancelReverify()
        runCurrent()
        assertEquals("profile_verification_required", assertIs<ApiResult.Error>(result.await()).error)
        assertEquals(1, calls)
        assertFalse(session.isActive.value)
        assertNull(session.manager)
        assertNull(session.reverify.value)
    }

    @Test fun cancelWhileCheckingDoesNotRenewOrRetry() = runTest {
        val answer = CompletableDeferred<ApiResult<VerifyPinResponse>>()
        val slow = HouseholdManagementSession(verifyPin = { _, _ -> answer.await() }, captureScope = { null })
        slow.begin(primary, "old", null)
        var calls = 0
        val result = async { slow.run<Unit> { calls++; stale } }
        runCurrent()
        slow.submitPin("1234")
        runCurrent()
        assertTrue(assertNotNull(slow.reverify.value).isVerifying)
        slow.cancelReverify()
        answer.complete(ApiResult.Success(VerifyPinResponse(valid = true, profileToken = "fresh")))
        runCurrent()
        assertIs<ApiResult.Error>(result.await())
        assertEquals(1, calls)
        assertFalse(slow.isActive.value)
        assertNull(slow.reverify.value)
    }

    @Test fun accountChangeDuringVerificationEndsTheSession() = runTest {
        val accountA = AuthScopeSnapshot(serverId = "s1", profileId = null, serverUrl = "https://a", profileToken = null)
        var current: AuthScopeSnapshot? = accountA
        val switching = HouseholdManagementSession(
            verifyPin = { _, _ ->
                current = accountA.copy(serverId = "s2", serverUrl = "https://b")
                ApiResult.Success(VerifyPinResponse(valid = true, profileToken = "fresh"))
            },
            captureScope = { current },
        )
        switching.begin(primary, "old", accountA)
        var calls = 0
        val result = async { switching.run<Unit> { calls++; stale } }
        runCurrent()
        switching.submitPin("1234")
        runCurrent()
        assertIs<ApiResult.Error>(result.await())
        assertEquals(1, calls)
        assertFalse(switching.isActive.value)
        assertNull(switching.reverify.value)
    }
}
