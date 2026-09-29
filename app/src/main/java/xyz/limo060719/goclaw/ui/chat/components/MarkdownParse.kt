package xyz.limo060719.goclaw.ui.chat.components

/**
 * Markdown 的纯解析层（不依赖 Compose，可单测）。渲染在 Markdown.kt。
 *
 * 增量渲染的关键是 [splitChunks]：正文按"代码块外的空行"切成若干块，每块独立解析、独立
 * remember。流式输出时只有最后一块在变，前面的块字符串不变 → 解析结果与重组都被跳过，
 * 不再每来一个 token 就把整条消息从头解析一遍。
 */

/* ---------- 分块 ---------- */

private fun isFenceLine(line: String) = line.trimStart().startsWith("```")

/**
 * Splits message text into independently-renderable chunks: runs of lines separated by blank
 * lines, except that a fenced code block (``` … ```) is always exactly one chunk, blank lines and
 * all. An unclosed fence (still streaming) runs to the end of the text.
 */
internal fun splitChunks(text: String): List<String> {
    val chunks = mutableListOf<String>()
    val buf = StringBuilder()
    var inFence = false
    fun flush() {
        if (buf.isNotBlank()) chunks.add(buf.toString().trimEnd('\n', '\r'))
        buf.setLength(0)
    }
    for (line in text.split('\n')) {
        when {
            inFence -> {
                buf.append(line).append('\n')
                if (isFenceLine(line)) { inFence = false; flush() }
            }
            isFenceLine(line) -> {
                flush()
                buf.append(line).append('\n')
                // A one-line fence like ```code``` opens and closes on the same line.
                val t = line.trim()
                if (t.length > 6 && t.endsWith("```")) flush() else inFence = true
            }
            line.isBlank() -> flush()
            else -> buf.append(line).append('\n')
        }
    }
    flush()
    return chunks
}

/* ---------- 块内分段：文本 / 代码 / 表格 ---------- */

internal sealed interface MsgSegment
internal data class TextSegment(val text: String) : MsgSegment
internal data class CodeSegment(val code: String, val lang: String) : MsgSegment
internal data class TableSegment(val header: List<String>, val rows: List<List<String>>) : MsgSegment

/** Parses one chunk from [splitChunks] into its segments. */
internal fun parseChunk(chunk: String): List<MsgSegment> {
    val trimmed = chunk.trimStart()
    if (trimmed.startsWith("```")) {
        val firstNl = trimmed.indexOf('\n')
        if (firstNl < 0) {
            // Single line: ```lang (still streaming) or ```inline code```.
            val inner = trimmed.removePrefix("```").removeSuffix("```")
            return if (trimmed.length > 6 && trimmed.endsWith("```")) listOf(CodeSegment(inner, ""))
            else listOf(CodeSegment("", inner.trim()))
        }
        val lang = trimmed.substring(3, firstNl).trim()
        var body = trimmed.substring(firstNl + 1)
        val closeAt = body.trimEnd().let { if (it.endsWith("```")) it.length - 3 else -1 }
        if (closeAt >= 0) body = body.substring(0, closeAt)
        return listOf(CodeSegment(body.trimEnd('\n', '\r'), lang))
    }
    val out = mutableListOf<MsgSegment>()
    splitTables(chunk, out)
    return out.ifEmpty { listOf(TextSegment(chunk)) }
}

/** A Markdown table separator row, e.g. `|---|:--:|---:|` — only pipes, dashes, colons, spaces. */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    return t.contains('-') && t.contains('|') && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
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

/* ---------- 块级解析：标题 / 列表（嵌套、任务）/ 引用 / 图片 / 段落 ---------- */

internal sealed interface MdBlock
internal data class MdHeading(val level: Int, val text: String) : MdBlock
/** A list item. [depth] 0 = top level; [checked] non-null for task items (`- [ ]` / `- [x]`). */
internal data class MdListItem(
    val text: String,
    val marker: String,
    val depth: Int,
    val checked: Boolean? = null,
) : MdBlock
internal data class MdQuote(val text: String) : MdBlock
internal data class MdParagraph(val text: String) : MdBlock
internal data class MdImage(val alt: String, val url: String) : MdBlock
internal data object MdDivider : MdBlock

private val orderedItemRegex = Regex("^(\\d+[.)]) (.*)$")
private val bulletItemRegex = Regex("^([-*+]) (.*)$")
private val dividerRegex = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val taskRegex = Regex("^\\[([ xX])] (.*)$")
private val imageLineRegex = Regex("^!\\[([^\\]]*)]\\((\\S+?)(?:\\s+\"[^\"]*\")?\\)$")

private fun indentOf(raw: String): Int {
    var n = 0
    for (c in raw) { if (c == ' ') n++ else if (c == '\t') n += 4 else break }
    return n
}

internal fun parseBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val para = StringBuilder()
    // Indent of each open list level; depth = index in this stack.
    val listIndents = ArrayDeque<Int>()
    fun flush() {
        para.toString().trim().takeIf { it.isNotBlank() }?.let { blocks.add(MdParagraph(it)) }
        para.setLength(0)
    }
    fun appendPara(s: String) { if (para.isNotEmpty()) para.append(' '); para.append(s.trim()) }
    fun listItem(raw: String, marker: String, body: String) {
        flush()
        val indent = indentOf(raw)
        while (listIndents.isNotEmpty() && listIndents.last() > indent) listIndents.removeLast()
        if (listIndents.isEmpty() || listIndents.last() < indent) listIndents.addLast(indent)
        val depth = (listIndents.size - 1).coerceIn(0, 3)
        val task = if (marker.length == 1) taskRegex.find(body) else null
        blocks.add(
            if (task != null) MdListItem(task.groupValues[2].trim(), marker, depth, task.groupValues[1] != " ")
            else MdListItem(body.trim(), marker, depth)
        )
    }
    for (raw in text.split('\n')) {
        val t = raw.trim()
        val hashes = t.takeWhile { it == '#' }.length
        val bullet = bulletItemRegex.find(t)
        val ordered = orderedItemRegex.find(t)
        val image = imageLineRegex.find(t)
        when {
            t.isBlank() -> { flush(); listIndents.clear() }
            dividerRegex.matches(t) -> { flush(); listIndents.clear(); blocks.add(MdDivider) }
            hashes in 1..6 && t.getOrNull(hashes) == ' ' -> {
                flush(); listIndents.clear()
                blocks.add(MdHeading(hashes.coerceAtMost(4), t.drop(hashes).trim()))
            }
            t.startsWith(">") -> {
                flush(); listIndents.clear()
                blocks.add(MdQuote(t.removePrefix(">").trim()))
            }
            image != null -> {
                flush(); listIndents.clear()
                blocks.add(MdImage(image.groupValues[1], image.groupValues[2]))
            }
            bullet != null -> listItem(raw, bullet.groupValues[1], bullet.groupValues[2])
            ordered != null -> listItem(raw, ordered.groupValues[1], ordered.groupValues[2])
            // An indented line right after a list item continues that item.
            para.isEmpty() && indentOf(raw) > 0 && blocks.lastOrNull() is MdListItem -> {
                val last = blocks.removeAt(blocks.lastIndex) as MdListItem
                blocks.add(last.copy(text = "${last.text} $t"))
            }
            else -> { listIndents.clear(); appendPara(raw) }
        }
    }
    flush()
    return blocks
}

/* ---------- 代码高亮（轻量扫描器） ---------- */

internal enum class TokenKind { KEYWORD, STRING, COMMENT, NUMBER }
internal data class CodeToken(val start: Int, val end: Int, val kind: TokenKind)

/** Above this size highlighting is skipped — a plain block is better than a janky one. */
internal const val HIGHLIGHT_MAX_CHARS = 30_000

private val hashCommentLangs = setOf(
    "py", "python", "sh", "bash", "shell", "zsh", "rb", "ruby", "yaml", "yml", "toml", "r",
    "perl", "pl", "dockerfile", "makefile", "make", "conf", "ini", "ps1", "powershell",
)
private val dashCommentLangs = setOf("sql", "lua", "haskell", "hs")

private val keywords = setOf(
    // C-family / JVM / JS / TS / Go / Rust / Swift
    "abstract", "as", "async", "await", "break", "case", "catch", "class", "const", "continue",
    "data", "default", "defer", "delete", "do", "else", "enum", "export", "extends", "false",
    "final", "finally", "fn", "for", "fun", "func", "function", "go", "if", "impl", "implements",
    "import", "in", "instanceof", "interface", "internal", "is", "let", "match", "mod", "mut",
    "namespace", "new", "null", "nil", "object", "open", "override", "package", "private",
    "protected", "pub", "public", "return", "sealed", "self", "static", "struct", "super",
    "suspend", "switch", "this", "throw", "throws", "trait", "true", "try", "type", "typeof",
    "use", "val", "var", "void", "when", "where", "while", "yield", "int", "long", "float",
    "double", "boolean", "bool", "char", "string", "undefined", "readonly", "lateinit", "companion",
    // Python / Ruby / shell
    "and", "def", "del", "elif", "except", "from", "global", "lambda", "None", "nonlocal", "not",
    "or", "pass", "raise", "True", "False", "with", "end", "then", "fi", "esac", "done", "echo",
    "local", "export", "unless", "until", "module", "require",
    // SQL (matched case-insensitively below)
    "select", "insert", "update", "into", "values", "set", "table", "create", "drop", "alter",
    "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having", "limit",
    "union", "distinct", "primary", "key", "references", "index",
)

/**
 * Tokenises [code] for highlighting: strings, comments, numbers and keywords. A single linear
 * pass; deliberately approximate (no per-language grammar) but never throws on odd input.
 */
internal fun highlightCode(code: String, lang: String): List<CodeToken> {
    if (code.length > HIGHLIGHT_MAX_CHARS) return emptyList()
    val l = lang.lowercase()
    val hashComments = l in hashCommentLangs
    val dashComments = l in dashCommentLangs
    val slashComments = !hashComments || l == "php"
    val caseInsensitive = l == "sql"
    val out = mutableListOf<CodeToken>()
    var i = 0
    val n = code.length
    fun lineEnd(from: Int) = code.indexOf('\n', from).let { if (it < 0) n else it }
    while (i < n) {
        val c = code[i]
        when {
            slashComments && c == '/' && code.startsWith("//", i) -> {
                val e = lineEnd(i); out.add(CodeToken(i, e, TokenKind.COMMENT)); i = e
            }
            slashComments && c == '/' && code.startsWith("/*", i) -> {
                val close = code.indexOf("*/", i + 2)
                val e = if (close < 0) n else close + 2
                out.add(CodeToken(i, e, TokenKind.COMMENT)); i = e
            }
            hashComments && c == '#' -> {
                val e = lineEnd(i); out.add(CodeToken(i, e, TokenKind.COMMENT)); i = e
            }
            dashComments && c == '-' && code.startsWith("--", i) -> {
                val e = lineEnd(i); out.add(CodeToken(i, e, TokenKind.COMMENT)); i = e
            }
            c == '"' || c == '\'' || c == '`' -> {
                val triple = code.startsWith("$c$c$c", i)
                val quote = if (triple) "$c$c$c" else c.toString()
                var j = i + quote.length
                while (j < n) {
                    if (code[j] == '\\') { j += 2; continue }
                    if (code.startsWith(quote, j)) { j += quote.length; break }
                    // Plain ' and " strings don't span lines — stops a stray apostrophe running on.
                    if (!triple && c != '`' && code[j] == '\n') break
                    j++
                }
                val e = j.coerceAtMost(n)
                out.add(CodeToken(i, e, TokenKind.STRING)); i = e
            }
            c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                var j = i + 1
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                out.add(CodeToken(i, j, TokenKind.NUMBER)); i = j
            }
            c.isLetter() || c == '_' -> {
                var j = i + 1
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                if (word in keywords || (caseInsensitive && word.lowercase() in keywords)) {
                    out.add(CodeToken(i, j, TokenKind.KEYWORD))
                }
                i = j
            }
            else -> i++
        }
    }
    return out
}
