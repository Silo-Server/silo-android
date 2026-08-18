package org.siloserver.silo.common.ui.components

import org.siloserver.silo.model.profile.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileAvatarSupportTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/common/ui/components/ProfileAvatarSupport.kt",
    ).readText()

    private val uploadRef =
        "upload:profile-avatars/1/8bf465bc-3a0b-4cca-87b9-4a1473890be6/original.webp"
    private val uploadObject =
        "https://r2.example.test/silos3private/silo/dev/profile-avatars/1/" +
            "8bf465bc-3a0b-4cca-87b9-4a1473890be6/w256.webp"

    private fun signedUploadUrl(signature: String) =
        "$uploadObject?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=900&X-Amz-Signature=$signature"

    // --- existing forms must keep working ------------------------------------

    @Test
    fun absoluteAvatarUrlsAreReturnedUnchanged() {
        assertEquals(
            "https://cdn.example.test/avatar.webp",
            resolveAvatarUrl("https://silo.example", "https://cdn.example.test/avatar.webp"),
        )
    }

    @Test
    fun relativeAvatarPathsResolveAgainstServerUrl() {
        assertEquals(
            "https://silo.example/api/v1/users/1/avatar.png",
            resolveAvatarUrl("https://silo.example/", "/api/v1/users/1/avatar.png"),
        )
    }

    @Test
    fun nonImageAvatarTextDoesNotBecomeAUrl() {
        assertNull(resolveAvatarUrl("https://silo.example", "JC"))
    }

    @Test
    fun diceBearPresetsStillResolveToTheDiceBearApi() {
        // Uri.encode is stubbed under plain unit tests, so assert the routing
        // rather than the fully-encoded query.
        val resolved = resolveProfileAvatar(
            "https://silo.example",
            ProfileAvatarRef("preset:dicebear:fun-emoji:cosmic-otter"),
        )
        assertTrue(
            resolved?.url?.startsWith("https://api.dicebear.com/9.x") == true,
            "DiceBear presets must still resolve against the DiceBear API",
        )
    }

    @Test
    fun emojiAndInitialsFallbacksAreUnchanged() {
        assertEquals("🦊", profileAvatarDisplayText(ProfileAvatarRef("🦊"), "Laura Chen"))
        assertEquals("LC", profileAvatarDisplayText(ProfileAvatarRef.None, "Laura Chen"))
        assertTrue(isEmojiAvatar(ProfileAvatarRef("🦊")))
        assertFalse(isEmojiAvatar(ProfileAvatarRef.None))
    }

    // --- uploaded avatars -----------------------------------------------------

    @Test
    fun uploadRefsCountAsImagesSoTheyNeverRenderAsText() {
        assertTrue(isUploadAvatarRef(uploadRef))
        assertTrue(isImageAvatar(uploadRef))
        // Otherwise the raw `upload:profile-avatars/…` string would be drawn
        // into the circle as if it were an emoji.
        assertEquals("LC", profileAvatarDisplayText(ProfileAvatarRef(uploadRef), "Laura Chen"))
        assertFalse(isEmojiAvatar(ProfileAvatarRef(uploadRef)))
    }

    @Test
    fun uploadRefUsesTheServerSuppliedUrl() {
        val signed = signedUploadUrl("abc123")
        val resolved = resolveProfileAvatar(
            "https://silo.example",
            ProfileAvatarRef(uploadRef, signed),
        )
        assertEquals(signed, resolved?.url)
    }

    @Test
    fun uploadRefWithoutAUrlResolvesToNullRatherThanAFabricatedServerPath() {
        // The regression: this used to produce
        // https://silo.example/upload:profile-avatars/… — a guaranteed 404.
        assertNull(resolveProfileAvatar("https://silo.example", ProfileAvatarRef(uploadRef)))
        assertNull(resolveAvatarUrl("https://silo.example", uploadRef))
    }

    @Test
    fun serverSuppliedUrlWinsOverAServerRelativePath() {
        val resolved = resolveProfileAvatar(
            "https://silo.example",
            ProfileAvatarRef("/api/v1/users/1/avatar.png", "https://cdn.example.test/a.webp"),
        )
        assertEquals("https://cdn.example.test/a.webp", resolved?.url)
    }

    @Test
    fun profileAvatarRefCarriesBothServerFields() {
        val profile = Profile(
            id = "p1",
            name = "Laura",
            avatar = uploadRef,
            avatarUrl = signedUploadUrl("abc123"),
            avatarSource = "upload",
        )
        assertEquals(ProfileAvatarRef(uploadRef, signedUploadUrl("abc123")), profile.avatarRef())
    }

    // --- cache-key stability across re-signing --------------------------------

    @Test
    fun resigningTheSameUploadKeepsOneStableCacheKey() {
        // The presigned URL is re-signed on every GET /profiles (15-minute
        // expiry), so keying the caches by the URL would re-download the same
        // bytes forever.
        val first = resolveProfileAvatar("", ProfileAvatarRef(uploadRef, signedUploadUrl("sigA")))
        val second = resolveProfileAvatar("", ProfileAvatarRef(uploadRef, signedUploadUrl("sigB")))

        assertEquals(uploadObject, first?.cacheKey)
        assertEquals(first?.cacheKey, second?.cacheKey)
        // ...while the URLs themselves genuinely differ.
        assertTrue(first?.url != second?.url)
    }

    @Test
    fun differentUploadsDoNotShareACacheKey() {
        val other = "upload:profile-avatars/1/11111111-2222-3333-4444-555555555555/original.webp"
        val otherUrl = "https://r2.example.test/silos3private/silo/dev/profile-avatars/1/" +
            "11111111-2222-3333-4444-555555555555/w256.webp?X-Amz-Signature=zzz"

        val a = resolveProfileAvatar("", ProfileAvatarRef(uploadRef, signedUploadUrl("sigA")))
        val b = resolveProfileAvatar("", ProfileAvatarRef(other, otherUrl))
        assertTrue(a?.cacheKey != b?.cacheKey)
    }

    @Test
    fun nonUploadUrlsKeepTheirQueryInTheCacheKey() {
        // DiceBear encodes the seed in the query. Stripping it would collapse
        // every preset avatar onto a single cache entry, so these must opt out
        // of the override entirely and let Coil key by URL.
        val resolved = resolveProfileAvatar(
            "",
            ProfileAvatarRef(
                "preset:dicebear:fun-emoji:cosmic-otter",
                "https://api.dicebear.com/9.x/fun-emoji/png?seed=cosmic-otter&size=256",
            ),
        )
        assertNull(resolved?.cacheKey)
    }

    @Test
    fun cacheKeyToleratesAnUnsignedUrl() {
        val resolved = resolveProfileAvatar("", ProfileAvatarRef(uploadRef, uploadObject))
        assertEquals(uploadObject, resolved?.cacheKey)
    }

    @Test
    fun rememberProfileServerUrlUsesServerRegistryInsteadOfLegacyPrefs() {
        assertTrue(
            source.contains("ServerRegistry"),
            "Avatar helpers should use the active server registry entry",
        )
        assertFalse(
            source.contains("silo_auth"),
            "Avatar helpers must not depend on the legacy plaintext auth preferences",
        )
    }
}
