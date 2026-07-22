package org.siloserver.silo.common.diagnostics

import org.junit.Test
import org.siloserver.silo.network.templateApiPath
import kotlin.test.assertEquals

/**
 * Path templating for `cat=network` log lines: identifier-shaped segments must
 * be replaced so lines aggregate by endpoint and never carry content ids.
 */
class TemplateApiPathTest {

    @Test
    fun `numeric segment becomes id placeholder`() {
        assertEquals("/api/v1/items/{id}", templateApiPath("/api/v1/items/42"))
    }

    @Test
    fun `uuid segment becomes uuid placeholder`() {
        assertEquals(
            "/api/v1/items/{uuid}/children",
            templateApiPath("/api/v1/items/0f8fad5b-d9cb-469f-a165-70867728950e/children"),
        )
        // Uppercase hex is still a UUID.
        assertEquals(
            "/api/v1/items/{uuid}",
            templateApiPath("/api/v1/items/0F8FAD5B-D9CB-469F-A165-70867728950E"),
        )
    }

    @Test
    fun `long opaque id segment becomes id placeholder`() {
        assertEquals(
            "/api/v1/media/{id}/stream",
            templateApiPath("/api/v1/media/aB3xK9_qRstUv-42Zz11/stream"),
        )
        // Exactly 16 chars is the threshold.
        assertEquals("/api/v1/media/{id}", templateApiPath("/api/v1/media/abcdefgh12345678"))
    }

    @Test
    fun `short word segments are untouched`() {
        assertEquals("/api/v1/libraries", templateApiPath("/api/v1/libraries"))
        // 15-char alphanumeric segment is below the opaque-id threshold.
        assertEquals("/api/v1/abcdefgh1234567", templateApiPath("/api/v1/abcdefgh1234567"))
        assertEquals("/api/v1/search", templateApiPath("/api/v1/search"))
    }

    @Test
    fun `empty and root paths are safe`() {
        assertEquals("", templateApiPath(""))
        assertEquals("/", templateApiPath("/"))
    }
}
