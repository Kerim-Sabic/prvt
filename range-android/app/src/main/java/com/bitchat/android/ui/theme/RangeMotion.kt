package com.bitchat.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings

/**
 * range — motion.
 *
 * Motion exists to explain causality: a message leaves the composer and *becomes* a row in the
 * transcript; a route changes and the glyph that represents it changes with it. Motion that is
 * not attached to a real state change does not belong in this app.
 *
 * Explicitly rejected: ambient looping animation in the transcript, spinning network visuals,
 * confetti, elastic overshoot on ordinary controls, and anything that delays input.
 */
object RangeMotion {
    /** Icon tints, small fills, text colour crossfades. Below this, motion reads as a glitch. */
    const val MICRO_MS = 120

    /** Chip reveals, badge changes, selection indicators. */
    const val FAST_MS = 180

    /** Sheet fades, list item insertion, most state transitions. */
    const val STANDARD_MS = 240

    /** Emphasis: delivery-state changes the user should notice, verification success. */
    const val EMPHASIS_MS = 360

    /** Navigation between top-level destinations. */
    const val NAVIGATION_MS = 420

    /**
     * Standard easing. Enters quickly, settles gently — the shape of something arriving
     * under its own momentum rather than being dragged.
     */
    val Easing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /** For elements leaving: accelerate away, no lingering. */
    val EasingExit: Easing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

    /**
     * The one spring in the system, used where a physical hand-off is being expressed —
     * chiefly the composer-to-transcript send. Damped so it never visibly overshoots twice.
     */
    fun <T> sendSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> micro(): FiniteAnimationSpec<T> = tween(MICRO_MS, easing = Easing)
    fun <T> fast(): FiniteAnimationSpec<T> = tween(FAST_MS, easing = Easing)
    fun <T> standard(): FiniteAnimationSpec<T> = tween(STANDARD_MS, easing = Easing)
    fun <T> emphasis(): FiniteAnimationSpec<T> = tween(EMPHASIS_MS, easing = Easing)
}

/**
 * True when the user has asked the system to reduce motion.
 *
 * Android exposes this as `Settings.Global.ANIMATOR_DURATION_SCALE == 0`, which is what the
 * accessibility "Remove animations" toggle sets. Callers must substitute an instant state
 * change — not a slower animation — when this is true.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Duration helper that collapses to zero under Reduce Motion, so a single call site can honour
 * the accessibility setting without branching at every animation.
 */
@Composable
fun rangeDuration(durationMs: Int): Int = if (rememberReduceMotion()) 0 else durationMs
