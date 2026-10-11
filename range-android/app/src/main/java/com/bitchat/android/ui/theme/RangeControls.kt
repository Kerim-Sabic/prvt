package com.bitchat.android.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Colours for the single primary call-to-action on a screen.
 *
 * Use this instead of `ButtonDefaults.buttonColors(containerColor = colorScheme.primary)`.
 * Material's `primary` has to remain legible as an icon tint and as text on a near-white canvas,
 * which forces it to an olive in light mode; filling a button with that olive is what makes an
 * app look like an untouched Material sample. range's primary action is instead a full-strength
 * signal slab with near-black text, identical in both schemes.
 *
 * Reserve it for the *one* action that advances the user. A screen with three lime buttons has
 * no primary action.
 */
@Composable
fun rangePrimaryButtonColors(): ButtonColors {
    val tokens = LocalRangeTokens.current
    return ButtonDefaults.buttonColors(
        containerColor = tokens.ctaFill,
        contentColor = tokens.onCtaFill,
        disabledContainerColor = tokens.surfaceInteractive,
        disabledContentColor = tokens.textTertiary,
    )
}

/**
 * Colours for a secondary action sitting next to a primary one — "Continue without
 * notifications", "Not now". Reads as a real control rather than as a disabled primary.
 */
@Composable
fun rangeSecondaryButtonColors(): ButtonColors {
    val tokens = LocalRangeTokens.current
    return ButtonDefaults.buttonColors(
        containerColor = tokens.surfaceInteractive,
        contentColor = tokens.textPrimary,
        disabledContainerColor = tokens.surfaceInteractive,
        disabledContentColor = tokens.textTertiary,
    )
}

/**
 * Colours for a destructive confirmation — panic wipe, clearing a conversation.
 *
 * Red as a *fill* is reserved for the confirmation step itself, never for the control that merely
 * opens the confirmation, so the irreversible tap is visually distinct from the one before it.
 */
@Composable
fun rangeDestructiveButtonColors(): ButtonColors {
    val tokens = LocalRangeTokens.current
    return ButtonDefaults.buttonColors(
        containerColor = tokens.destructive,
        contentColor = Color.White,
        disabledContainerColor = tokens.surfaceInteractive,
        disabledContentColor = tokens.textTertiary,
    )
}
