package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.i18n.UiText
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SUBTITLE_DELIVERY_BURN_IN_ONLY
import org.siloserver.silo.model.playback.SUBTITLE_DELIVERY_SIDECAR
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TvSubtitleHudStateTest {
    // The HUD builders read their words from the app's resources (English here).
    private val resources
        get() = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun `sync status resolves server tracks by ordinal and merged stored rows by download id`() {
        val sync = TvHudSubtitleSync(
            statusByServerIndex = mapOf(0 to UiText.Raw("Synced −3.0 s")),
            statusByDownloadId = mapOf(9 to UiText.Raw("Syncing… 40%")),
        )
        assertEquals(UiText.Raw("Synced −3.0 s"), sync.statusFor(SubtitleIdentity.ServerSidecar(0)))
        assertEquals(UiText.Raw("Syncing… 40%"), sync.statusFor(SubtitleIdentity.Downloaded(9, SubtitleMediaIdentity())))
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
            resources = resources,
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
            resources = resources,
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
            resources = resources,
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
            resources = resources,
        )

        assertEquals(id(sidecar(4)), refreshed.focusedStableId)
        assertTrue(refreshed.rows.single { it.identity == sidecar(4) }.focused)
    }

    @Test
    fun `a track no route can show is listed in place but never takes focus`() {
        val english = PlayerSubtitleInfo(
            index = 0,
            language = "eng",
            codec = "srt",
            source = "external",
            url = "/s/0.vtt",
            serverTrackId = "file:1:subtitle:0",
            serverDelivery = SUBTITLE_DELIVERY_SIDECAR,
        )
        val unrecognized = PlayerSubtitleInfo(
            index = 1,
            language = "eng",
            source = "embedded",
            url = "",
            serverTrackId = "file:1:subtitle:1",
            serverDelivery = SUBTITLE_DELIVERY_BURN_IN_ONLY,
            unplayable = true,
        )
        val options = buildTvSubtitleHudOptions(
            subtitleUrls = listOf(english, unrecognized),
            subtitleTracks = emptyList(),
            resources = resources,
        )
        assertEquals(listOf(false, false, true), options.map(TvSubtitleHudOption::unplayable))

        // A stale focus on it, or Off committed, lands on a row that can take focus.
        val presentation = buildTvSubtitleHudPresentation(
            options = options,
            committedIdentity = SubtitleIdentity.Off,
            pendingIdentity = null,
            hudOpen = true,
            focusedStableId = options[2].stableId,
            resources = resources,
        )
        assertEquals(options[0].stableId, presentation.focusedStableId)
        assertTrue(presentation.rows[2].unplayable)
    }

    @Test
    fun `each embedded track is listed once, under the server row that names it`() {
        // Embedded SubRip rows of a direct-play MKV. English is natively
        // switchable; Spanish is a sidecar row, so only the container track ID
        // of the original file ties it to the Media3 track the player lists.
        val english = PlayerSubtitleInfo(
            index = 0, language = "eng", codec = "subrip", label = "English (SDH)", source = "embedded",
            url = "/s/0.vtt", isDefault = true, serverTrackId = "file:1:subtitle:0",
            serverDelivery = SUBTITLE_DELIVERY_SIDECAR, nativeContainerTrackId = "3", sourceContainerTrackId = "3",
        )
        val spanish = PlayerSubtitleInfo(
            index = 1, language = "spa", codec = "subrip", label = "Spanish", source = "embedded",
            url = "/s/1.vtt", serverTrackId = "file:1:subtitle:1",
            serverDelivery = SUBTITLE_DELIVERY_SIDECAR, sourceContainerTrackId = "4",
        )
        // An older server sends no container track ID: nothing ties the row to a track.
        val spanishUnknown = spanish.copy(sourceContainerTrackId = null)
        fun track(index: Int, trackId: String?, label: String = "") =
            PlayerTrackEntry(index = index, label = label, language = null, isSelected = false, trackId = trackId)
        val englishTrack = track(0, "3", label = "English")
        val spanishTrack = track(1, "4")
        val cea608 = track(2, "1/608")

        data class Case(
            val name: String,
            val rows: List<PlayerSubtitleInfo>,
            val tracks: List<PlayerTrackEntry>,
            val expected: List<String>,
        )
        val cases = listOf(
            Case("subtitles off", listOf(english, spanish), listOf(englishTrack, spanishTrack),
                listOf("off", "server:0", "server:1")),
            Case("server sidecar merged beside the file", listOf(english, spanish),
                listOf(track(0, "0:3"), track(1, "0:4"), track(2, "1:silo-subtitle:1")),
                listOf("off", "server:0", "server:1")),
            Case("a track no row names stays listed", listOf(english, spanish),
                listOf(englishTrack, spanishTrack, cea608),
                listOf("off", "server:0", "server:1", "player:1/608")),
            Case("no container track ID from the server", listOf(english, spanishUnknown),
                listOf(englishTrack, spanishTrack),
                listOf("off", "server:0", "server:1", "player:4")),
            Case("an unplayable row keeps its track pickable", listOf(english, spanish.copy(unplayable = true)),
                listOf(englishTrack, spanishTrack),
                listOf("off", "server:0", "server:1", "player:4")),
            // A burn-in-only row (VobSub, DVB) plays through a transcode; the
            // file's track is the way to show it on the device.
            Case("a burn-in-only row keeps its track pickable",
                listOf(english, spanish.copy(url = "", serverDelivery = SUBTITLE_DELIVERY_BURN_IN_ONLY)),
                listOf(englishTrack, spanishTrack),
                listOf("off", "server:0", "server:1", "player:4")),
        )
        for (case in cases) {
            val options = buildTvSubtitleHudOptions(case.rows, case.tracks, resources)
            val keys = options.map { option ->
                when (val identity = option.identity) {
                    SubtitleIdentity.Off -> "off"
                    is SubtitleIdentity.LocalMedia3 -> "player:${identity.media.trackId}"
                    else -> "server:${identity.serverIndexOrNull()}"
                }
            }
            assertEquals(case.expected, keys, case.name)
        }
        // A choice saved as that player track checks the row it now stands under,
        // and picking that row keeps the player track instead of replanning it.
        val picked = mutableListOf<SubtitleIdentity>()
        val presentation = buildTvSubtitleHudPresentation(
            options = buildTvSubtitleHudOptions(listOf(english, spanish), listOf(englishTrack, spanishTrack), resources),
            committedIdentity = tvSubtitleIdentity(spanishTrack),
            pendingIdentity = null,
            hudOpen = true,
            focusedStableId = null,
            resources = resources,
            onSelect = { picked += it },
        )
        assertEquals(listOf(tvSubtitleIdentity(spanish)), presentation.rows.filter { it.checked }.map { it.identity })
        presentation.onSelect(tvSubtitleIdentity(spanish))
        presentation.onSelect(tvSubtitleIdentity(english))
        assertEquals(listOf(tvSubtitleIdentity(spanishTrack), tvSubtitleIdentity(english)), picked)
    }

    private fun SubtitleIdentity.serverIndexOrNull(): Int? = when (this) {
        is SubtitleIdentity.Embedded -> serverIndex
        is SubtitleIdentity.ServerSidecar -> serverIndex
        is SubtitleIdentity.ServerBurnIn -> serverIndex
        else -> null
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
            resources = resources,
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
        resources = resources,
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
