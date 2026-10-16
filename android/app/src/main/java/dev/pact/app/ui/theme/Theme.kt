package dev.pact.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pact.app.core.Palette
import dev.pact.app.core.Themes
import dev.pact.app.core.hexColor
import dev.pact.app.data.Settings

val LocalPalette = staticCompositionLocalOf<Palette> { error("no palette") }

fun color(hex: String) = Color(hexColor(hex))

/** Maps a Pact palette (spec/themes.json) onto a Material 3 color scheme. */
fun schemeFrom(p: Palette, dark: Boolean): ColorScheme {
    val bg = color(p.bg)
    val bg1 = color(p.bg1)
    val bg2 = color(p.bg2)
    val fg = color(p.fg)
    val primary = color(p.primary)
    val secondary = color(p.aqua)
    val tertiary = color(p.purple)
    val red = color(p.red)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = bg,
        primaryContainer = lerp(bg, primary, 0.25f), onPrimaryContainer = fg,
        inversePrimary = primary,
        secondary = secondary, onSecondary = bg,
        secondaryContainer = lerp(bg, primary, 0.2f), onSecondaryContainer = fg,
        tertiary = tertiary, onTertiary = bg,
        tertiaryContainer = lerp(bg, tertiary, 0.22f), onTertiaryContainer = fg,
        background = bg, onBackground = fg,
        surface = bg, onSurface = fg,
        surfaceVariant = bg2, onSurfaceVariant = color(p.muted),
        surfaceTint = primary,
        inverseSurface = fg, inverseOnSurface = bg,
        error = red, onError = bg, errorContainer = lerp(bg, red, 0.25f), onErrorContainer = fg,
        outline = color(p.muted), outlineVariant = bg2,
        surfaceBright = bg2, surfaceDim = bg,
        surfaceContainerLowest = bg, surfaceContainerLow = lerp(bg, bg1, 0.5f),
        surfaceContainer = bg1, surfaceContainerHigh = lerp(bg1, bg2, 0.5f), surfaceContainerHighest = bg2,
    )
}

fun isDark(settings: Settings, system: Boolean) = when (settings.themeMode) {
    "dark" -> true
    "light" -> false
    else -> system
}

@Composable
fun PactTheme(settings: Settings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isDark(settings, isSystemInDarkTheme())
    val def = remember(settings.theme) { Themes.get(context, settings.theme) }
    val palette = if (dark) def.dark else def.light
    val scheme = remember(palette, dark) { schemeFrom(palette, dark) }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(32.dp),
            ),
            content = content,
        )
    }
}

@Composable
fun groupColor(key: String): Color = color(LocalPalette.current.named(key))

@Composable
fun priorityColor(p: Int): Color = LocalPalette.current.let {
    when (p) {
        1 -> color(it.red)
        2 -> color(it.orange)
        3 -> color(it.blue)
        else -> color(it.muted)
    }
}
