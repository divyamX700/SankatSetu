package com.sankatsetu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark is the operating default — the real usage scene is a phone at low
// battery, at night or indoors in poor light (see PRODUCT.md's Operating
// Context) — rendered as a night ledger: warm near-black page surfaces, a
// dim khaki rule line standing in for a printed ledger rule. Light stays
// fully designed, not an afterthought, since a judge's phone may be in
// either mode. Dynamic Color (Material You) stays off, unchanged from
// prior passes: a demo phone should show this app's own palette, not
// per-device wallpaper theming. See docs/adr/0019-ledger-register-redesign.md.
private val DarkColors = darkColorScheme(
    primary = SankatSetuColors.SignalBlueDark,
    onPrimary = Color(0xFF191D4A),
    primaryContainer = Color(0xFF2C3060),
    onPrimaryContainer = Color(0xFFDBDDFF),
    secondary = SankatSetuColors.InkMutedOnDark,
    onSecondary = SankatSetuColors.SurfaceBase,
    secondaryContainer = SankatSetuColors.SurfaceOverlayHigh,
    onSecondaryContainer = SankatSetuColors.InkOnDark,
    tertiary = SankatSetuColors.StatusCaution,
    onTertiary = Color(0xFF3A2900),
    tertiaryContainer = Color(0xFF564000),
    onTertiaryContainer = Color(0xFFFFE3AD),
    error = SankatSetuColors.StatusCritical,
    onError = Color(0xFF3A0603),
    errorContainer = Color(0xFF57180F),
    onErrorContainer = Color(0xFFFFDAD2),
    background = SankatSetuColors.SurfaceBase,
    onBackground = SankatSetuColors.InkOnDark,
    surface = SankatSetuColors.SurfaceRaised,
    onSurface = SankatSetuColors.InkOnDark,
    surfaceVariant = SankatSetuColors.SurfaceOverlay,
    onSurfaceVariant = SankatSetuColors.InkMutedOnDark,
    surfaceContainer = SankatSetuColors.SurfaceRaised,
    surfaceContainerHigh = SankatSetuColors.SurfaceOverlay,
    surfaceContainerHighest = SankatSetuColors.SurfaceOverlayHigh,
    surfaceContainerLow = SankatSetuColors.SurfaceBase,
    surfaceContainerLowest = SankatSetuColors.SurfaceBase,
    outline = SankatSetuColors.HairlineOnDark,
    outlineVariant = SankatSetuColors.HairlineOnDark
)

private val LightColors = lightColorScheme(
    primary = SankatSetuColors.SignalBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE1F7),
    onPrimaryContainer = Color(0xFF20245A),
    secondary = SankatSetuColors.InkMutedOnLight,
    onSecondary = Color.White,
    secondaryContainer = SankatSetuColors.SurfaceOverlayHighLight,
    onSecondaryContainer = SankatSetuColors.InkOnLight,
    tertiary = SankatSetuColors.StatusCaution,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6E2BB),
    onTertiaryContainer = Color(0xFF453000),
    error = SankatSetuColors.StatusCritical,
    onError = Color.White,
    errorContainer = Color(0xFFFAD8D2),
    onErrorContainer = Color(0xFF450F09),
    background = SankatSetuColors.SurfaceBaseLight,
    onBackground = SankatSetuColors.InkOnLight,
    surface = SankatSetuColors.SurfaceRaisedLight,
    onSurface = SankatSetuColors.InkOnLight,
    surfaceVariant = SankatSetuColors.SurfaceOverlayHighLight,
    onSurfaceVariant = SankatSetuColors.InkMutedOnLight,
    surfaceContainer = SankatSetuColors.SurfaceRaisedLight,
    surfaceContainerHigh = SankatSetuColors.SurfaceOverlayHighLight,
    surfaceContainerHighest = Color(0xFFE7E8EB),
    surfaceContainerLow = SankatSetuColors.SurfaceBaseLight,
    surfaceContainerLowest = Color.White,
    outline = SankatSetuColors.HairlineOnLight,
    outlineVariant = SankatSetuColors.HairlineOnLight
)

@Composable
fun SankatSetuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SankatSetuTypography,
        shapes = SankatSetuShapes,
        content = content
    )
}
