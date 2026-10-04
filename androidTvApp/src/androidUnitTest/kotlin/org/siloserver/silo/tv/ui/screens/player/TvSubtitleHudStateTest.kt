package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TvSubtitleHudStateTest {
    @Test
    fun `sync status resolves server tracks by ordinal and merged stored rows by download id`() {
        val sync = TvHudSubtitleSync(
            statusByServerIndex = mapOf(0 to "Synced −3.0 s"),
            statusByDownloadId = mapOf(9 to "Syncing… 40%"),
        )
        assertEquals("Synced −3.0 s", sync.statusFor(SubtitleIdentity.ServerSidecar(0)))
        assertEquals("Syncing… 40%", sync.statusFor(SubtitleIdentity.Downloaded(9, SubtitleMediaIdentity())))
        assertEquals(null, sync.statusFor(SubtitleIdentity.ServerSidecar(1)))
        assertEquals(null, sync.statusFor(SubtitleIdentity.Off))
    }

    @Test
    fun `pending HUD state keeps committed row checked and labels pending row Applying`() {
        val presentation = present(
            committed = sidecar(3),
            pending = sidecar(4),
            focusedId = id(sidecar(4)),
        )

        assertTrue(presentation.rows.single { it.identity == sidecar(3) }.checked)
        assertFalse(presentation.rows.single { it.identity == sidecar(4) }.checked)
        assertEquals("Applying…", presentation.rows.single { it.identity == sidecar(4) }.status)
    }

    @Test
    fun `transaction failure removes Applying and keeps committed row checked`() {
        val presentation = present(
            committed = sidecar(3),
            pending = null,
            focusedId = id(sidecar(4)),
        )

        assertTrue(presentation.rows.single { it.identity == sidecar(3) }.checked)
        assertFalse(presentation.rows.any { it.applying })
    }

    @Test
    fun `cancellation never flashes the pending row as committed`() {
        val applying = present(sidecar(3), sidecar(4), id(sidecar(4)))
        val cancelled = present(sidecar(3), null, applying.focusedStableId)

        assertFalse(applying.rows.single { it.identity == sidecar(4) }.checked)
        assertFalse(cancelled.rows.single { it.identity == sidecar(4) }.checked)
        assertTrue(cancelled.rows.single { it.identity == sidecar(3) }.checked)
    }

    @Test
    fun `stale completion cannot change HUD selection`() {
        val afterNewerIntent = present(sidecar(3), sidecar(5), id(sidecar(5)))
        val afterStaleCompletion = present(sidecar(3), sidecar(5), afterNewerIntent.focusedStableId)

        assertTrue(afterStaleCompletion.rows.single { it.identity == sidecar(3) }.checked)
        assertTrue(afterStaleCompletion.rows.single { it.identity == sidecar(5) }.applying)
        assertFalse(afterStaleCompletion.rows.single { it.identity == sidecar(4) }.checked)
    }

    @Test
    fun `content reset removes prior pending HUD state`() {
        val reset = present(
            committed = SubtitleIdentity.Off,
            pending = null,
            focusedId = id(sidecar(4)),
        )

        assertTrue(reset.rows.single { it.identity == SubtitleIdentity.Off }.checked)
        assertFalse(reset.rows.any { it.applying })
    }

    @Test
    fun `exit clears pending HUD state`() {
        val presentation = buildTvSubtitleHudPresentation(
            options = options(),
            committedIdentity = sidecar(3),
            pendingIdentity = null,
            hudOpen = false,
            focusedStableId = id(sidecar(4)),
        )

        assertFalse(presentation.hudOpen)
        assertFalse(presentation.rows.any { it.applying })
    }

    @Test
    fun `selecting a catalog row while HUD is open keeps the HUD open`() {
        val presentation = buildTvSubtitleHudPresentation(
            options = options(),
            committedIdentity = sidecar(3),
            pendingIdentity = sidecar(4),
            hudOpen = true,
            focusedStableId = id(sidecar(4)),
        )

        assertTrue(presentation.hudOpen)
        assertTrue(presentation.rows.single { it.identity == sidecar(4) }.applying)
    }

    @Test
    fun `picker focus is independent from the checked committed row`() {
        val presentation = present(sidecar(3), null, id(sidecar(5)))

        assertTrue(presentation.rows.single { it.identity == sidecar(3) }.checked)
        assertTrue(presentation.rows.single { it.identity == sidecar(5) }.focused)
        assertFalse(presentation.rows.single { it.identity == sidecar(5) }.checked)
    }

    @Test
    fun `failed selection keeps focus on the activated pending row`() {
        val pending = present(sidecar(3), sidecar(4), id(sidecar(4)))
        val failed = present(sidecar(3), null, pending.focusedStableId)

        assertEquals(id(sidecar(4)), failed.focusedStableId)
        assertTrue(failed.rows.single { it.identity == sidecar(4) }.focused)
    }

    @Test
    fun `cancelled selection keeps the picker focus trap active`() {
        val cancelled = buildTvSubtitleHudPresentation(
            options = options(),
            committedIdentity = sidecar(3),
            pendingIdentity = null,
            hudOpen = true,
            focusedStableId = id(sidecar(4)),
        )

        assertTrue(cancelled.focusTrapActive)
        assertEquals(id(sidecar(4)), cancelled.focusedStableId)
    }

    @Test
    fun `stale completion cannot move focus`() {
        val before = present(sidecar(3), sidecar(5), id(sidecar(5)))
        val after = present(sidecar(3), sidecar(5), before.focusedStableId)

        assertEquals(id(sidecar(5)), after.focusedStableId)
    }

    @Test
    fun `refresh and remount preserve the focused stable option`() {
        val before = present(sidecar(3), sidecar(4), id(sidecar(4)))
        val refreshed = buildTvSubtitleHudPresentation(
            options = listOf(
                option(SubtitleIdentity.Off, "Off"),
                option(sidecar(5), "Spanish"),
                option(sidecar(4), "English"),
                option(sidecar(3), "French"),
            ),
            committedIdentity = sidecar(3),
            pendingIdentity = sidecar(4),
            hudOpen = true,
            focusedStableId = before.focusedStableId,
        )

        assertEquals(id(sidecar(4)), refreshed.focusedStableId)
        assertTrue(refreshed.rows.single { it.identity == sidecar(4) }.focused)
    }

    @Test
    fun `duplicate labels use typed option IDs and never collapse focus targets`() {
        val forced = SubtitleIdentity.LocalMedia3(
            media(trackId = "forced", forced = true),
        )
        val full = SubtitleIdentity.LocalMedia3(
            media(trackId = "full", forced = false),
        )
        val choices = listOf(option(forced, "English"), option(full, "English"))

        assertNotEquals(choices[0].stableId, choices[1].stableId)
        val presentation = buildTvSubtitleHudPresentation(
            options = choices,
            committedIdentity = forced,
            pendingIdentity = full,
            hudOpen = true,
            focusedStableId = choices[1].stableId,
        )
        assertEquals(2, presentation.rows.map { it.stableId }.distinct().size)
        assertTrue(presentation.rows.single { it.identity == full }.focused)
    }

    private fun present(
        committed: SubtitleIdentity,
        pending: SubtitleIdentity?,
        focusedId: String?,
    ): TvSubtitleHudPresentation = buildTvSubtitleHudPresentation(
        options = options(),
        committedIdentity = committed,
        pendingIdentity = pending,
        hudOpen = true,
        focusedStableId = focusedId,
    )

    private fun options(): List<TvSubtitleHudOption> = listOf(
        option(SubtitleIdentity.Off, "Off"),
        option(sidecar(3), "French"),
        option(sidecar(4), "English"),
        option(sidecar(5), "Spanish"),
    )

    private fun option(identity: SubtitleIdentity, label: String): TvSubtitleHudOption =
        TvSubtitleHudOption(
            stableId = id(identity),
            identity = identity,
            label = label,
        )

    private fun id(identity: SubtitleIdentity): String = tvSubtitleOptionStableId(identity)

    private fun sidecar(index: Int): SubtitleIdentity = SubtitleIdentity.ServerSidecar(index)

    private fun media(
        trackId: String,
        forced: Boolean,
    ): SubtitleMediaIdentity = SubtitleMediaIdentity(
        trackId = trackId,
        label = "English",
        language = "en",
        codecFamily = "pgs",
        forced = forced,
        hearingImpaired = false,
    )
}
