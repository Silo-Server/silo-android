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
| 2026-07 | 26 |
| 2026-08 (to the 10th) | **49** |

Of the 83 focus commits in the last 90 days, **49 are `fix:` and 9 are
`feat:`**. That ratio is the finding. This is not a feature being built out;
it is one problem being re-cut repeatedly.

Churn is concentrated, not spread:

| File | times touched by a focus commit (90d) |
|---|---|
| `TvMainShell.kt` | **15** |
| `TvRecommendationsScreen.kt` | 9 |
| `TvLibraryDetailScreen.kt` | 8 |
| `TvCalendarScreen.kt` | 8 |
| `TvRecommendationsFocusBridge.kt` | 7 |

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

`TvShellFocusState` on `main` carries ten mutable fields:

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
openPanel               TvTopMenuPanel?
```

PR #204 adds two more (`barFocusFromPanelClose`, `menuFocusSuppressesDwell`).

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

`#204`'s two new flags collapse into this directly. `barFocusFromPanelClose`
becomes `Bar(tab) + PanelDismissed`. `menuFocusSuppressesDwell` stops existing:
`dwellEligible` returns false for `PanelDismissed` and `Leaving`, true for
`Browsing`. The bug where an ordinary Up armed the Back-close suppression
becomes unrepresentable rather than excluded by a flag.

### Focus entry is a shell concern

`#202` says a subtree-level fix cannot beat a `focusRestorer` on an ancestor.
So the model must state where restorers may live: a restorer belongs to a
**content region**, never to a container that also hosts modals or chrome.
Modals get their own window. This is a rule about the composition tree, and it
needs a source test to hold — the same class of check the repo already uses in
`*SourceTest.kt`.

### Failure must be loud

The 08-04 design says observed focus is authoritative. Keep that, and add: a
claim that never lands emits a diagnostic. `#199` added exactly that warning
and `#203` is what stops it crashing the app — with those merged, the signal
exists. `focus trap suspected` and `navigation struggle` already arrive from
real devices, so we can measure whether this works instead of asserting it.

## Scope

**In:** `TvShellFocusState`, `TvMainShell`, `TvTopMenuBar`, and the Back/dwell
decision paths — the 15-edit hotspot.

**Out:** screen-local initial-focus acquisition (`TvContentInitialFocus`,
`TvDialogInitialFocus`, the return-target adapters). That half of the 08-04
design works; the bounded observed-focus policy is sound and stays. This is
about who owns focus, not how a screen claims it.

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

- Focus `fix:` commits per month on `main` — currently 49 and rising.
- Edits to `TvMainShell.kt` per month.
- `focus trap suspected` and `navigation struggle` per tester session in
  GlitchTip, which is where the real evidence has been coming from.

If those three do not fall, this document was wrong and should be replaced
rather than supplemented.
