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
    fun `cards open the library only when their status reads in library`() {
        val inLibrary = result(availability = RequestAvailability.Available).copy(libraryContentId = "item-1")
        assertEquals("item-1", inLibrary.libraryItemToOpen())
        // A series in the library with missing seasons on the way opens the request.
        val seasonsComing = inLibrary.copy(request = RequestState(status = RequestStatus.Downloading, state = RequestUserState.Approved))
        assertNull(seasonsComing.libraryItemToOpen())
        val landed = request(status = RequestStatus.Completed).copy(state = RequestUserState.Available, libraryContentId = "item-2")
        assertEquals("item-2", landed.libraryItemToOpen())
        assertNull(landed.copy(state = RequestUserState.Processing).libraryItemToOpen())
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
}
