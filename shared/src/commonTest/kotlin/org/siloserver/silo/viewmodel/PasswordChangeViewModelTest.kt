package org.siloserver.silo.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.model.auth.PasswordRequirements
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.WRONG_CURRENT_PASSWORD
import androidx.lifecycle.viewModelScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.common.i18n.SharedPlural
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.model.auth.PasswordChangeError

/**
 * silo-android#663: an account holding a temporary password signed in, then
 * saw "Failed to load profiles" with no way forward. The app now replaces the
 * password, refreshes the session so the server lifts its restriction, and
 * only then continues to profiles.
 */
class PasswordChangeViewModelTest {

    private class FakeAccount(
        /** Each read takes the next answer; the last one repeats. Null = unreadable. */
        var reads: MutableList<Boolean?> = mutableListOf(true),
        var change: ApiResult<Unit> = ApiResult.Success(Unit),
        var refreshes: MutableList<Boolean> = mutableListOf(true),
        /** Thrown by the change instead of answering. */
        var changeThrows: Exception? = null,
        /** Thrown by the refresh instead of answering. */
        var refreshThrows: Exception? = null,
    ) : TemporaryPasswordAccount {
        val calls = mutableListOf<String>()

        override suspend fun readAccount(): ApiResult<User> {
            calls += "read"
            val required = if (reads.size > 1) reads.removeAt(0) else reads.single()
            return if (required == null) {
                ApiResult.NetworkError(RuntimeException("offline"))
            } else {
                ApiResult.Success(User("1", "member", "m@example.test", "user", passwordChangeRequired = required))
            }
        }

        override suspend fun passwordRequirements() = PasswordRequirements(minimumLength = 10, maximumBytes = 72)

        override suspend fun changePassword(current: String, new: String): ApiResult<Unit> {
            calls += "change"
            changeThrows?.let { throw it }
            return change
        }

        override suspend fun refreshSessionAfterPasswordChange(): Boolean {
            calls += "refresh"
            refreshThrows?.let { throw it }
            return if (refreshes.size > 1) refreshes.removeAt(0) else refreshes.single()
        }
    }

    private suspend fun TestScope.withModel(account: FakeAccount, block: suspend (PasswordChangeViewModel) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val model = PasswordChangeViewModel(account)
        try {
            block(model)
        } finally {
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private fun PasswordChangeViewModel.fill(new: String = "a-new-password", confirmation: String = new) {
        onCurrentPasswordChanged("temporary")
        onNewPasswordChanged(new)
        onConfirmationChanged(confirmation)
    }

    @Test
    fun loadKeepsTheFormWhileThePasswordIsTemporary() = runTest {
        val account = FakeAccount()
        withModel(account) { model ->
            val state = model.uiState.value
            assertEquals(PasswordChangePhase.Editing, state.phase)
            assertEquals("member", state.accountName)
            assertEquals(UiText.Plural(SharedPlural.PasswordRequirement, 10), state.requirementsHint)
            assertEquals(listOf("read"), account.calls)
        }
    }

    /**
     * The password changed but the refresh never finished, and the app
     * relaunched. The temporary password no longer works, so the screen only
     * refreshes the session.
     */
    @Test
    fun anAlreadyChangedPasswordOnlyRefreshesTheSession() = runTest {
        val account = FakeAccount(reads = mutableListOf(false))
        withModel(account) { model ->
            assertEquals(listOf("read", "refresh"), account.calls)
            assertTrue(model.uiState.value.finished)
        }
    }

    @Test
    fun savingChangesThePasswordThenRefreshesBeforeContinuing() = runTest {
        val account = FakeAccount()
        withModel(account) { model ->
            model.fill()
            model.submit()
            assertEquals(listOf("read", "change", "refresh"), account.calls)
            val state = model.uiState.value
            assertTrue(state.finished)
            assertEquals(listOf("", "", ""), listOf(state.currentPassword, state.newPassword, state.confirmation))
        }
    }

    /** Saved, but the refresh failed: the form stays closed and Try again only refreshes. */
    @Test
    fun aFailedRefreshOffersARetryThatDoesNotResend() = runTest {
        val account = FakeAccount(refreshes = mutableListOf(false, true))
        withModel(account) { model ->
            model.fill()
            model.submit()
            assertEquals(PasswordChangePhase.RefreshFailed(retrying = false), model.uiState.value.phase)
            assertFalse(model.uiState.value.finished)

            model.submit()
            model.refreshAgain()

            assertEquals(listOf("read", "change", "refresh", "refresh"), account.calls)
            assertTrue(model.uiState.value.finished)
        }
    }

    @Test
    fun aWrongTemporaryPasswordStaysOnTheFormWithoutRefreshing() = runTest {
        val account = FakeAccount(change = ApiResult.Error(422, WRONG_CURRENT_PASSWORD, "incorrect"))
        withModel(account) { model ->
            model.fill()
            model.submit()
            val state = model.uiState.value
            assertEquals(PasswordChangePhase.Editing, state.phase)
            assertEquals(PasswordChangeError.WrongTemporary, state.error)
            assertEquals(listOf("read", "change"), account.calls, "a refusal needs no account check")
        }
    }

    /** The server saved the password but the answer was lost: sign-in continues. */
    @Test
    fun aLostAnswerAfterTheChangeLandedContinuesSignIn() = runTest {
        val account = FakeAccount(
            reads = mutableListOf(true, false),
            change = ApiResult.NetworkError(RuntimeException("connection lost")),
        )
        withModel(account) { model ->
            model.fill()
            model.submit()
            assertEquals(listOf("read", "change", "read", "refresh"), account.calls)
            val state = model.uiState.value
            assertTrue(state.finished)
            assertNull(state.error)
            assertEquals("", state.newPassword)
        }
    }

    /** Nobody knows whether it changed: the form stays closed and Try again re-reads, never resends. */
    @Test
    fun anUnconfirmedChangeChecksTheAccountInsteadOfResending() = runTest {
        val account = FakeAccount(
            reads = mutableListOf(true, null, false),
            change = ApiResult.NetworkError(RuntimeException("connection lost")),
        )
        withModel(account) { model ->
            model.fill()
            model.submit()
            assertEquals(PasswordChangePhase.Unconfirmed(checking = false), model.uiState.value.phase)
            model.submit()
            assertEquals(1, account.calls.count { it == "change" })

            model.recheck()

            assertEquals(1, account.calls.count { it == "change" })
            assertEquals(listOf("read", "change", "read", "read", "refresh"), account.calls)
            assertTrue(model.uiState.value.finished)
        }
    }

    @Test
    fun anUnconfirmedChangeReopensTheFormWhenThePasswordIsStillTemporary() = runTest {
        val account = FakeAccount(
            reads = mutableListOf(true, null, true),
            change = ApiResult.NetworkError(RuntimeException("connection lost")),
        )
        withModel(account) { model ->
            model.fill()
            model.submit()
            model.recheck()
            val state = model.uiState.value
            assertEquals(PasswordChangePhase.Editing, state.phase)
            assertEquals(PasswordChangeError.NotChanged, state.error)
            assertEquals("a-new-password", state.newPassword, "the form keeps what was typed")
            assertFalse("refresh" in account.calls)
        }
    }

    /** A server that doesn't report the flag counts as still holding the temporary password. */
    @Test
    fun anOlderServerReopensTheFormAfterAFailedChange() = runTest {
        val account = FakeAccount(change = ApiResult.Error(503, "service_unavailable", "Busy."))
        val olderServer = object : TemporaryPasswordAccount by account {
            override suspend fun readAccount(): ApiResult<User> =
                ApiResult.Success(User("1", "member", "m@example.test", "user", passwordChangeRequired = null))
        }
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val model = PasswordChangeViewModel(olderServer)
        try {
            model.fill()
            model.submit()
            assertEquals(PasswordChangePhase.Editing, model.uiState.value.phase)
            assertEquals(PasswordChangeError.Failed(UiText.Raw("Busy.")), model.uiState.value.error)
        } finally {
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    /** The server answered and refused, so nothing changed: the form keeps the refusal even when the account can't be read. */
    @Test
    fun aDefiniteRefusalStaysOnTheFormWithoutCheckingTheAccount() = runTest {
        for (refusal in listOf(
            ApiResult.Error(422, WRONG_CURRENT_PASSWORD, "incorrect"),
            ApiResult.Error(429, "rate_limited", "slow down"),
        )) {
            val account = FakeAccount(reads = mutableListOf(true, null), change = refusal)
            withModel(account) { model ->
                model.fill()
                model.submit()
                assertEquals(PasswordChangePhase.Editing, model.uiState.value.phase, refusal.error)
                assertEquals(listOf("read", "change"), account.calls, refusal.error)
            }
        }
    }

    /** A change that throws is an unknown outcome: the account says what happened. */
    @Test
    fun aChangeThatThrowsIsCheckedLikeALostAnswer() = runTest {
        val account = FakeAccount(reads = mutableListOf(true, false), changeThrows = IllegalStateException("keystore"))
        withModel(account) { model ->
            model.fill()
            model.submit()
            assertEquals(listOf("read", "change", "read", "refresh"), account.calls)
            assertTrue(model.uiState.value.finished)
        }
    }

    @Test
    fun aRefreshThatThrowsOffersTryAgain() = runTest {
        val account = FakeAccount(refreshThrows = IllegalStateException("keystore"))
        withModel(account) { model ->
            model.fill()
            model.submit()
            assertEquals(PasswordChangePhase.RefreshFailed(retrying = false), model.uiState.value.phase)
        }
    }

    @Test
    fun theFormChecksItsFieldsBeforeSpendingTheBudget() = runTest {
        val account = FakeAccount()
        withModel(account) { model ->
            model.onNewPasswordChanged("a-new-password")
            model.onConfirmationChanged("a-new-password")
            model.submit()
            assertEquals(PasswordChangeError.EmptyTemporary, model.uiState.value.error)
            model.fill(new = "too-short")
            model.submit()
            assertEquals(PasswordChangeError.TooShort(10), model.uiState.value.error)
            assertFalse("change" in account.calls)
        }
    }
}
