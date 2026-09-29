package xyz.limo060719.goclaw.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 极简黑白主题。
 *
 * 语义约定（供所有屏幕复用，不要绕过它硬编码颜色）：
 * - primary            = 反色强调（暗色下是白、亮色下是黑），用于用户气泡、主按钮、选中态
 * - surfaceContainer*  = 灰阶台阶，层级越高越亮（暗色）/ 越暗（亮色）
 * - outline            = 可见边框；outlineVariant = 发丝分隔线
 */
private val DarkColors = darkColorScheme(
    primary = Mono.TextHighDark,
    onPrimary = Mono.Black,
    primaryContainer = Mono.TextHighDark,
    onPrimaryContainer = Mono.Black,
    secondary = Mono.TextMidDark,
    onSecondary = Mono.Black,
    secondaryContainer = Mono.Ink3,
    onSecondaryContainer = Mono.TextHighDark,
    tertiary = Mono.TextHighDark,
    tertiaryContainer = Mono.Ink2,
    onTertiaryContainer = Mono.TextHighDark,
    background = Mono.Black,
    onBackground = Mono.TextHighDark,
    surface = Mono.Black,
    onSurface = Mono.TextHighDark,
    surfaceVariant = Mono.Ink2,
    onSurfaceVariant = Mono.TextMidDark,
    surfaceTint = Mono.Black, // 关闭 tonal elevation 的染色，保持纯灰阶
    surfaceContainerLowest = Mono.Black,
    surfaceContainerLow = Mono.Ink1,
    surfaceContainer = Mono.Ink2,
    surfaceContainerHigh = Mono.Ink3,
    surfaceContainerHighest = Mono.Ink4,
    outline = Mono.HairlineDark,
    outlineVariant = Mono.HairlineDarkSoft,
    error = Mono.RedDark,
    onError = Mono.Black,
    errorContainer = androidx.compose.ui.graphics.Color(0xFF3A1E1E),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFD6D6),
    scrim = androidx.compose.ui.graphics.Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = Mono.TextHighLight,
    onPrimary = Mono.Paper,
    primaryContainer = Mono.TextHighLight,
    onPrimaryContainer = Mono.Paper,
    secondary = Mono.TextMidLight,
    onSecondary = Mono.White,
    secondaryContainer = Mono.Fog2,
    onSecondaryContainer = Mono.TextHighLight,
    tertiary = Mono.TextHighLight,
    tertiaryContainer = Mono.Fog1,
    onTertiaryContainer = Mono.TextHighLight,
    background = Mono.Paper,
    onBackground = Mono.TextHighLight,
    surface = Mono.Paper,
    onSurface = Mono.TextHighLight,
    surfaceVariant = Mono.Fog2,
    onSurfaceVariant = Mono.TextMidLight,
    surfaceTint = Mono.Paper,
    surfaceContainerLowest = Mono.White,
    surfaceContainerLow = Mono.Fog1,
    surfaceContainer = Mono.Fog2,
    surfaceContainerHigh = Mono.Fog3,
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFE3E3E3),
    outline = Mono.HairlineLight,
    outlineVariant = Mono.HairlineLightSoft,
    error = Mono.RedLight,
    onError = Mono.White,
    errorContainer = androidx.compose.ui.graphics.Color(0xFFFFDAD6),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFF410002),
    scrim = androidx.compose.ui.graphics.Color(0xFF000000),
)

/** 统一圆角刻度：6 / 10 / 14 / 20 / 28。 */
private val MonoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * [dynamicColor] (opt-in, Android 12+) swaps the Mono palette for the wallpaper-derived one;
 * shapes and typography stay Mono either way.
 */
@Composable
fun GoClawTheme(darkTheme: Boolean = true, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = MonoTypography,
        shapes = MonoShapes,
        content = content,
    )
}
