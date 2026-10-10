package org.siloserver.silo.common.ui.marquee

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.koin.dsl.module

/**
 * Where the first-run flow is, shared by every first-run screen. One
 * [MarqueeBackdrop] sits behind the whole flow and survives screen changes;
 * screens describe the flow through this object and the backdrop moves
 * between those states instead of cutting.
 */
object MarqueeScene {
    enum class Focus {
        /** Server and sign-in screens. */
        Account,

        /** Profile selection and PIN. */
        Profiles,
    }

    /** False until a server is confirmed. */
    var serverConfirmed by mutableStateOf(false)
        private set

    /** The confirmed server's accent, clamped to a dark, low-saturation tint. */
    var accent by mutableStateOf<Color?>(null)
        private set

    var focus by mutableStateOf(Focus.Account)

    /** Overrides [accent] on screens about one person (PIN, a pressed profile). */
    var personalTint by mutableStateOf<Color?>(null)

    /** 0 choosing a server, 1 signing in, 2 choosing a profile. */
    val stage: Float
        get() = when {
            !serverConfirmed -> 0f
            focus == Focus.Profiles -> 2f
            else -> 1f
        }

    /** Applies a confirmed server's branding. Called from the cache first, then after the server answers. */
    fun showServer(branding: ServerBranding?) {
        serverConfirmed = true
        accent = branding?.accentColor?.let(::clampedAccent)
    }

    /** Back to the pre-connect state (sign out, change server). */
    fun showGeneric() {
        serverConfirmed = false
        accent = null
        focus = Focus.Account
        personalTint = null
    }

    /**
     * Admin-chosen accents can be anything; keep the tint dark enough that
     * form text over it stays legible.
     */
    fun clampedAccent(hex: String): Color? {
        val trimmed = hex.trim().removePrefix("#")
        if (!Regex("^[0-9A-Fa-f]{6}$").matches(trimmed)) return null
        val rgb = trimmed.toInt(16)
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, hsv)
        hsv[1] = hsv[1].coerceAtMost(0.7f)
        hsv[2] = hsv[2].coerceIn(0.45f, 0.8f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}

/** Branding cache and loader for the first-run screens on phone and TV. */
val marqueeModule = module {
    single { ServerBrandingCache(get()) }
    single { ServerBrandingLoader(get(), get()) }
}
