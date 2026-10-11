package com.bitchat.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * App-specific colour semantics that have no faithful Material 3 role.
 *
 * Standard backgrounds, surfaces, text, outlines, primary/secondary accents and errors belong to
 * [androidx.compose.material3.MaterialTheme.colorScheme]. Only the extra range semantics live
 * here, so Material components inherit correct defaults without losing range's identity.
 *
 * Every value is derived from [RangeTokens] — that file is the single source of truth for
 * colour in range. Do not introduce literal colours here.
 */
@Immutable
data class BitchatPalette(
    // MARK: - Form controls
    /**
     * Resting border for text inputs. Deliberately a neutral grey rather than a signal-tinted
     * outline: the composer is the one surface the user stares at while typing, and a coloured
     * border there competes with the delivery-state colours that actually carry meaning.
     */
    val inputOutline: Color,
    /** Border for a focused text input. A step brighter, still neutral. */
    val inputOutlineFocused: Color,
    /** Fill for text inputs. Untinted, so it does not compound with the surface beneath it. */
    val inputSurface: Color,
    /** Fill for a focused text input. A barely perceptible lift. */
    val inputSurfaceFocused: Color,
    /** Resting disc behind the composer's action glyphs. Neutral grey. */
    val inputButton: Color,

    // MARK: - Extra semantics
    /** Timestamps, placeholders, section labels, disabled states. */
    val textTertiary: Color,
    /**
     * You: your own messages, mentions targeting you, unread direct messages.
     *
     * Amber, matching range's "needs your attention" semantic. Deliberately *not* signal green —
     * green is reserved for actionable controls, and a transcript where your own name is the
     * same colour as the send button reads as though every line were a button.
     */
    val accentAttention: Color,
    /**
     * Reachable over an internet relay (Nostr) rather than the local mesh.
     *
     * Shares the amber family with [accentAttention] but is applied only to reachability
     * glyphs, never to text, so the two never appear in the same role. Route colour is always
     * accompanied by a glyph — see the route legend in About — because colour alone must never
     * carry state.
     */
    val accentRelay: Color,

    // MARK: - Deterministic peer colors
    /**
     * Saturation/value applied after deriving a peer's stable hue. Swap this when adding a
     * new theme — see [PeerColorStyle] for contrast guidelines.
     */
    val peerColors: PeerColorStyle,
)

val DarkBitchatPalette = BitchatPalette(
    inputOutline = Color(0xFF272C2A),
    inputOutlineFocused = Color(0xFF4A524E),
    inputSurface = RangeDarkTokens.surface,
    inputSurfaceFocused = RangeDarkTokens.surfaceElevated,
    inputButton = RangeDarkTokens.surfaceInteractive,
    textTertiary = RangeDarkTokens.textTertiary,
    accentAttention = RangeDarkTokens.relayInternet,
    accentRelay = RangeDarkTokens.relayInternet,
    peerColors = PeerColorStyle.Dark,
)

val LightBitchatPalette = BitchatPalette(
    inputOutline = Color(0xFFD6DAD5),
    inputOutlineFocused = Color(0xFF8E9490),
    inputSurface = RangeLightTokens.surface,
    inputSurfaceFocused = RangeLightTokens.surfaceInteractive,
    inputButton = RangeLightTokens.surfaceInteractive,
    textTertiary = RangeLightTokens.textTertiary,
    accentAttention = RangeLightTokens.relayInternet,
    accentRelay = RangeLightTokens.relayInternet,
    peerColors = PeerColorStyle.Light,
)

val LocalBitchatPalette = staticCompositionLocalOf { DarkBitchatPalette }

/**
 * Motion tokens.
 *
 * Retained as a thin alias over [RangeMotion] so existing call sites keep working; new code
 * should use [RangeMotion] directly, which also carries easing curves and the Reduce Motion
 * helper.
 */
object BitchatMotion {
    /** Icon tints, text colors, small fills. */
    const val QUICK_MS = RangeMotion.MICRO_MS

    /** Tab indicators, pill growth, chip reveals. */
    const val STANDARD_MS = RangeMotion.FAST_MS

    /** Sheet-level fades and scroll-driven top bars. */
    const val EMPHASIZED_MS = RangeMotion.STANDARD_MS
}
