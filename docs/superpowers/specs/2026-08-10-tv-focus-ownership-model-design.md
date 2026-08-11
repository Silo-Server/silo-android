# TV Focus Ownership Model — Design

## Goal

Stop the TV shell's focus behaviour from being an emergent property of a dozen
independent flags, and make it a value that can be read, tested exhaustively,
and reasoned about without running the app.

This is deliberately **not** another whole-application focus audit. One already
exists and it was largely right. This addresses the one place it explicitly
declined to model.

## Why another focus document

`2026-08-04-whole-application-focus-hardening-design.md` identified six
recurring causes and a set of principles that still hold — observed focus is
authoritative, retries are bounded and lifecycle-cancelled, modals own focus
for their visible lifetime, disabled means disabled everywhere. Those are
correct and this design keeps all of them.

The rate of focus defects went **up** after it landed.

| Month | focus-titled commits on `main` |
|---|---|
| 2026-06 | 8 |
| 2026-07 | 22 |
| 2026-08 (to the 11th) | **48** |

Method, so these can be re-derived rather than trusted:
`git log main --format=%s --since=2026-06-01 --until=2026-06-30 | grep -ci focus`
— commit SUBJECTS only, one month per row.

Of the 81 focus-subject commits in the last 90 days, **49 are `fix:` and 7 are
`feat:`**. That ratio is the finding. This is not a feature being built out;
it is one problem being re-cut repeatedly.

Churn is concentrated, not spread:

| File | times touched by a focus commit (90d) |
|---|---|
| `TvMainShell.kt` | **14** |
| `TvRecommendationsScreen.kt` | 9 |
| `TvLibraryDetailScreen.kt` | 7 |
| `TvCalendarScreen.kt` | 7 |
| `TvRecommendationsFocusBridge.kt` | 6 |

Method: `git log main --since=2026-05-13 --format=%s -- '*/<file>.kt' | grep -ci focus`

There are already 19 production focus files, 22 focus test files and 8 focus
design documents. The infrastructure is not missing. Something else is wrong.

### What the 2026-08-04 design got right, and the sentence that limits it

> *Shared helpers encode repeated policy, but screen-specific resolution
> remains close to the screen. This avoids a global focus coordinator with
> hidden cross-route coupling.*

That is a reasonable instinct — a global coordinator that every route reaches
into is its own disaster. But it left the **shell** unmodelled, and the shell
is where per-screen policies collide. Hence 15 edits to one file.

It also has no enforcement half. Nothing makes a screen adopt the helpers,
nothing detects a screen that has not, and the escape clause — *"existing
specialized flows … remain intact unless they can adopt the helper"* — makes
divergence compliant. Good rules with no mechanism decay into good intentions.

Two defects found after it landed are not in its six causes at all:

- **#202** — `focusRestorer` on the shell's content `Box` intercepts focus
  *entry* into its subtree and redirects it to the child it remembers. Every
  claim the crash prompt made was rerouted to the card behind it. Nothing
  inside the subtree can win, because both retry and focus boundaries govern
  movement *once focus is in*, and it never got in.
- **#204** — `menuFocusTarget` meant two things at once: which bar element to
  land on, and whether that element's dwell preview is suppressed.

Neither is an acquisition bug. Both are **ownership** bugs.

## Root cause

`TvShellFocusState` on `main` carries twelve mutable fields:

```
menuFocusRequest        Int token
menuFocusTarget         TvTopMenuPanel?
profileFocusRequest     Int token
panelFocusEntryToken    Int token
profileMenuFocusEntryToken  Int token
isMenuFocused           Boolean
profileMenuOpen         Boolean
profileMenuEntered      Boolean
panelEntersFocus        Boolean
panelHasFocus           Boolean
menuFocusSuppressesDwell Boolean
openPanel               TvTopMenuPanel?
```

As merged, PR #204 added `menuFocusSuppressesDwell` and `panelHasFocus`, and
**deleted** `barFocusFromPanelClose` — review found it unreachable from
production UI, because the only caller that armed it was a cascade `onClose`
callback the selector never invoked. That deletion is itself evidence for this
document's argument: a flag existed, was carried through routing and tests, and
described a state the app could not actually reach.

Nothing in that list says *who owns focus*. Ownership is inferred by reading
several flags together, and every combination is representable — including the
many that are meaningless. Each new defect is fixed by adding a flag that
excludes one bad combination, which enlarges the space the next defect hides
in. That is the mechanism behind both the 15 edits and the rising trend.

The insight is already written down, in `b44e1d8f`'s own commit message:

> *The distinction that matters is not which call site but **why focus
> arrived**: Up from content = browsing → the cascade should open. Back from
> content = leaving → it must not.*

Exactly right — and today "why focus arrived" is not stored anywhere. It is
reconstructed from flags at each decision point, and each reconstruction is a
new chance to get it wrong.

## The model

Replace the flag set with one value:

```kotlin
sealed interface TvFocusOwner {
    data class Content(val route: String) : TvFocusOwner
    data class Bar(val tab: TvTopMenuPanel) : TvFocusOwner
    data class Panel(val panel: TvTopMenuPanel) : TvFocusOwner
    data object ProfileMenu : TvFocusOwner
    data class Modal(val id: String) : TvFocusOwner
}

/** Why focus arrived where it is. Governs Back and dwell, nothing else. */
enum class TvFocusArrival { Browsing, Leaving, PanelDismissed, Restored, Explicit }

data class TvFocusOwnership(val owner: TvFocusOwner, val arrival: TvFocusArrival)
```

Exactly one owner at a time, and the reason it arrived travels with it.

Two pure functions replace the scattered conditionals:

```kotlin
fun backAction(ownership: TvFocusOwnership, canNavigateUp: Boolean): TvShellBackAction
fun dwellEligible(ownership: TvFocusOwnership): Boolean
```

Both are total `when`s over a sealed type, so they are exhaustively testable in
JVM tests with no Compose runtime, and — the part that matters — **adding an
owner or an arrival reason breaks compilation everywhere a decision is made.**
That is the enforcement the previous design lacked: the compiler, not a
convention.

`#204`'s flags collapse into this directly. `menuFocusSuppressesDwell` stops
existing: `dwellEligible` returns false for `PanelDismissed` and `Leaving`,
true for `Browsing`. The bug where an ordinary Up armed the Back-close
suppression becomes unrepresentable rather than excluded by a flag.

`panelHasFocus` is the more interesting case, because it is the one flag #204
added that this model would *keep* — under a different name. It exists because
`panelEntersFocus` records entry INTENT, and routing that asked the intent flag
sent the viewer into content from a bar they had never left. In these terms
that is not a flag at all: it is the difference between `Panel(tab)` being the
owner and `Bar(tab)` still being the owner with a preview showing. An ownership
type makes the distinction structural; a boolean pair makes it a rule someone
has to remember. #204 shipped the boolean because the type does not exist yet.

### Focus entry is a shell concern

`#202` says a subtree-level fix cannot beat a `focusRestorer` on an ancestor.
So the model must state where restorers may live: a restorer belongs to a
**content region**, never to a container that also hosts modals or chrome.
Modals get their own window. This is a rule about the composition tree, so it
needs its own check to hold — a restorer-placement source test, in the same
style as `*SourceTest.kt`. That is a DIFFERENT check from #208's silent-claim
ratchet, which is why the retraction above says no further enforcement is
needed for silent focus claims specifically, rather than none at all.

### Failure must be loud

The 08-04 design says observed focus is authoritative. Keep that, and add: a
claim that never lands emits a diagnostic. `#199` added exactly that warning
and `#203` is what stops it crashing the app — with those merged, the signal
exists. `focus trap suspected` and `navigation struggle` already arrive from
real devices, so we can measure whether this works instead of asserting it.

## The larger half: the prescribed fix was never adopted

An earlier draft of this document asserted that the screen-local half of the
08-04 design "works" and only the shell needed modelling. That is false, and
the correction changes the priority order.

Focus churn by area over 90 days, counted as file-touches by focus commits:

| Area | touches |
|---|---|
| `ui/screens/` | **126** |
| `ui/components/` | 34 |
| `ui/focus/` | 14 |
| `ui/shell/` | **16** |

Method: file-touches by focus-subject commits since 2026-05-13 —
`git log main --since=2026-05-13 --format='COMMIT %s' --name-only -- <dir>`,
counting filenames under commits whose subject matches `focus`.

The shell is the most-edited single *file*, but screens are five times the
churn.

**An earlier draft of this note argued the cause was non-adoption, on figures
that #208 has since made false.** It claimed 8 adopters against 44 direct
callers — 18% — and 107 `runCatching` occurrences in TV screens. As of `main`
at 7245c0f4:

Method, so these can be re-derived rather than trusted — run against
`androidTvApp/src/androidMain`:
`grep -rl 'requestFocusUntilObserved\|claimFocusOrReport'`,
`grep -rl 'requestFocus()'`, and the intersection of the two.

- **34** files use the shared observed-focus helpers
  (`requestFocusUntilObserved` / `claimFocusOrReport`).
- **24** still contain a raw `requestFocus()`, of which **9** also use a helper,
  leaving **15** genuine hold-outs.
- That is **69%** adoption, not 18%.
- **31** `runCatching` occurrences remain in TV screens, and exactly **1**
  wraps a `requestFocus()` —
  `grep -ro runCatching …/tv/ui/screens | wc -l`, and the same grep with `-n`
  filtered to lines also containing `requestFocus`.

Cause #1 of the 08-04 audit — *"a focus request executing without exception is
treated as focus acquisition"* — has therefore been substantially worked off,
by #208's sweep of 78 sites and by the ratchet that keeps them at zero.

**So the ordering this note originally proposed is wrong, and the argument it
leans on is gone.** Enforcement exists and adoption followed it. What remains
is the part the sweep could not do: the shell still infers ownership by reading
twelve flags together, and #204's `panelHasFocus` is the newest example — a
boolean added because `panelEntersFocus` records intent rather than arrival.
That distinction is a type, expressed as a flag pair.

**The revised ordering is: shell ownership model first, hold-out migration
second, and no further enforcement against silent focus claims — #208's
ratchet is doing that job. The one check this note does still ask for is
unrelated to it: a restorer-placement test, described below.**

### Enforcement — already done, nothing proposed here

An earlier draft proposed adding a source test that fails on
`runCatching { … requestFocus() … }` in `androidTvApp/.../ui/screens/`, with
existing sites baselined and the count only allowed to fall.

**That is #208, which has merged.** The ratchet exists, it holds the count at
zero, and `TvSilentFocusClaimSourceTest` is the file. This note proposes no new
enforcement; the 15 remaining hold-out files can be migrated in churn order
behind the gate that is already there.

## Scope

**In:** `TvShellFocusState`, `TvMainShell`, `TvTopMenuBar` and the Back/dwell
decision paths — the ownership model, which is what a sweep cannot do. Then
migration of the 15 hold-out files, in churn order.

**Out:** the enforcement gate, which #208 already shipped. Also out: the
bounded observed-focus policy itself — it is sound, it stays, and at 69%
adoption the objection that "nobody uses it" no longer holds. What remains
wrong is not that policy but the shell inferring ownership from twelve flags.

**Explicitly not** a global focus coordinator. Screens keep resolving their own
targets. The shell stops guessing what state it is in.

## Risks

- It is a refactor of the file with the most focus history, so it will conflict
  with anything in flight. #204 should land first; this is built on top.
- A sealed model is only as good as its arrival reasons. If a sixth reason is
  needed within a month, that is a signal the taxonomy is wrong, not that it
  needs another entry.
- Behaviour must be preserved exactly for the cases the current flags get
  right. The existing `TvShellFocusStateTest` is the floor and should be
  extended, not rewritten, so a regression is visible as a failing assertion
  rather than a changed expectation.

## How we will know it worked

- Focus-subject `fix:` commits per month on `main` — 49 across the last 90
  days, against 48 focus-subject commits of all types in August alone.
- Edits to `TvMainShell.kt` per month.
- `focus trap suspected` and `navigation struggle` per tester session in
  GlitchTip, which is where the real evidence has been coming from.

If those three do not fall, this document was wrong and should be replaced
rather than supplemented.
