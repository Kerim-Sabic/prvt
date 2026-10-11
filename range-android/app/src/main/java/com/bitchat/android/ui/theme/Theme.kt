package com.bitchat.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

/**
 * Standard UI semantics live in Material so stock components and custom range composables share
 * one source of truth; [LocalBitchatPalette] supplies only the extra app semantics, and
 * [LocalRangeTokens] exposes the canonical range palette to newer components.
 *
 * The mapping from range tokens onto Material roles is deliberate:
 *
 *  - `primary`   → range signal green. The app's actionable voice.
 *  - `secondary` → mesh cyan. Reserved for "this travelled over local Bluetooth".
 *  - `tertiary`  → relay amber. Internet relay, queued, couriered, attention.
 *  - `error`     → range destructive red.
 *
 * Surfaces are intentionally *untinted*. The previous scheme tinted every surface green
 * (`surface` #0E150E, `outline` #2A3A2A), which read as terminal cosplay and, more practically,
 * muddied every accent drawn on top of it. Neutral near-blacks let the three route colours stay
 * distinguishable, which is the one thing this UI must never lose.
 */
internal val DarkBitchatColorScheme = darkColorScheme(
    primary = RangeDarkTokens.signal,
    onPrimary = RangeDarkTokens.onSignal,
    primaryContainer = Color(0xFF2A3A12),
    onPrimaryContainer = Color(0xFFE4FFB0),
    secondary = RangeDarkTokens.meshLocal,
    onSecondary = Color(0xFF002A31),
    secondaryContainer = Color(0xFF0B3A42),
    onSecondaryContainer = Color(0xFFC4F1FA),
    tertiary = RangeDarkTokens.relayInternet,
    onTertiary = Color(0xFF2B1A00),
    background = RangeDarkTokens.canvas,
    onBackground = RangeDarkTokens.textPrimary,
    surface = RangeDarkTokens.surface,
    onSurface = RangeDarkTokens.textPrimary,
    surfaceVariant = RangeDarkTokens.surfaceElevated,
    onSurfaceVariant = RangeDarkTokens.textSecondary,
    outline = Color(0xFF2A302E),
    outlineVariant = Color(0xFF1C211F),
    error = RangeDarkTokens.destructive,
    onError = Color(0xFF2B0000)
)

internal val LightBitchatColorScheme = lightColorScheme(
    primary = RangeLightTokens.signal,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6F5C2),
    onPrimaryContainer = Color(0xFF1E2E00),
    secondary = RangeLightTokens.meshLocal,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9EDF4),
    onSecondaryContainer = Color(0xFF002A31),
    tertiary = RangeLightTokens.relayInternet,
    onTertiary = Color(0xFFFFFFFF),
    background = RangeLightTokens.canvas,
    onBackground = RangeLightTokens.textPrimary,
    surface = RangeLightTokens.surface,
    onSurface = RangeLightTokens.textPrimary,
    surfaceVariant = RangeLightTokens.surfaceInteractive,
    onSurfaceVariant = RangeLightTokens.textSecondary,
    outline = Color(0xFFC9CEC8),
    outlineVariant = Color(0xFFE2E6DF),
    error = RangeLightTokens.destructive,
    onError = Color(0xFFFFFFFF)
)

@Composable
fun BitchatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkBitchatColorScheme else LightBitchatColorScheme
    val palette = if (shouldUseDark) DarkBitchatPalette else LightBitchatPalette
    val tokens = if (shouldUseDark) RangeDarkTokens else RangeLightTokens

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    CompositionLocalProvider(
        LocalBitchatPalette provides palette,
        LocalRangeTokens provides tokens,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/**
 * Preferred name for the app theme. [BitchatTheme] is retained because it is referenced from the
 * activity layer and from previews across the module; both resolve to the same composition.
 */
@Composable
fun RangeTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) = BitchatTheme(darkTheme = darkTheme, content = content)
