package xyz.limo060719.goclaw.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.ui.theme.CodeFontFamily

/**
 * 轻量 Markdown 渲染：段落 / 标题 / 列表（嵌套、任务）/ 引用 / 分隔线 / 图片 / 行内样式 /
 * 代码块（高亮）/ 表格（横向滚动）。消息正文的唯一入口是 [MessageText]；解析在 MarkdownParse.kt。
 */

/** 消息正文入口：按块渲染，每块独立 remember（流式时只重算最后一块）。 */
@Composable
internal fun MessageText(text: String) {
    val chunks = remember(text) { splitChunks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunks.forEachIndexed { i, chunk -> key(i) { MarkdownChunk(chunk) } }
    }
}

/** 一块正文。参数不变时被 Compose 跳过，所以已完成的块在流式输出期间不会重组。 */
@Composable
private fun MarkdownChunk(chunk: String) {
    val segments = remember(chunk) { parseChunk(chunk) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is TextSegment -> MarkdownText(seg.text)
                is CodeSegment -> CodeBlock(seg.code, seg.lang)
                is TableSegment -> TableBlock(seg.header, seg.rows)
            }
        }
    }
}

/* ---------- 行内样式 ---------- */

private val autoLinkRegex = Regex("""https?://[^\s<>()\[\]{}"'`，。！？、；：）】」]+""")

private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/**
 * Append text with inline Markdown (`**bold**`, `*italic*`, `` `code` ``, `~~strike~~`, links,
 * inline images as links, bare URLs auto-linked).
 */
private fun AnnotatedString.Builder.appendInline(text: String, codeBg: Color, linkColor: Color) {
    val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end >= 0) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendInline(text.substring(i + 2, end), codeBg, linkColor); pop()
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    appendInline(text.substring(i + 1, end), codeBg, linkColor); pop()
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end >= 0) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    appendInline(text.substring(i + 2, end), codeBg, linkColor); pop()
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end >= 0) {
                    pushStyle(SpanStyle(fontFamily = CodeFontFamily, background = codeBg))
                    append(text.substring(i + 1, end)); pop()
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '[' || (text[i] == '!' && text.getOrNull(i + 1) == '[') -> {
                val isImage = text[i] == '!'
                val open = if (isImage) i + 1 else i
                val close = text.indexOf(']', open + 1)
                val urlEnd = if (close >= 0 && text.getOrNull(close + 1) == '(') text.indexOf(')', close + 2) else -1
                if (urlEnd >= 0) {
                    val url = text.substring(close + 2, urlEnd).trim().substringBefore(' ')
                    val label = text.substring(open + 1, close)
                    // LinkAnnotation makes the span clickable; Text opens it via the platform UriHandler.
                    withLink(LinkAnnotation.Url(url, linkStyle)) {
                        if (isImage) append("🖼 ${label.ifBlank { url }}")
                        else appendInline(label, codeBg, linkColor)
                    }
                    i = urlEnd + 1
                } else { append(text[i]); i++ }
            }
            else -> {
                // Bare URL → link. Only at a word start (ASCII-wise: "看https://…" still links).
                val prev = text.getOrNull(i - 1)
                val url = if (text[i] == 'h' && (prev == null || !(prev.isAsciiLetterOrDigit()))) {
                    autoLinkRegex.matchAt(text, i)?.value?.trimEnd('.', ',', ';', ':', '!', '?')
                } else null
                if (url != null) {
                    withLink(LinkAnnotation.Url(url, linkStyle)) { append(url) }
                    i += url.length
                } else { append(text[i]); i++ }
            }
        }
    }
}

/* ---------- 块级渲染 ---------- */

private val bulletMarkers = listOf("•", "◦", "▪", "▫")

@Composable
private fun MarkdownText(text: String) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val linkColor = MaterialTheme.colorScheme.onSurface
    fun inline(s: String) = buildAnnotatedString { appendInline(s, codeBg, linkColor) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdHeading -> Text(
                    inline(b.text),
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        3 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    },
                    fontWeight = FontWeight.Bold,
                )
                is MdParagraph -> Text(inline(b.text), style = MaterialTheme.typography.bodyLarge)
                is MdListItem -> Row(
                    Modifier.padding(start = (b.depth * 16).dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    val marker = when {
                        b.checked == true -> "☑"
                        b.checked == false -> "☐"
                        b.marker.first().isDigit() -> b.marker
                        else -> bulletMarkers[b.depth.coerceIn(0, bulletMarkers.lastIndex)]
                    }
                    Text("$marker ", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        inline(b.text),
                        style = MaterialTheme.typography.bodyLarge,
                        // Done tasks read as done: struck through and dimmed.
                        textDecoration = if (b.checked == true) TextDecoration.LineThrough else null,
                        color = if (b.checked == true) MaterialTheme.colorScheme.onSurfaceVariant
                        else Color.Unspecified,
                    )
                }
                is MdQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier.width(2.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        inline(b.text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is MdImage -> MarkdownImage(b.alt, b.url)
                MdDivider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Block image. Only absolute http(s) URLs load (gateway-relative paths need auth) → else a link. */
@Composable
private fun MarkdownImage(alt: String, url: String) {
    if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
        AsyncImage(
            model = url,
            contentDescription = alt.ifBlank { null },
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(MaterialTheme.shapes.small),
        )
    } else {
        val linkColor = MaterialTheme.colorScheme.onSurface
        Text(
            buildAnnotatedString { appendInline("![$alt]($url)", Color.Transparent, linkColor) },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/* ---------- 表格：按内容定列宽，超宽横向滚动 ---------- */

/** Grid line positions captured during layout, read when drawing the borders. */
private class TableGrid {
    var colX = IntArray(0)
    var rowY = IntArray(0)
}

@Composable
private fun TableBlock(header: List<String>, rows: List<List<String>>) {
    val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val allRows = listOf(header) + rows
    val border = MaterialTheme.colorScheme.outline
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val linkColor = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val maxColPx = with(density) { 220.dp.roundToPx() }
    val minColPx = with(density) { 36.dp.roundToPx() }
    val lineW = with(density) { 1.dp.toPx() }
    val grid = remember { TableGrid() }

    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Layout(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .drawBehind {
                    if (grid.rowY.size > 1) {
                        drawRect(headerBg, size = Size(size.width, grid.rowY[1].toFloat()))
                    }
                    for (x in grid.colX.drop(1).dropLast(1)) {
                        drawLine(border, Offset(x.toFloat(), 0f), Offset(x.toFloat(), size.height), lineW)
                    }
                    for (y in grid.rowY.drop(1).dropLast(1)) {
                        drawLine(border, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), lineW)
                    }
                }
                .border(1.dp, border, MaterialTheme.shapes.small),
            content = {
                allRows.forEachIndexed { r, row ->
                    for (c in 0 until cols) {
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(
                                buildAnnotatedString { appendInline(row.getOrNull(c).orEmpty(), codeBg, linkColor) },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            // Column width = widest cell's natural width, clamped; long cells wrap within it.
            val colW = IntArray(cols) { minColPx }
            measurables.forEachIndexed { i, m ->
                val c = i % cols
                colW[c] = maxOf(colW[c], m.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(maxColPx))
            }
            val placeables = measurables.mapIndexed { i, m ->
                m.measure(Constraints.fixedWidth(colW[i % cols]))
            }
            val rowCount = allRows.size
            val rowH = IntArray(rowCount) { r -> (0 until cols).maxOf { c -> placeables[r * cols + c].height } }
            grid.colX = IntArray(cols + 1).also { for (c in 0 until cols) it[c + 1] = it[c] + colW[c] }
            grid.rowY = IntArray(rowCount + 1).also { for (r in 0 until rowCount) it[r + 1] = it[r] + rowH[r] }
            layout(grid.colX[cols], grid.rowY[rowCount]) {
                placeables.forEachIndexed { i, p -> p.place(grid.colX[i % cols], grid.rowY[i / cols]) }
            }
        }
    }
}

/* ---------- 代码块：灰阶高亮 + 发丝边框 + 一键复制 ---------- */

@Composable
internal fun CodeBlock(code: String, lang: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }

    // Mono design language: emphasis through weight / tone, not hue.
    val base = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val highlighted = remember(code, lang, base, muted) {
        val tokens = highlightCode(code, lang)
        buildAnnotatedString {
            append(code)
            tokens.forEach { t ->
                val style = when (t.kind) {
                    TokenKind.KEYWORD -> SpanStyle(fontWeight = FontWeight.Bold, color = base)
                    TokenKind.STRING -> SpanStyle(color = base.copy(alpha = 0.7f))
                    TokenKind.NUMBER -> SpanStyle(color = base.copy(alpha = 0.7f))
                    TokenKind.COMMENT -> SpanStyle(color = muted, fontStyle = FontStyle.Italic)
                }
                addStyle(style, t.start, t.end)
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    lang.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = CodeFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(code)); copied = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.md_copy_code),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(if (copied) R.string.common_copied else R.string.common_copy), style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                highlighted,
                fontFamily = CodeFontFamily,
                style = MaterialTheme.typography.bodySmall,
                color = base,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}
