package com.sankatsetu.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sankatsetu.app.R

/**
 * Inter — a variable font, referenced once per weight at the same resource
 * file. `variationSettings` is required here, not decorative: without an
 * explicit `wght` axis value, every `Font()` entry below would render at
 * the file's single default instance regardless of its `weight` parameter
 * (that parameter only affects which entry Compose *picks* for a given
 * `FontWeight` request — it does not itself vary the rendered glyphs of a
 * variable font). See docs/adr/0018-ui-revamp.md.
 *
 * Bundled as a static asset (`res/font/inter_variable.ttf`), never fetched
 * over network — this app's whole premise is working with zero
 * connectivity, so a Google-Fonts network provider was never on the table,
 * only a build-time download of a file that ships in the APK. SIL Open
 * Font License, attributed in NOTICE.md. Chosen (over Android's own
 * Roboto) as the closest free typeface to SF Pro's screen-optimized
 * proportions — see the Apple HIG research this pass is based on.
 */
@OptIn(ExperimentalTextApi::class)
private val Inter = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

/**
 * Apple's real hierarchy technique isn't exotic sizes, it's discipline: a
 * handful of size/weight pairs reused everywhere, with tighter tracking at
 * large sizes and looser tracking at small ones (their own tracking table,
 * approximated here rather than copied verbatim since Apple doesn't publish
 * exact per-style numbers — see the HIG research this maps from).
 */
val SankatSetuTypography = Typography(
    displayLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp)
)

/**
 * The "instrument panel" register — peer IDs, hop counts, timestamps,
 * signal readouts. Reserved narrowly for that data/measurement role per
 * craft-floor's ban on monospace as a generic "technical" costume; body
 * text, first-aid answers, and every other prose surface stay in Inter.
 * Unchanged choice of typeface from the prior pass (docs/adr/0014) — a
 * deliberate one that's still correct, only its surrounding type system
 * changed.
 */
val FieldMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium)
)

val ConsoleReadoutStyle = TextStyle(
    fontFamily = FieldMono,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    letterSpacing = 0.4.sp,
    lineHeight = 16.sp
)
