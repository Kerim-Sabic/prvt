package com.bitchat.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * range — canonical design tokens.
 *
 * Concept: LIVING SIGNAL. Communication is a signal finding its way through nearby people,
 * intermediate devices, and distant relays. The palette encodes *where a message travelled*,
 * not merely decoration.
 *
 * Accent semantics are load-bearing and must stay stable across the app:
 *
 *  - [signal]   signal green  — primary actionable state, the app's voice
 *  - [meshLocal]      cyan    — this hop happened over local Bluetooth mesh
 *  - [relayInternet]  amber   — internet relay, queued, couriered, needs attention
 *  - [verified]       green   — verified identity, delivered
 *  - [destructive]    red     — destructive, failed, recording, serious warning
 *
 * Colour is never the sole carrier of state — every colour here has a paired glyph and a
 * text label in the UI layer (see RangeRouteGlyph). Changing a value here changes meaning
 * app-wide, so treat these as a contract rather than a palette.
 */
@Immutable
data class RangeTokens(
    // MARK: - Surfaces
    /** The deepest background. Near-black, deliberately untinted. */
    val canvas: Color,
    /** Default raised surface: sheets, cards that genuinely group. */
    val surface: Color,
    /** A surface sitting above [surface] — popovers, menus. */
    val surfaceElevated: Color,
    /** Pressed / hovered / selected fill for interactive rows. */
    val surfaceInteractive: Color,
    /** Hairline separators. Low-contrast by design; never stack with a divider. */
    val borderSubtle: Color,
    /** A border that must read as a real edge (focused input, selected chip). */
    val borderStrong: Color,

    // MARK: - Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Text drawn on top of a [signal]-filled surface. */
    val onSignal: Color,

    // MARK: - Signal semantics
    val signal: Color,
    /** A dimmed signal, for large fills where full-strength signal would shout. */
    val signalMuted: Color,
    /**
     * Fill for the one primary call-to-action on a screen, with [onCtaFill] as its label.
     *
     * Held at full-strength signal green in *both* schemes. This is deliberate and is the app's
     * strongest brand signature: a bright lime slab with near-black text, identical in light and
     * dark, at roughly 13:1 contrast.
     *
     * It exists as a separate token because [signal] cannot do this job. [signal] has to stay
     * legible as text and as an icon tint on a near-white canvas, which forces it down to an
     * olive in light mode — and an olive button reads as generic Android green, not as range.
     * Splitting the two lets each be correct at its own job.
     */
    val ctaFill: Color,
    val onCtaFill: Color,
    val meshLocal: Color,
    val relayInternet: Color,
    val verified: Color,
    val warning: Color,
    val destructive: Color,
) {
    /** True when this is the dark scheme. Used for elevation and scrim decisions. */
    val isDark: Boolean get() = canvas.luminance() < 0.5f
}

private fun Color.luminance(): Float = (0.299f * red + 0.587f * green + 0.114f * blue)

/**
 * Dark is range's primary appearance — the product is used outdoors, at night, at protests,
 * in blackouts, and on a battery that matters. The canvas is a true near-black so OLED panels
 * can actually switch pixels off, and it is untinted: the previous green-tinted surfaces made
 * every accent muddy.
 */
val RangeDarkTokens = RangeTokens(
    canvas = Color(0xFF090B0B),
    surface = Color(0xFF101313),
    surfaceElevated = Color(0xFF171B1A),
    surfaceInteractive = Color(0xFF1E2422),
    borderSubtle = Color(0x14FFFFFF),
    borderStrong = Color(0x33FFFFFF),

    textPrimary = Color(0xFFF4F6F2),
    textSecondary = Color(0xFF9AA39F),
    textTertiary = Color(0xFF68716D),
    onSignal = Color(0xFF0B1400),

    signal = Color(0xFFC8FF5A),
    signalMuted = Color(0xFF7E9E3A),
    ctaFill = Color(0xFFC8FF5A),
    onCtaFill = Color(0xFF0B1400),
    meshLocal = Color(0xFF62D6E8),
    relayInternet = Color(0xFFFFB454),
    verified = Color(0xFF74D49B),
    warning = Color(0xFFFFB454),
    destructive = Color(0xFFFF6262),
)

/**
 * Light is designed, not inverted. Mechanically flipping the dark palette produces the
 * classic failure where a neon accent becomes illegible on white: #C8FF5A on a white canvas
 * is roughly 1.4:1 and unreadable as text.
 *
 * So the *hues* carry over but the accents are re-derived at a luminance that clears WCAG AA
 * (4.5:1) against [canvas] when used as text or as an icon carrying meaning. The bright
 * signal green survives only as a *fill* colour, where [onSignal] supplies dark text on top.
 */
val RangeLightTokens = RangeTokens(
    canvas = Color(0xFFFBFCFA),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceInteractive = Color(0xFFF1F4EF),
    borderSubtle = Color(0x14000000),
    borderStrong = Color(0x33000000),

    textPrimary = Color(0xFF12150F),
    textSecondary = Color(0xFF5A625C),
    textTertiary = Color(0xFF868E88),
    onSignal = Color(0xFF0B1400),

    // Re-derived for contrast on a near-white canvas rather than reused from dark.
    signal = Color(0xFF4E7A00),
    signalMuted = Color(0xFF6E9A22),
    // Identical to dark on purpose — see the note on RangeTokens.ctaFill.
    ctaFill = Color(0xFFC8FF5A),
    onCtaFill = Color(0xFF0B1400),
    meshLocal = Color(0xFF0E6E80),
    relayInternet = Color(0xFF8F5000),
    verified = Color(0xFF1F7A4D),
    warning = Color(0xFF8F5000),
    destructive = Color(0xFFC02A2A),
)

/**
 * The bright signal green, held constant across both schemes, for use as a *fill* only
 * (send button, primary action). Pair it with [RangeTokens.onSignal] for the label.
 * Never use this as text or as a hairline on a light canvas.
 */
val RangeSignalFill = Color(0xFFC8FF5A)

val LocalRangeTokens = staticCompositionLocalOf { RangeDarkTokens }
