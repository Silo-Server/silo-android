# Intro skip: never / ask / always

The behaviour is specified once, in the server repo, and implemented here:
`docs/design/2026-08-16-intro-skip-mode.md` in `silo-server`. Its
"Prompt behaviour" tables, "When the controls are up" and "Recap and credits
pills" are the contract — read them before changing anything below, and change
them there first if the behaviour needs to move.

The same state machine drives the Skip Recap and Skip Credits pill: a second
`IntroAutoSkipController` per player, started with `observeSegmentPill`, which
follows the `ask` table whatever the intro mode is. Every pill's timer is
`DEFAULT_COUNTDOWN_SECONDS` (the spec's `INTRO_PROMPT_SECONDS`, 10 s).

## Setting

`playback.intro_skip_mode` (`never` | `ask` | `always`, default `ask`), contract
revision 7, scopes `profile` and `profile_device`. It supersedes the deprecated
boolean `playback.auto_skip_intro`, which the server mirrors at write time for
one release.

## Where each part lives

| Part | Class |
| --- | --- |
| The mode enum and its wire/legacy mapping | `shared/.../domain/player/IntroSkipMode.kt` |
| The state machine (the spec's tables) | `shared/.../domain/player/IntroAutoSkipController.kt` |
| Conformance against the tables | `shared/src/commonTest/.../IntroAutoSkipControllerTest.kt` |
| Rebuffer-vs-pause filtering | `shared/.../domain/player/SettlingFalseEdges.kt` |
| Reading and writing the setting | `android-shared/.../settings/AndroidPlayerSettingsStore.kt` |
| TV pill (intro, recap and credits) | `androidTvApp/.../player/TvIntroAutoSkipBanner.kt` |
| TV Select / Back routing | `androidTvApp/.../player/TvPlayerScreen.kt` (root key handler + `BackHandler`) |
| Phone pill (intro, recap and credits) | `androidApp/.../player/IntroAutoSkipBanner.kt` |
| Phone Back | `androidApp/.../player/PlayerOverlay.kt` (`BackHandler(enabled = timed pill shown)`) |
| Settings UI | `androidTvApp/.../settings/TvSettingsScreen.kt`, `androidApp/.../settings/PlaybackSettings.kt` |

## Rules worth stating twice

**The controller performs exactly one seek.** The immediate skip that `always`
is, through `observe(onSeek = ...)`. Everything the viewer triggers is
*returned* by `select()` for the caller to perform.

**Watch Together pins the mode to `ask`.** In a room only the host's transport
may move position, so a guest must never auto-seek — `TvPlayerViewModel`
substitutes `flowOf(IntroSkipMode.ASK)` for the stored mode whenever `roomId`
is non-null. The pill stays live; its Select routes through the screen's
`tvRoomTransportGate` like every other seek. The gate is checked *before*
`select()` is asked for a target, so a refused press leaves the pill and the
intro untouched.

**The `always` pill is anchored to the intro, not the position.** The skip that
produces it necessarily leaves the range, so the ordinary "outside the range →
hide" rule would take the undo down on the next frame. While `Skipped` is
showing, position changes do nothing; only the timer, Select, Back, a content
change or a mode change end it.

**A pause freezes the timer, it does not restart it.** The tick job is
cancelled and `secondsRemaining` is kept; resuming continues from that number.
The fill is frame-clock driven (never an `AnimationSpec` — the system animator
duration scale would make the bar lie about when the action fires), so it needs
two signals from the controller: `countdownRun`, bumped every time the tick job
starts *including* a thaw, to re-anchor its clock, and `timerRunning`, false
while frozen, to hold the bar still. Whole seconds only: the partial second in
flight when the pause landed is not carried across.

**`ask`'s timeout does not resolve the intro; `always`'s does.** A withdrawn
offer re-offers when the viewer scrubs back in. An expired undo does not — the
viewer was told the intro was skipped and let it go.

**A withdrawn `ask` offer returns with the controls.** While the position stays
inside the range and the controls overlay is up (not under the TV menu or Up
Next, and not mid-scrub), the controller publishes `Returned`: the same action
with no timer. It hides with the controls and never takes focus; on TV, Up from
the scrubber reaches it. If the timer runs out with the controls already up,
the pill stays as `Returned` and the screen moves focus to the scrubber. It
counts as a skip pill for the buffering pill. Callers tell the two forms apart
with `isTimed`: only the timed pill takes root Select and Back, so with
`Returned` up Back does what it does without a pill.

**Recap and credits.** Back resolves the timed pill on phone and TV (TV: Back,
not Menu, which still opens the menu). Back is not claimed while the TV menu or
Up Next is open, where the pills are not drawn. On TV, OK with the controls
hidden skips only while the timer runs; afterwards it shows the controls, as
OK always does. The segment controller's `playbackActive` is also held false
while the intro pill or Up Next covers it, so its timer freezes there and an
offer that waited starts with a full timer. A Watch Party member who may not
seek never sees the pill; one who may gets a room seek.
