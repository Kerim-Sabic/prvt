package com.bitchat.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * range — spacing scale.
 *
 * A strict 4/8-point scale. Every gap in the UI must come from this object; a literal `13.dp`
 * in a layout is a bug, because it breaks the vertical rhythm that makes dense technical
 * content feel calm.
 */
object RangeSpacing {
    /** Hairline gaps inside a single control (icon to its own label). */
    val xxs: Dp = 2.dp
    /** Tight pairing: a glyph and the word it modifies. */
    val xs: Dp = 4.dp
    /** Default gap between related elements in a row. */
    val sm: Dp = 8.dp
    /** Gap between a label and its value; internal padding of compact controls. */
    val md: Dp = 12.dp
    /** Standard screen gutter and the padding of most surfaces. */
    val lg: Dp = 16.dp
    /** Separation between distinct groups within a section. */
    val xl: Dp = 24.dp
    /** Separation between sections. */
    val xxl: Dp = 32.dp
    /** Top of an empty state; breathing room around a hero element. */
    val xxxl: Dp = 48.dp

    /** The horizontal gutter used by every full-width screen. */
    val screenGutter: Dp = lg

    /**
     * Minimum touch target. Android's accessibility guidance is 48dp; the brief asks for 44pt.
     * We take the larger of the two so the app satisfies both platforms' guidance.
     */
    val minTouchTarget: Dp = 48.dp
}

/**
 * range — corner radii.
 *
 * Radii are assigned by *role*, not by taste. A control and the surface containing it must not
 * share a radius, or the nesting reads as a single flat blob.
 */
object RangeRadius {
    /** Chips, badges, route glyph backgrounds. */
    val xs: Dp = 6.dp
    /** Buttons, inputs, small interactive cells. */
    val sm: Dp = 10.dp
    /** Cards and grouped rows. */
    val md: Dp = 14.dp
    /** Sheets and large containers. */
    val lg: Dp = 20.dp
    /** Bottom sheets — top corners only, applied by the sheet itself. */
    val sheet: Dp = 28.dp

    val chip = RoundedCornerShape(xs)
    val control = RoundedCornerShape(sm)
    val card = RoundedCornerShape(md)
    val container = RoundedCornerShape(lg)

    /** Fully rounded. For avatars and the jump-to-latest pill only. */
    val pill = RoundedCornerShape(percent = 50)
}
