package org.siloserver.silo.domain.player

/**
 * What Silo does when playback enters a detected intro —
 * `playback.intro_skip_mode`, contract revision 7.
 *
 * The spec is the server repo's `docs/design/2026-08-16-intro-skip-mode.md`;
 * [IntroAutoSkipController] implements the tables in its "Prompt behaviour"
 * section and its tests assert them case for case.
 */
enum class IntroSkipMode {
    /** Entering an intro does nothing: no pill, no skip. */
    NEVER,

    /** Offer a "Skip Intro" pill for [IntroAutoSkipController.totalCountdownSeconds]. */
    ASK,

    /** Skip immediately and offer an "Intro skipped — Watch Intro" undo. */
    ALWAYS,
    ;

    /** The contract's enum member spelling. */
    val wireValue: String
        get() = when (this) {
            NEVER -> "never"
            ASK -> "ask"
            ALWAYS -> "always"
        }

    companion object {
        /**
         * The contract default. Identical to what the deprecated
         * `playback.auto_skip_intro = false` always did, so an untouched
         * profile behaves the same across the cutover.
         */
        val Default: IntroSkipMode = ASK

        /** Parses a stored/wire value; null for absent or unrecognized input. */
        fun fromWire(value: String?): IntroSkipMode? = when (value) {
            "never" -> NEVER
            "ask" -> ASK
            "always" -> ALWAYS
            else -> null
        }

        /**
         * The lossy compatibility direction, for a server whose contract
         * predates revision 7 and therefore only answers the boolean. It cannot
         * produce [NEVER] — nobody could express it before this cut.
         */
        fun fromLegacyBoolean(autoSkip: Boolean): IntroSkipMode = if (autoSkip) ALWAYS else ASK
    }
}
