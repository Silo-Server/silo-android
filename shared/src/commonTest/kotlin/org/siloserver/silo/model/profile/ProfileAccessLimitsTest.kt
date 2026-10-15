package org.siloserver.silo.model.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.network.api.toProfileUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileAccessLimitsTest {
    private val restrictedChild = ProfileAccessLimits(
        isChild = true,
        maxContentRating = "PG",
        libraryRestrictionsEnabled = true,
        allowedLibraryIds = setOf(3, 1),
    )

    /** The PATCH body the edit forms send. */
    private fun patch(limits: ProfileAccessLimits, loaded: ProfileAccessLimits, assignable: Set<Int>? = null): JsonObject =
        UpdateProfileRequest(name = "Kid").withAccessLimits(limits, loaded, assignable).toProfileUpdate().toJsonObject()

    @Test fun createSendsAChildsLimitsAndOmitsThemOtherwise() {
        val child = CreateProfileRequest("Kid").withAccessLimits(ProfileAccessLimits().withChild(true).withLibrary(3, true).withLibrary(1, true))
        assertEquals(true, child.isChild)
        assertEquals(DEFAULT_CHILD_CONTENT_RATING, child.maxContentRating)
        assertEquals(true, child.libraryRestrictionsEnabled)
        assertEquals(listOf(1, 3), child.allowedLibraryIds)

        val unrestricted = CreateProfileRequest("Kid").withAccessLimits(restrictedChild.withLibraryRestrictions(false))
        assertNull(unrestricted.libraryRestrictionsEnabled)
        assertNull(unrestricted.allowedLibraryIds)

        assertEquals(CreateProfileRequest("Adult"), CreateProfileRequest("Adult").withAccessLimits(ProfileAccessLimits()))
    }

    @Test fun editLeavesUnchangedLimitsAsStored() {
        // A rating from another client's list and a library this form can't
        // list (deleted, or Reading on TV) survive a name-only edit.
        val stored = restrictedChild.copy(maxContentRating = "12", allowedLibraryIds = setOf(1, 99))
        val body = patch(stored, stored, assignable = setOf(1))
        assertEquals(JsonPrimitive(true), body["is_child"])
        assertFalse("max_content_rating" in body)
        assertFalse("library_restrictions_enabled" in body)
        assertFalse("allowed_library_ids" in body)
    }

    @Test fun editSendsChangedLimitsWithoutUnassignableLibraries() {
        val body = patch(restrictedChild.copy(maxContentRating = "R").withLibrary(2, true), restrictedChild.copy(allowedLibraryIds = setOf(1, 3, 99)), assignable = setOf(1, 2, 3))
        assertEquals(JsonPrimitive("R"), body["max_content_rating"])
        assertFalse("library_restrictions_enabled" in body)
        assertEquals(JsonArray(listOf("1", "2", "3").map(::JsonPrimitive)), body["allowed_library_ids"])
    }

    @Test fun turningChildOffClearsTheLimits() {
        val body = patch(restrictedChild.withChild(false), restrictedChild)
        assertEquals(JsonPrimitive(false), body["is_child"])
        assertEquals(JsonNull, body["max_content_rating"])
        assertEquals(JsonPrimitive(false), body["library_restrictions_enabled"])
        assertEquals(JsonArray(emptyList()), body["allowed_library_ids"])
    }

    @Test fun onlyARestrictedChildNeedsALibrary() {
        assertTrue(ProfileAccessLimits().withChild(true).needsLibrary())
        // A non-child profile set elsewhere to allow no library isn't blocked
        // from an unrelated edit by controls the form hides.
        assertFalse(ProfileAccessLimits(libraryRestrictionsEnabled = true).needsLibrary())
    }

    @Test fun aChangedAllowlistNeedsALibraryTheSaveKeeps() {
        val stored = restrictedChild.copy(allowedLibraryIds = setOf(1, 99))
        // Unchecking the only library the account still has leaves a deleted
        // one, which the save would drop: the form must still ask.
        assertTrue(stored.withLibrary(1, false).needsLibrary(stored, assignable = setOf(1)))
        // Left as stored, the same allowlist isn't sent, so it isn't checked.
        assertFalse(stored.copy(allowedLibraryIds = setOf(99)).needsLibrary(stored.copy(allowedLibraryIds = setOf(99)), setOf(1)))
    }
}
