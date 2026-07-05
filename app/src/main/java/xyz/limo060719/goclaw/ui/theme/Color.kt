package xyz.limo060719.goclaw.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 单色设计语言（Mono）。
 *
 * 整个应用只有一条灰阶 + 一个语义红，"强调"通过黑白反转表达：
 * 深色模式下主色是白，浅色模式下主色是黑。
 * 层级靠细腻的灰阶台阶与 1dp 发丝线（outlineVariant）表达，而不是彩色。
 */
object Mono {

    /* ---- 深色灰阶（自下而上逐级提亮） ---- */
    val Black = Color(0xFF0A0A0A)        // 页面背景
    val Ink1 = Color(0xFF111111)         // surfaceContainerLow
    val Ink2 = Color(0xFF161616)         // surfaceContainer
    val Ink3 = Color(0xFF1C1C1C)         // surfaceContainerHigh
    val Ink4 = Color(0xFF232323)         // surfaceContainerHighest
    val HairlineDark = Color(0xFF262626) // outline
    val HairlineDarkSoft = Color(0xFF1E1E1E) // outlineVariant（发丝线）
    val TextHighDark = Color(0xFFF2F2F2)
    val TextMidDark = Color(0xFF9A9A9A)

    /* ---- 浅色灰阶 ---- */
    val Paper = Color(0xFFFAFAFA)        // 页面背景
    val White = Color(0xFFFFFFFF)
    val Fog1 = Color(0xFFF4F4F4)
    val Fog2 = Color(0xFFEFEFEF)
    val Fog3 = Color(0xFFE9E9E9)
    val HairlineLight = Color(0xFFE2E2E2)
    val HairlineLightSoft = Color(0xFFECECEC)
    val TextHighLight = Color(0xFF111111)
    val TextMidLight = Color(0xFF6F6F6F)

    /* ---- 语义色（唯一保留的彩色） ---- */
    val RedDark = Color(0xFFE5484D)
    val RedLight = Color(0xFFD93036)
    val Green = Color(0xFF22C55E)        // 在线状态点
}
