package org.siloserver.silo.common.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadSubscriptionScopeTest {

    @Test
    fun explicitInputScopeWinsOverActiveScope() {
        assertEquals(
            Triple("srv-1", "acct-1", "prof-1"),
            downloadSubscriptionScope(
                inputServerId = "srv-1",
                inputAccountId = "acct-1",
                inputProfileId = "prof-1",
                activeServerId = "srv-2",
                activeAccountId = "acct-2",
                activeProfileId = "prof-2",
            ),
        )
    }

    @Test
    fun periodicRunWithoutInputUsesActiveScope() {
        assertEquals(
            Triple("srv-2", "acct-2", "prof-2"),
            downloadSubscriptionScope(
                inputServerId = null,
                inputAccountId = null,
                inputProfileId = null,
                activeServerId = "srv-2",
                activeAccountId = "acct-2",
                activeProfileId = "prof-2",
            ),
        )
    }

    @Test
    fun missingScopesFallBackToDownloadDefaults() {
        assertEquals(
            Triple(DownloadEnqueuer.DEFAULT_SERVER_ID, "", DownloadEnqueuer.DEFAULT_PROFILE_ID),
            downloadSubscriptionScope(
                inputServerId = null,
                inputAccountId = null,
                inputProfileId = null,
                activeServerId = null,
                activeAccountId = null,
                activeProfileId = null,
            ),
        )
    }

    @Test
    fun partialInputScopeIsIgnoredAsAWhole() {
        // A one-shot enqueue always sets both keys; a lone value is stale
        // data and must not mix scopes (e.g. input server + active profile).
        assertEquals(
            Triple("srv-2", "acct-2", "prof-2"),
            downloadSubscriptionScope(
                inputServerId = "srv-1",
                inputAccountId = "acct-1",
                inputProfileId = null,
                activeServerId = "srv-2",
                activeAccountId = "acct-2",
                activeProfileId = "prof-2",
            ),
        )
    }
}
