package org.siloserver.silo.domain.player

/**
 * What Silo does on Auto when playback keeps buffering —
 * `playback.buffering_quality_mode`.
 *
 * The spec is the server repo's `docs/design/2026-10-17-buffering-quality.md`;
 * [BufferingQualityController] implements its *Behaviour* tables and
 * `BufferingQualityVectorsTest` replays its conformance vectors.
 */
enum class BufferingQualityMode {
    /** Detect and report stalls only. Requests also send `learned_limit: ignore`. */
    NEVER,

    /** Offer "Lower quality" / "Keep quality" before changing anything. */
    ASK,

    /**
     * Lower Auto at once and offer a "Restore quality" undo. A quality the
     * viewer picked still gets the prompt first.
     */
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
        /** The contract default. */
        val Default: BufferingQualityMode = ALWAYS

        /** Parses a stored/wire value; null for absent or unrecognized input. */
        fun fromWire(value: String?): BufferingQualityMode? = when (value) {
            "never" -> NEVER
            "ask" -> ASK
            "always" -> ALWAYS
            else -> null
        }
    }
}
