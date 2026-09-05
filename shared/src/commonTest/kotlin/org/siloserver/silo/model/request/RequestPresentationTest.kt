package org.siloserver.silo.model.request

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestPresentationTest {

    @Test
    fun `poster url builds tmdb w500 path for relative paths`() {
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", requestPosterUrl("/poster.jpg"))
    }

    @Test
    fun `backdrop url builds tmdb w780 path for relative paths`() {
        assertEquals("https://image.tmdb.org/t/p/w780/backdrop.jpg", requestBackdropUrl("/backdrop.jpg"))
    }

    @Test
    fun `image urls pass through absolute urls and reject blanks`() {
        assertEquals("https://example.com/p.jpg", requestPosterUrl("https://example.com/p.jpg"))
        assertEquals("http://example.com/p.jpg", requestPosterUrl("http://example.com/p.jpg"))
        assertEquals("relative.jpg", requestPosterUrl("relative.jpg"))
        assertNull(requestPosterUrl(null))
        assertNull(requestPosterUrl("   "))
    }

    @Test
    fun `badge status prefers availability then status then requestable then reason`() {
        assertEquals(RequestAvailability.Available, result(availability = RequestAvailability.Available).badgeStatus())
        assertEquals(RequestStatus.Pending, result(requestStatus = RequestStatus.Pending).badgeStatus())
        assertEquals("request", result(requestable = true).badgeStatus())
        assertEquals("not allowed", result(reason = "not allowed").badgeStatus())
        assertEquals(RequestAvailability.Missing, result().badgeStatus())
    }

    @Test
    fun `display label maps known tokens and title-cases the rest`() {
        assertEquals("In Library", RequestAvailability.Available.requestDisplayLabel())
        assertEquals("Missing", RequestAvailability.Missing.requestDisplayLabel())
        assertEquals("Request", "request".requestDisplayLabel())
        assertEquals("Movie", RequestMediaType.Movie.requestDisplayLabel())
        assertEquals("Series", RequestMediaType.Series.requestDisplayLabel())
        assertEquals("All", RequestMediaType.All.requestDisplayLabel())
        assertEquals("Pending", RequestStatus.Pending.requestDisplayLabel())
        assertEquals("Partially Available", "partially_available".requestDisplayLabel())
    }

    @Test
    fun `can cancel only while active and pending`() {
        assertTrue(request(status = RequestStatus.Pending, outcome = RequestOutcome.Active).canCancel())
        assertFalse(request(status = RequestStatus.Downloading, outcome = RequestOutcome.Active).canCancel())
        assertFalse(request(status = RequestStatus.Pending, outcome = RequestOutcome.Cancelled).canCancel())
    }

    @Test
    fun `target summary joins fields and truncates with ellipsis after two targets`() {
        assertNull(request().targetSummary())

        val summary = request(
            targets = listOf(
                target(id = 1, instanceName = "Radarr 4K", quality = "2160p", status = "queued"),
                target(id = 2, instanceName = "Radarr", quality = "1080p", status = "queued"),
                target(id = 3, instanceName = "Backup", quality = "720p", status = "queued"),
            ),
        ).targetSummary()

        assertEquals("Radarr 4K • 2160p • queued, Radarr • 1080p • queued, …", summary)
    }

    private fun result(
        availability: String = RequestAvailability.Missing,
        requestStatus: String? = null,
        requestable: Boolean = false,
        reason: String = "",
    ): RequestMediaResult = RequestMediaResult(
        mediaType = RequestMediaType.Movie,
        tmdbId = 1,
        title = "Stub",
        availability = availability,
        request = RequestState(status = requestStatus, requestable = requestable, reason = reason),
    )

    private fun request(
        status: String = RequestStatus.Pending,
        outcome: String = RequestOutcome.Active,
        targets: List<RequestTarget> = emptyList(),
    ): MediaRequest = MediaRequest(
        id = "request-1",
        mediaType = RequestMediaType.Movie,
        tmdbId = 1,
        title = "Stub",
        status = status,
        outcome = outcome,
        targets = targets,
        createdAt = "2026-06-12T00:00:00Z",
        updatedAt = "2026-06-12T00:00:00Z",
    )

    private fun target(
        id: Long,
        instanceName: String,
        quality: String,
        status: String,
    ): RequestTarget = RequestTarget(
        id = id.toString(),
        requestId = "request-1",
        instanceName = instanceName,
        quality = quality,
        status = status,
        createdAt = "2026-06-12T00:00:00Z",
        updatedAt = "2026-06-12T00:00:00Z",
    )
}
