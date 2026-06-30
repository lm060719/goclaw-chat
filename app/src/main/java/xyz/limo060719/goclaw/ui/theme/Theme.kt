package xyz.limo060719.goclaw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Accent
private val Indigo = Color(0xFF8B88FF)
private val IndigoBright = Color(0xFF7C78F5)
private val UserBubble = Color(0xFF5F5CDA)

// Dark surfaces (near-black, slightly blue)
private val Bg = Color(0xFF07080D)
private val Surface0 = Color(0xFF0B0D13)
private val Surface1 = Color(0xFF13151D)
private val Surface2 = Color(0xFF181B24)
private val Surface3 = Color(0xFF1E212C)

private val TextHigh = Color(0xFFEAEBF2)
private val TextMid = Color(0xFFA6A9B8)
private val OutlineCol = Color(0xFF2A2E3B)

private val DarkColors = darkColorScheme(
    primary = Indigo,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = UserBubble,
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = IndigoBright,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Surface3,
    onSecondaryContainer = TextHigh,
    tertiary = Indigo,
    tertiaryContainer = Surface2,
    onTertiaryContainer = TextHigh,
    background = Bg,
    onBackground = TextHigh,
    surface = Surface0,
    onSurface = TextHigh,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMid,
    surfaceTint = Indigo,
    surfaceContainerLowest = Bg,
    surfaceContainerLow = Surface0,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface3,
    outline = OutlineCol,
    outlineVariant = Color(0xFF20232E),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A1010),
    errorContainer = Color(0xFF3A1E1E),
    onErrorContainer = Color(0xFFFFD6D6),
    scrim = Color(0xFF000000),
)

private val IndigoLight = Color(0xFF5B57E0)
private val LightColors = lightColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFDCFF),
    onPrimaryContainer = Color(0xFF1A1640),
    secondary = IndigoLight,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9E8F2),
    onSecondaryContainer = Color(0xFF1B1B22),
    tertiary = IndigoLight,
    tertiaryContainer = Color(0xFFEDEDF4),
    onTertiaryContainer = Color(0xFF1B1B22),
    background = Color(0xFFF6F6FA),
    onBackground = Color(0xFF18181C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18181C),
    surfaceVariant = Color(0xFFEDEDF2),
    onSurfaceVariant = Color(0xFF5B5C66),
    surfaceTint = IndigoLight,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F4F8),
    surfaceContainer = Color(0xFFEFEFF4),
    surfaceContainerHigh = Color(0xFFE9E9EF),
    surfaceContainerHighest = Color(0xFFE3E3EA),
    outline = Color(0xFFC9C9D2),
    outlineVariant = Color(0xFFE0E0E7),
    error = Color(0xFFD23B3B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

@Composable
fun GoClawTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
