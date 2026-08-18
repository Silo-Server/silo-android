# Intro skip: never / ask / always

The behaviour is specified once, in the server repo, and implemented here:
`docs/design/2026-08-16-intro-skip-mode.md` in `silo-server`. Its
"Prompt behaviour" tables are the contract — read them before changing anything
below, and change them there first if the behaviour needs to move.

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
| TV pill | `androidTvApp/.../player/TvIntroAutoSkipBanner.kt` |
| TV Select / Back routing | `androidTvApp/.../player/TvPlayerScreen.kt` (root key handler + `BackHandler`) |
| Phone pill | `androidApp/.../player/IntroAutoSkipBanner.kt` |
| Phone Back | `androidApp/.../player/PlayerOverlay.kt` (`BackHandler(enabled = pill visible)`) |
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
