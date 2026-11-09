package com.bydassistantng.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.bydassistantng.data.UiAccent
import com.bydassistantng.data.UiStyle

/*
 * Two looks for the whole app.
 *
 * Neon (the default since 1.7.0) matches the remote dashboard: a near-black blue page, dark glass surfaces with a
 * faint accent edge, and one glowing accent colour (cyan unless chosen otherwise). It is always dark: it is made for
 * a car screen at night and in a dark cabin, and a glow on a light page looks washed out.
 *
 * Classic is the original calm style: a near-black page, slightly lighter rounded surfaces and a teal accent, light
 * or dark with the system.
 *
 * Both are fixed palettes, not the system's dynamic colours, so the app looks the same on every head unit.
 */

/** What the Neon pieces (the voice orb, glass cards) need beyond the colour scheme. */
@Immutable
data class NeonLook(
    val neon: Boolean = false,
    val accent: Color = Color(0xFF35D1BC),
    val accent2: Color = Color(0xFFB26BFF),
    val glass: Color = Color(0xFF161C21),
    val edge: Color = Color(0x00000000),
    val motion: Boolean = true,
)

val LocalNeonLook = staticCompositionLocalOf { NeonLook() }

private val ClassicDark = darkColorScheme(
    primary = Color(0xFF35D1BC),
    onPrimary = Color(0xFF00150B),
    primaryContainer = Color(0xFF0F4F46),
    onPrimaryContainer = Color(0xFFB6F5EA),
    secondaryContainer = Color(0xFF252D33),
    onSecondaryContainer = Color(0xFFEFF7FE),
    background = Color(0xFF0C0E10),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0C0E10),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF252D33),
    onSurfaceVariant = Color(0xFFB9BFC6),
    surfaceContainer = Color(0xFF161C21),
    surfaceContainerHigh = Color(0xFF1C242A),
    surfaceContainerHighest = Color(0xFF252D33),
    outline = Color(0xFF39434D),
    outlineVariant = Color(0xFF1E2429),
    error = Color(0xFFFFB4AB),
)

private val ClassicLight = lightColorScheme(
    primary = Color(0xFF0E8F7D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBFF0E6),
    onPrimaryContainer = Color(0xFF00201B),
    secondaryContainer = Color(0xFFE4EBEE),
    onSecondaryContainer = Color(0xFF11181C),
    background = Color(0xFFF2F5F6),
    onBackground = Color(0xFF11181C),
    surface = Color(0xFFF2F5F6),
    onSurface = Color(0xFF11181C),
    surfaceVariant = Color(0xFFE4EBEE),
    onSurfaceVariant = Color(0xFF59656D),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE4EBEE),
    outline = Color(0xFFB4C0C6),
    outlineVariant = Color(0xFFE2E8EB),
    error = Color(0xFFB3261E),
)

/** The Neon palette around one [accent]: the page and surfaces stay the same, the accent and its tints follow. */
internal fun neonColors(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color(0xFF02040A),
    primaryContainer = accent.copy(alpha = 0.18f).compositeOver(Color(0xFF0B0F18)),
    onPrimaryContainer = Color(0xFFE6FBFF),
    secondary = Color(0xFFB26BFF),
    onSecondary = Color(0xFF0B0418),
    tertiary = Color(0xFF2BFF9A),
    onTertiary = Color(0xFF00150B),
    secondaryContainer = Color(0xFF151B2A),
    onSecondaryContainer = Color(0xFFE8EEFA),
    background = Color(0xFF05070D),
    onBackground = Color(0xFFF4F6FB),
    surface = Color(0xFF05070D),
    onSurface = Color(0xFFF4F6FB),
    surfaceVariant = Color(0xFF141824),
    onSurfaceVariant = Color(0xFFA6ADBB),
    surfaceContainer = Color(0xFF0D111B),
    surfaceContainerHigh = Color(0xFF121726),
    surfaceContainerHighest = Color(0xFF181E2E),
    outline = accent.copy(alpha = 0.32f).compositeOver(Color(0xFF0D111B)),
    outlineVariant = Color(0xFF1A2030),
    error = Color(0xFFFF4D5E),
)

@Composable
fun AppTheme(
    style: UiStyle = UiStyle.NEON,
    accent: UiAccent = UiAccent.CYAN,
    motion: Boolean = true,
    largeText: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val neon = style == UiStyle.NEON
    val accentColor = Color(accent.argb)
    val scheme = when {
        neon -> neonColors(accentColor)
        darkTheme -> ClassicDark
        else -> ClassicLight
    }
    val look = if (neon) {
        NeonLook(neon = true, accent = accentColor, accent2 = if (accent == UiAccent.PURPLE) Color(0xFF3D6BFF) else Color(0xFFB26BFF),
            glass = Color(0xFF0D111B), edge = accentColor.copy(alpha = 0.16f), motion = motion)
    } else {
        NeonLook(neon = false, accent = scheme.primary, accent2 = scheme.primary, glass = scheme.surfaceContainer, motion = motion)
    }
    // Larger text: the whole app's text, one step up, for reading at a glance from the driver's seat.
    val density = LocalDensity.current
    val scaled = if (largeText) Density(density.density, density.fontScale * 1.15f) else density
    CompositionLocalProvider(LocalNeonLook provides look, LocalDensity provides scaled) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
