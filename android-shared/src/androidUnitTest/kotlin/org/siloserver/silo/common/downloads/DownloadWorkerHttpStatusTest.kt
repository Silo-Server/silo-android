package org.siloserver.silo.common.downloads

import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.siloserver.silo.network.SiloAuthUnavailableException

class DownloadWorkerHttpStatusTest {
    @Test
    fun `successful download status has no failure`() {
        assertNull(downloadHttpStatusFailure(HttpStatusCode.OK))
        assertNull(downloadHttpStatusFailure(HttpStatusCode.PartialContent))
    }

    @Test
    fun `client error download status is permanent failure`() {
        assertIs<IllegalStateException>(downloadHttpStatusFailure(HttpStatusCode.NotFound))
        assertIs<IllegalStateException>(downloadHttpStatusFailure(HttpStatusCode.Forbidden))
        assertIs<IllegalStateException>(downloadHttpStatusFailure(HttpStatusCode.Gone))
    }

    @Test
    fun `transient client statuses are retryable io failures`() {
        // Matches SyncEngine's transient classification: 401/408/429.
        assertIs<IOException>(downloadHttpStatusFailure(HttpStatusCode.Unauthorized))
        assertIs<IOException>(downloadHttpStatusFailure(HttpStatusCode.RequestTimeout))
        assertIs<IOException>(downloadHttpStatusFailure(HttpStatusCode.TooManyRequests))
    }

    @Test
    fun `server error download status is retryable io failure`() {
        assertIs<IOException>(downloadHttpStatusFailure(HttpStatusCode.ServiceUnavailable))
    }

    // A 409 never reaches downloadHttpStatusFailure: the worker intercepts it
    // first as "not ready yet" (bounded preparing retry), because the v2 file
    // route sends one bare conflict problem for every non-active state.
    @Test
    fun `a conflict that escapes the preparing intercept is a permanent failure`() {
        assertIs<IllegalStateException>(downloadHttpStatusFailure(HttpStatusCode.Conflict))
    }
}

class DownloadWorkerAuthFailureTest {
    @Test
    fun `a repudiated session is retriable, not a permanent download failure`() {
        assertTrue(
            downloadAuthFailureIsRetriable(
                SiloAuthUnavailableException(SiloAuthUnavailableException.CREDENTIALS_REPUDIATED),
            ),
        )
        assertTrue(
            downloadAuthFailureIsRetriable(
                SiloAuthUnavailableException(SiloAuthUnavailableException.REQUIRED_AUTH_UNAVAILABLE),
            ),
        )
    }

    @Test
    fun `a genuine client error stays permanent`() {
        // SiloAuthUnavailableException extends IllegalStateException, which is
        // what downloadHttpStatusFailure returns for a 404. Widening the auth
        // predicate to that supertype would make every 404 retry forever.
        assertFalse(
            downloadAuthFailureIsRetriable(
                downloadHttpStatusFailure(HttpStatusCode.NotFound)!!,
            ),
        )
        assertFalse(downloadAuthFailureIsRetriable(IOException("socket closed")))
    }
}
