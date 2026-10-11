package com.bitchat.android.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R

/**
 * The default range UI family: the platform's own proportional face.
 *
 * This is the single most consequential line in the redesign. The app previously set a bundled
 * monospace family here and then hard-coded `fontFamily = <this>` at ~300 call sites, so *every*
 * surface — navigation, settings, onboarding, message bodies, permission explanations — rendered
 * as monospace. The result read as terminal cosplay, cost roughly 15% horizontal density at the
 * same point size, and made long-form explanatory copy genuinely harder to read.
 *
 * Using the system face instead means range inherits the user's own font scaling, language
 * shaping, and any accessibility face they have chosen — which no bundled font can do. Monospace
 * is still used, deliberately and narrowly, via [RangeMono].
 */
internal val RangeSans = FontFamily.Default

/**
 * The bundled Geist Mono family, reserved for text where character-level precision is the point:
 * geohashes, key fingerprints, relay addresses, protocol versions, and diagnostic identifiers.
 *
 * Keeping the fonts in the APK preserves offline behaviour and guarantees that these metrics do
 * not depend on which monospace family a device happens to provide — which matters here, because
 * a user comparing a fingerprint in person needs stable, unambiguous glyphs.
 */
internal val RangeMono = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
    Font(R.font.geist_mono_bold, FontWeight.Bold),
)

/** Exact typography, spacing, and opacity values exported for the chat transcript. */
internal object ChatVisualTokens {
    val MessageBodyFontSize: TextUnit = 14.sp
    val MessageBodyLineHeight: TextUnit = 20.sp
    val SenderFontSize: TextUnit = 14.sp
    val SenderLineHeight: TextUnit = 16.sp
    val SystemActionFontSize: TextUnit = 12.sp
    val SystemActionLineHeight: TextUnit = 16.sp
    val SystemTimeFontSize: TextUnit = 10.sp

    val MessageItemSpacing: Dp = 8.dp
    val SenderTopPadding: Dp = 8.dp
    val SenderToBodySpacing: Dp = 4.dp

    const val SenderSuffixAlpha: Float = 0.60f
    const val HighlightAlpha: Float = 0.20f
    const val MutedTextAlpha: Float = 0.50f

    val MessageBodyStyle = TextStyle(
        fontFamily = RangeSans,
        fontWeight = FontWeight.Normal,
        fontSize = MessageBodyFontSize,
        lineHeight = MessageBodyLineHeight,
    )

    val SenderStyle = TextStyle(
        fontFamily = RangeSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = SenderFontSize,
        lineHeight = SenderLineHeight,
    )

    val SystemActionStyle = TextStyle(
        fontFamily = RangeSans,
        fontWeight = FontWeight.Medium,
        fontSize = SystemActionFontSize,
        lineHeight = SystemActionLineHeight,
    )

    /**
     * Technical identifiers shown inline in otherwise proportional text — a geohash in a header,
     * a relay host in a settings row.
     *
     * Slightly smaller than body text because monospace carries more optical weight at the same
     * nominal size, so matching the numbers would make it visually louder than the sentence
     * around it.
     */
    val TechnicalStyle = TextStyle(
        fontFamily = RangeMono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /**
     * Key fingerprints during in-person verification. Larger and loosely tracked, because the
     * user is comparing it character by character against another device — this is a security
     * ceremony, and legibility here is a security property, not a style choice.
     */
    val FingerprintStyle = TextStyle(
        fontFamily = RangeMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 1.5.sp,
    )
}
