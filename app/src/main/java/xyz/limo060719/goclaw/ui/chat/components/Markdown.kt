package xyz.limo060719.goclaw.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.limo060719.goclaw.ui.theme.CodeFontFamily

/**
 * 轻量 Markdown 渲染：段落 / 标题 / 列表 / 引用 / 分隔线 / 行内样式 / 代码块 / 表格。
 * 消息正文的唯一入口是 [MessageText]。
 */

/* ---------- 顶层切分：文本 / 代码块 / 表格 ---------- */

private sealed interface MsgSegment
private data class TextSegment(val text: String) : MsgSegment
private data class CodeSegment(val code: String, val lang: String) : MsgSegment
private data class TableSegment(val header: List<String>, val rows: List<List<String>>) : MsgSegment

private val codeFenceRegex = Regex("```([a-zA-Z0-9+#._-]*)\\r?\\n?([\\s\\S]*?)```")

/** A Markdown table separator row, e.g. `|---|:--:|---:|` — only pipes, dashes, colons, spaces. */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    return t.contains('-') && t.contains('|') && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}

private fun parseSegments(text: String): List<MsgSegment> {
    val out = mutableListOf<MsgSegment>()
    var last = 0
    for (m in codeFenceRegex.findAll(text)) {
        if (m.range.first > last) {
            text.substring(last, m.range.first).trim('\n', '\r')
                .takeIf { it.isNotBlank() }?.let { splitTables(it, out) }
        }
        out.add(CodeSegment(m.groupValues[2].trimEnd('\n', '\r'), m.groupValues[1].trim()))
        last = m.range.last + 1
    }
    if (last < text.length) {
        text.substring(last).trim('\n', '\r').takeIf { it.isNotBlank() }?.let { splitTables(it, out) }
    }
    if (out.isEmpty()) out.add(TextSegment(text))
    return out
}

/** Split a (code-free) block into plain-text and Markdown-table segments. */
private fun splitTables(block: String, out: MutableList<MsgSegment>) {
    val lines = block.split('\n')
    val buf = StringBuilder()
    fun flushText() {
        buf.toString().trim('\n', '\r').takeIf { it.isNotBlank() }?.let { out.add(TextSegment(it)) }
        buf.setLength(0)
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val next = lines.getOrNull(i + 1)
        // A header row followed by a `---` separator row starts a table.
        if (line.contains('|') && next != null && isTableSeparator(next)) {
            flushText()
            val header = splitCells(line)
            val rows = mutableListOf<List<String>>()
            var j = i + 2
            while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                rows.add(splitCells(lines[j])); j++
            }
            out.add(TableSegment(header, rows))
            i = j
        } else {
            buf.append(line).append('\n'); i++
        }
    }
    flushText()
}

private fun splitCells(row: String): List<String> {
    var s = row.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    return s.split('|').map { it.trim() }
}

/* ---------- 块级解析：标题 / 列表 / 引用 / 段落 ---------- */

private sealed interface MdBlock
private data class MdHeading(val level: Int, val text: String) : MdBlock
private data class MdBullet(val text: String, val marker: String) : MdBlock
private data class MdQuote(val text: String) : MdBlock
private data class MdParagraph(val text: String) : MdBlock
private object MdDivider : MdBlock

private val orderedItemRegex = Regex("^\\d+\\. ")
private val dividerRegex = Regex("^(-{3,}|\\*{3,}|_{3,})$")

private fun parseBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        para.toString().trim().takeIf { it.isNotBlank() }?.let { blocks.add(MdParagraph(it)) }
        para.setLength(0)
    }
    fun appendPara(s: String) { if (para.isNotEmpty()) para.append(' '); para.append(s.trim()) }
    for (raw in text.split('\n')) {
        val t = raw.trim()
        val hashes = t.takeWhile { it == '#' }.length
        when {
            t.isBlank() -> flush()
            dividerRegex.matches(t) -> { flush(); blocks.add(MdDivider) }
            hashes in 1..6 && t.getOrNull(hashes) == ' ' -> {
                flush(); blocks.add(MdHeading(hashes.coerceAtMost(4), t.drop(hashes).trim()))
            }
            t.startsWith("> ") -> { flush(); blocks.add(MdQuote(t.removePrefix("> ").trim())) }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") ->
                { flush(); blocks.add(MdBullet(t.drop(2).trim(), "•")) }
            orderedItemRegex.containsMatchIn(t) ->
                { flush(); blocks.add(MdBullet(t.substringAfter(' ').trim(), t.takeWhile { it != ' ' })) }
            else -> appendPara(raw)
        }
    }
    flush()
    return blocks
}

/** Append text with inline Markdown (`**bold**`, `*italic*`, `` `code` ``, `~~strike~~`, links). */
private fun AnnotatedString.Builder.appendInline(text: String, codeBg: Color, linkColor: Color) {
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
            text[i] == '[' -> {
                val close = text.indexOf(']', i + 1)
                if (close >= 0 && text.getOrNull(close + 1) == '(') {
                    val urlEnd = text.indexOf(')', close + 2)
                    if (urlEnd >= 0) {
                        val url = text.substring(close + 2, urlEnd).trim()
                        val linkStyle = TextLinkStyles(
                            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                        )
                        // LinkAnnotation makes the span clickable; Text opens it via the platform UriHandler.
                        withLink(LinkAnnotation.Url(url, linkStyle)) {
                            appendInline(text.substring(i + 1, close), codeBg, linkColor)
                        }
                        i = urlEnd + 1
                    } else { append(text[i]); i++ }
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

/* ---------- 渲染 ---------- */

/** 消息正文入口：按 文本 / 代码 / 表格 分段渲染。 */
@Composable
internal fun MessageText(text: String) {
    val segments = remember(text) { parseSegments(text) }
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
                is MdBullet -> Row(verticalAlignment = Alignment.Top) {
                    Text("${b.marker} ", style = MaterialTheme.typography.bodyLarge)
                    Text(inline(b.text), style = MaterialTheme.typography.bodyLarge)
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
                MdDivider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun TableBlock(header: List<String>, rows: List<List<String>>) {
    val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val border = MaterialTheme.colorScheme.outline
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val linkColor = MaterialTheme.colorScheme.onSurface

    @Composable
    fun RowOfCells(cells: List<String>, isHeader: Boolean) {
        Row(
            Modifier
                .height(IntrinsicSize.Min)
                .background(
                    if (isHeader) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
                ),
        ) {
            for (c in 0 until cols) {
                if (c > 0) VerticalDivider(color = border)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(
                        buildAnnotatedString { appendInline(cells.getOrNull(c).orEmpty(), codeBg, linkColor) },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }

    Column(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, border, MaterialTheme.shapes.small),
    ) {
        RowOfCells(header, isHeader = true)
        rows.forEach { row ->
            HorizontalDivider(color = border)
            RowOfCells(row, isHeader = false)
        }
    }
}

/** 代码块：灰阶底 + 发丝边框 + 一键复制。 */
@Composable
internal fun CodeBlock(code: String, lang: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }

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
                        contentDescription = "复制代码",
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                code,
                fontFamily = CodeFontFamily,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}
