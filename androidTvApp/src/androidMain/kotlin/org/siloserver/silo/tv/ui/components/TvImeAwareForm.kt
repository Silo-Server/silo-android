package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.siloserver.silo.tv.ui.focus.TvFocusLog
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

internal data class TvImeRelocationKey(
    val imeBottomPx: Int,
    val fieldWidthPx: Int,
    val fieldHeightPx: Int,
)

internal fun tvImeRelocationKey(
    hasFocus: Boolean,
    imeBottomPx: Int,
    fieldWidthPx: Int,
    fieldHeightPx: Int,
): TvImeRelocationKey? =
    if (hasFocus && imeBottomPx > 0 && fieldWidthPx > 0 && fieldHeightPx > 0) {
        TvImeRelocationKey(
            imeBottomPx = imeBottomPx,
            fieldWidthPx = fieldWidthPx,
            fieldHeightPx = fieldHeightPx,
        )
    } else {
        null
    }

internal fun shouldRestoreTvImeFormScroll(
    previousImeBottomPx: Int,
    currentImeBottomPx: Int,
): Boolean = previousImeBottomPx > 0 && currentImeBottomPx == 0

/**
 * Keeps a focused TV text-field context clear of the stock platform IME.
 *
 * Apply this to the smallest container that includes the field's visible
 * label. [focusGroup] lets the container observe focus held by its child text
 * field without becoming an extra D-pad destination.
 */
@Composable
internal fun Modifier.tvImeAwareFieldContext(
    bottomClearance: Dp = TvImeFieldBottomClearance,
): Modifier {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val bottomClearancePx = with(density) { bottomClearance.toPx() }
    var hasFocus by remember { mutableStateOf(false) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val relocationKey = tvImeRelocationKey(
        hasFocus = hasFocus,
        imeBottomPx = imeBottomPx,
        fieldWidthPx = fieldSize.width,
        fieldHeightPx = fieldSize.height,
    )

    LaunchedEffect(relocationKey, bottomClearancePx) {
        val key = relocationKey ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching {
            bringIntoViewRequester.bringIntoView(
                Rect(
                    left = 0f,
                    top = 0f,
                    right = key.fieldWidthPx.toFloat(),
                    bottom = key.fieldHeightPx + bottomClearancePx,
                ),
            )
        }
    }

    return this
        .bringIntoViewRequester(bringIntoViewRequester)
        .onSizeChanged { fieldSize = it }
        .onFocusEvent { hasFocus = it.hasFocus }
        .focusGroup()
}

/**
 * The scrollable form the fields under one [TvSelectToShowImeHost] belong to.
 *
 * A registration slot rather than a parameter: the fields sit inside private
 * card composables that would otherwise have to thread the scroll state down,
 * and each auth route hosts exactly one form, so the slot is unambiguous.
 * [Modifier.tvShowImeOnSelect] reads it to put the form back at the top when
 * the D-pad tries to leave the topmost field upward.
 */
@Stable
internal class TvImeAwareFormScroll {
    var scrollState by mutableStateOf<ScrollState?>(null)
}

/** Absent for fields composed outside a [TvSelectToShowImeHost]. */
internal val LocalTvImeAwareFormScroll = staticCompositionLocalOf<TvImeAwareFormScroll?> { null }

/**
 * Owns scrolling for a TV form and returns it to its normal top position when
 * the stock IME closes. Initial composition with a hidden IME is a no-op.
 *
 * Also registers the form with the host so its fields can reach it.
 */
@Composable
internal fun rememberTvImeAwareFormScrollState(): ScrollState {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    var previousImeBottomPx by remember { mutableIntStateOf(imeBottomPx) }

    LaunchedEffect(imeBottomPx) {
        if (shouldRestoreTvImeFormScroll(previousImeBottomPx, imeBottomPx)) {
            scrollState.scrollTo(0)
        }
        previousImeBottomPx = imeBottomPx
    }

    val formScroll = LocalTvImeAwareFormScroll.current
    DisposableEffect(formScroll, scrollState) {
        formScroll?.scrollState = scrollState
        onDispose {
            if (formScroll?.scrollState === scrollState) formScroll.scrollState = null
        }
    }

    return scrollState
}

private val TvImeFieldBottomClearance = 32.dp

/**
 * Permission to raise the stock IME, shared by every field under one
 * [TvSelectToShowImeHost].
 *
 * Held by a token rather than a bare flag so a field can only close the gate it
 * opened: focus moving between two fields interleaves their events, and an
 * unconditional close from the field being left would revoke a permission the
 * arriving field had already been granted.
 */
@Stable
internal class TvSelectToShowImeGate {
    private var holder by mutableStateOf<Any?>(null)

    /** True while some field has earned the keyboard with a completed SELECT. */
    val isOpen: Boolean
        get() = holder != null

    fun open(token: Any) {
        holder = token
    }

    fun close(token: Any) {
        if (holder === token || (holder as? Parked)?.token === token) holder = null
    }

    /**
     * Leaves [token]'s permission standing but unowned, so the field focus is
     * moving *to* can [open] it for itself.
     *
     * The IME's own Next action moves focus while the keyboard is up; revoking
     * on the way out would tear down the session the viewer is typing into and
     * cost them a SELECT per field. The two orderings of a focus transfer are
     * both covered: an arriving field that observes its focus first adopts a
     * still-live holder, one that observes it second adopts the park.
     */
    fun park(token: Any) {
        if (holder === token) holder = Parked(token)
    }

    /** Carries the parking field's token so only that field can drop it. */
    private class Parked(val token: Any)
}

/**
 * Absent by default so [Modifier.tvShowImeOnSelect] degrades to its reactive
 * behavior when used outside a [TvSelectToShowImeHost] rather than crashing.
 */
internal val LocalTvSelectToShowImeGate = staticCompositionLocalOf<TvSelectToShowImeGate?> { null }

/**
 * Refuses the platform text-input session for the fields inside it until a
 * SELECT asks for one, so the stock IME is never raised in the first place.
 *
 * [Modifier.tvShowImeOnSelect] can only hide the keyboard *after* the field has
 * asked for it — the `value: String` `BasicTextField` under every Material
 * `OutlinedTextField` requests the IME on focus unconditionally. Hiding after
 * the fact is a race, and losing it is visible: device logcat on a Shield
 * caught the keyboard on screen for 120–270ms before the hide landed, which is
 * the flash this fixes (`SiloTvFocus`, 2026-08-15). Suppression therefore
 * has to happen upstream of the request, which is what this does: block
 * `startInputMethod` and the IMM is never told to start input at all, so there
 * is nothing to flash.
 *
 * **The interceptor instance is the restart signal.** Once
 * `interceptStartInputMethod` suspends in `awaitCancellation()`, flipping state
 * the suspended body already read changes nothing — Compose re-reads the
 * interceptor, not the body, and restarts the upstream session only when a
 * *different* interceptor object is provided
 * (`ChainedPlatformTextInputInterceptor` collects `snapshotFlow { interceptor }`
 * with `collectLatest`). Hence `remember(allowIme)`, and hence the lambda
 * capturing `allowIme` rather than reading the gate itself: both are needed for
 * the gate flip to produce a new object, and without a new object SELECT would
 * silently stop summoning the keyboard at all.
 *
 * Wrap the auth screens (see `TvAppNavigation`), not the whole app: fields that
 * legitimately want the keyboard on focus — search, the text-entry dialogs —
 * must stay outside.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvSelectToShowImeHost(content: @Composable () -> Unit) {
    val gate = remember { TvSelectToShowImeGate() }
    val formScroll = remember { TvImeAwareFormScroll() }
    // Pointer users are exempt: a tap on a field is an explicit request to
    // type, so the session is never withheld from them. Same product call the
    // reactive half of the policy makes in tvShowImeOnSelect.
    val touchMode = LocalInputModeManager.current.inputMode == InputMode.Touch
    val allowIme = gate.isOpen || touchMode

    val interceptor = remember(allowIme) {
        PlatformTextInputInterceptor { request, nextHandler ->
            if (allowIme) {
                TvFocusLog.d { "ime: platform session allowed (select or touch)" }
                nextHandler.startInputMethod(request)
            } else {
                TvFocusLog.d { "ime: platform session blocked (focus without select)" }
                // Never delegating is what blocks the request. The session stays
                // suspended here until the gate flips and this instance is
                // replaced, at which point the branch above runs instead.
                awaitCancellation()
            }
        }
    }

    CompositionLocalProvider(
        LocalTvSelectToShowImeGate provides gate,
        LocalTvImeAwareFormScroll provides formScroll,
    ) {
        InterceptPlatformTextInput(interceptor = interceptor, content = content)
    }
}

/**
 * Summons the stock IME on SELECT/ENTER instead of on focus, and routes
 * vertical D-pad out of the field so focus is never trapped in it.
 *
 * **Every** text field in the TV auth flow needs this, not just the one a
 * screen focuses first: without it the field owns the vertical D-pad and the
 * remote cannot leave it (verified on the emulator 2026-08-14 — the first-run
 * admin form could not be completed at all).
 *
 * `KeyboardOptions(showKeyboardOnFocus = false)` does **not** deliver the
 * focus half on its own. Compose foundation 1.8.0 documents the option as
 * unsupported on the `value: String` overload of `BasicTextField`
 * (`BasicTextField.kt:639` and `:796`), which is what every Material
 * `OutlinedTextField` in this flow is built on — so the field still pops the
 * IME the moment D-pad focus lands. Suppression therefore lives here, where
 * this modifier can tell a focus arrival apart from a deliberate SELECT.
 * Set the option anyway at the call sites: it is free, and it starts working
 * on its own the day the fields move to the `TextFieldState` overload.
 *
 * The actual suppression is [TvSelectToShowImeHost]'s, which refuses the
 * platform input session outright; this modifier only tells it which field has
 * earned the keyboard. The reactive `hide()` below stays as a safety net for
 * fields composed outside a host, where the gate is null — after the host
 * landed it should never again see `visible=true` in the log.
 *
 * Pointer users are exempt from the suppression — a click on a field is an
 * explicit request to type. Auth-flow field idiom (product call 2026-08-14).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.tvShowImeOnSelect(): Modifier {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputMode = LocalInputModeManager.current.inputMode
    // The visibility flag, not the inset height: Gboard TV is a floating panel
    // whose IME inset source reports visible=true with a zero-height frame
    // (Google TV emulator, 2026-08-16 — `dumpsys window` showed
    // `type=ime frame=[0,1080][1920,1080] visible=true` with the keyboard on
    // screen), so `ime.getBottom() > 0` reads false while the keyboard is up
    // and the Next-keeps-the-keyboard handoff below never engages.
    val imeVisible = WindowInsets.isImeVisible
    // This modifier raises the IME, so it owns taking it back down when the
    // field leaves composition — otherwise the keyboard floats over the next
    // screen and keeps eating the D-pad. TvStockKeyboardPolicyTest pins the
    // pairing; putting it here means every field using this gets it.
    TvHideStockImeOnDispose()

    // Null when this field is composed outside a TvSelectToShowImeHost; the
    // reactive suppression below then carries the policy on its own.
    val imeGate = LocalTvSelectToShowImeGate.current
    // Identifies this field to the shared gate for the lifetime of the node.
    val gateToken = remember { Any() }
    val formScrollState = LocalTvImeAwareFormScroll.current?.scrollState
    val scope = rememberCoroutineScope()

    var hasFocus by remember { mutableStateOf(false) }
    // True once SELECT summoned the keyboard on purpose, so the arrival
    // suppression below leaves it alone until focus moves on.
    var imeRequested by remember { mutableStateOf(false) }

    // Leaving composition with the gate still open would hand the next screen's
    // fields a keyboard they never asked for.
    DisposableEffect(imeGate, gateToken) {
        onDispose { imeGate?.close(gateToken) }
    }

    // Take back down whatever the field raised on a focus arrival this
    // modifier did not ask for. Keyed on the IME insets as well as on focus so
    // it is self-correcting: the field's own show request is asynchronous and
    // can land after ours, and re-running the moment the keyboard actually
    // surfaces closes that race without guessing a frame count.
    LaunchedEffect(hasFocus, imeRequested, inputMode, imeVisible) {
        if (!hasFocus || imeRequested || inputMode == InputMode.Touch) return@LaunchedEffect
        withFrameNanos { }
        keyboardController?.hide()
        TvFocusLog.d { "field: focus arrived without select -> IME hidden (visible=$imeVisible)" }
    }

    // A park exists only to survive the focus transfer that created it. One
    // frame on, either the arriving sibling has claimed it or nothing will —
    // leaving it standing would let the host allow a session no field asked
    // for. Harmless on the initial composition: closing a gate we never held
    // is a no-op.
    LaunchedEffect(hasFocus) {
        if (hasFocus) return@LaunchedEffect
        withFrameNanos { }
        imeGate?.close(gateToken)
    }

    // A select must start AND end on this field to summon the IME. Acting on
    // KeyUp alone leaks: activating a button whose click moves focus into the
    // field (e.g. "Sign in with a password") delivers the tail KeyUp of that
    // same press here and pops the keyboard uninvited — after which the D-pad
    // drives the keyboard instead of the form.
    val sawKeyDown = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    return this
        .onFocusEvent { focusState ->
            // isFocused, not hasFocus: the field's own decoration hosts the
            // password visibility button, and that button holding focus must
            // not read as the editable field holding it.
            val focused = focusState.isFocused
            if (focused) {
                // Focus arriving while the keyboard is already up and this
                // form's permission is still live is the IME's own Next
                // action moving between fields — the viewer is mid-entry, so
                // adopt the session instead of tearing it down. A programmatic
                // claim on a fresh screen cannot reach this: each auth route
                // has its own host, so its gate starts closed.
                if (imeVisible && imeGate?.isOpen == true) {
                    imeGate.open(gateToken)
                    imeRequested = true
                }
            } else {
                imeRequested = false
                // A SELECT that started here but ends elsewhere is not a
                // select on this field; forgetting the KeyDown keeps a later
                // stray KeyUp from summoning the keyboard on its own.
                sawKeyDown.set(false)
                if (imeVisible) imeGate?.park(gateToken) else imeGate?.close(gateToken)
            }
            hasFocus = focused
        }
        .onPreviewKeyEvent { event ->
            val selectKey = event.key == Key.DirectionCenter ||
                event.key == Key.Enter ||
                event.key == Key.NumPadEnter
            when {
                // SELECT belongs to whatever is focused. When that is the
                // trailing visibility button rather than the editable field,
                // this handler is still its ancestor, and swallowing the press
                // here is what made the button untoggleable from a remote.
                selectKey && !hasFocus -> false
                selectKey && event.type == KeyEventType.KeyDown -> {
                    sawKeyDown.set(true)
                    // DPAD_CENTER means nothing to the field itself, and left
                    // unconsumed the root key handler reads it as
                    // FocusDirection.Enter — on the password field that walks
                    // focus into the trailing visibility button before the
                    // KeyUp that raises the keyboard ever arrives (Google TV
                    // emulator 2026-08-16; foundation's own D-pad interceptor
                    // only swallows it for physical D-pad devices). Enter stays
                    // with the field so a hardware keyboard's Enter still
                    // performs the IME action.
                    event.key == Key.DirectionCenter
                }
                selectKey && event.type == KeyEventType.KeyUp -> {
                    if (sawKeyDown.compareAndSet(true, false)) {
                        TvFocusLog.d { "field: select completed on field -> showing IME" }
                        imeRequested = true
                        // Opening the gate is what actually raises the keyboard
                        // under a host: it swaps the interceptor, which restarts
                        // the field's pending session and lets it through — the
                        // delegated startInput shows the IME by itself. The
                        // show() below still matters for the re-press case (the
                        // gate is already open, so nothing recomposes) and for
                        // fields composed outside a host.
                        imeGate?.open(gateToken)
                        keyboardController?.show()
                    } else {
                        TvFocusLog.d { "field: stray select KeyUp suppressed (no matching KeyDown)" }
                    }
                    // Consumed either way. Forwarding the stray tail KeyUp is
                    // what the suppression exists to prevent — handing it to
                    // the field pops the very keyboard we declined to show.
                    true
                }
                // The legacy text field consumes vertical D-pad for cursor moves a
                // single-line box cannot make, trapping focus in the field forever.
                // Route vertical D-pad to focus search instead. Only reachable with
                // the IME closed — an open IME owns the keys before the app sees
                // them. Left/right stay with the field for in-text cursor movement.
                event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                    // Call outside the log lambda: TvFocusLog.d only runs its
                    // body in debug builds, so a move made in there would not
                    // happen in release.
                    val moved = focusManager.moveFocus(FocusDirection.Down)
                    TvFocusLog.d { "field: dpad DOWN -> moveFocus moved=$moved" }
                    // Consumed even when the move fails. Handing an unusable
                    // vertical key back to a single-line field is what trapped
                    // focus in the first place.
                    true
                }
                event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                    val moved = focusManager.moveFocus(FocusDirection.Up)
                    if (!moved) {
                        // Nothing focusable above, but the form can still be
                        // scrolled: focus search only ever scrolls a control
                        // far enough to be visible, so the brand mark and title
                        // above the first field stay off-screen with no
                        // focusable way back. Spend the key on the scroll
                        // instead of on nothing.
                        formScrollState?.let { state ->
                            scope.launch { state.animateScrollTo(0) }
                        }
                    }
                    TvFocusLog.d { "field: dpad UP -> moveFocus moved=$moved" }
                    true
                }
                else -> false
            }
        }
}
