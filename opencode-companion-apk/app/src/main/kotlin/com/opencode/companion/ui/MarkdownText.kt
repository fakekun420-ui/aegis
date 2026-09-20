package com.opencode.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Lightweight markdown renderer — no external library.
 * Handles: #/##/### headers, ``` code blocks (with language header & copy button), **bold**, *italic*, `inline code`, - lists, [links](url), > blockquotes.
 * Uses Claude-inspired Serif reading typography and clean styled surfaces.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, onLinkClick: ((String) -> Unit)? = null) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Header -> Text(
                    block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                    },
                    color = MaterialTheme.colorScheme.onSurface
                )
                is MdBlock.CodeBlock -> CodeBlockItem(block)
                is MdBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        block.text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Serif, fontSize = 14.sp, lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEach { item ->
                        Row {
                            Text("• ", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif, fontSize = 15.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(buildInline(item), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 22.sp), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                is MdBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEachIndexed { idx, item ->
                        Row {
                            Text("${idx + 1}. ", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif, fontSize = 15.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(buildInline(item), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 22.sp), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                is MdBlock.Paragraph -> Text(
                    buildInline(block.text),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun CodeBlockItem(block: MdBlock.CodeBlock) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.language.ifBlank { "código" }.uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(block.code))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp).semantics { contentDescription = if (copied) "Copiado" else "Copiar código" }
                ) {
                    Icon(
                        if (copied) Icons.Filled.Done else Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (copied) "¡Copiado!" else "Copiar",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val highlighted = remember(block.code, block.language) {
                highlightCode(block.code, block.language)
            }
            Text(
                text = highlighted,
                modifier = Modifier
                    .padding(12.dp)
                    .horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
        }
    }
}

private fun highlightCode(code: String, language: String): AnnotatedString {
    val lang = language.lowercase().trim()
    val builder = AnnotatedString.Builder()

    val keywordColor = Color(0xFFBA68C8)      // Purple accent for keywords
    val stringColor = Color(0xFF81C784)       // Soft green for strings
    val commentColor = Color(0xFF9E9E9E)      // Muted gray for comments
    val numberColor = Color(0xFFFFB74D)       // Soft orange for numbers
    val typeColor = Color(0xFF4DD0E1)         // Cyan for types / builtins
    val defaultColor = Color(0xFFE0E0E0)      // Soft crisp light for plain text

    val kotlinKeywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "import", "package", "return",
        "if", "else", "when", "for", "while", "do", "try", "catch", "finally", "throw",
        "null", "true", "false", "this", "super", "override", "private", "public",
        "protected", "internal", "suspend", "data", "sealed", "enum", "companion", "in", "is", "as"
    )

    val pythonKeywords = setOf(
        "def", "class", "import", "from", "return", "if", "elif", "else", "for", "while",
        "try", "except", "finally", "raise", "None", "True", "False", "self", "with", "as",
        "lambda", "async", "await", "yield", "pass", "in", "is", "not", "and", "or"
    )

    val bashKeywords = setOf(
        "echo", "if", "then", "fi", "elif", "else", "for", "in", "do", "done", "while",
        "until", "case", "esac", "function", "return", "exit", "sudo", "export", "cd",
        "ls", "cat", "grep", "curl", "pm", "am", "su", "chmod", "chown", "source", "mkdir", "rm"
    )

    val jsKeywords = setOf(
        "function", "const", "let", "var", "return", "if", "else", "for", "while",
        "switch", "case", "default", "import", "export", "from", "class", "extends",
        "new", "this", "true", "false", "null", "undefined", "async", "await", "try", "catch"
    )

    val keywords = when {
        lang.contains("kotlin") || lang.contains("kt") -> kotlinKeywords
        lang.contains("python") || lang.contains("py") -> pythonKeywords
        lang.contains("bash") || lang.contains("sh") || lang.contains("shell") || lang.contains("zsh") -> bashKeywords
        lang.contains("js") || lang.contains("javascript") || lang.contains("ts") || lang.contains("typescript") -> jsKeywords
        else -> kotlinKeywords + pythonKeywords + bashKeywords + jsKeywords
    }

    val lines = code.split("\n")
    lines.forEachIndexed { lineIdx, line ->
        if (lineIdx > 0) builder.append("\n")

        val trimmed = line.trimStart()
        val isBashOrPy = lang.contains("py") || lang.contains("sh") || lang.contains("bash")
        val commentPrefix = if (isBashOrPy) "#" else "//"

        if (trimmed.startsWith(commentPrefix)) {
            val indent = line.takeWhile { it.isWhitespace() }
            builder.append(indent)
            builder.withStyle(SpanStyle(color = commentColor, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                append(trimmed)
            }
            return@forEachIndexed
        }

        val tokenRegex = Regex("""(//.*|#.*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b|[^\sA-Za-z0-9_]+|\s+)""")
        val tokens = tokenRegex.findAll(line)

        for (match in tokens) {
            val token = match.value
            when {
                token.startsWith("//") || token.startsWith("#") -> {
                    builder.withStyle(SpanStyle(color = commentColor, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(token)
                    }
                }
                (token.startsWith("\"") && token.endsWith("\"")) || (token.startsWith("'") && token.endsWith("'")) -> {
                    builder.withStyle(SpanStyle(color = stringColor)) {
                        append(token)
                    }
                }
                token.matches(Regex("""\b\d+(\.\d+)?\b""")) -> {
                    builder.withStyle(SpanStyle(color = numberColor)) {
                        append(token)
                    }
                }
                keywords.contains(token) -> {
                    builder.withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)) {
                        append(token)
                    }
                }
                token.firstOrNull()?.isUpperCase() == true && token.matches(Regex("""[A-Za-z0-9_]+""")) -> {
                    builder.withStyle(SpanStyle(color = typeColor)) {
                        append(token)
                    }
                }
                else -> {
                    builder.withStyle(SpanStyle(color = defaultColor)) {
                        append(token)
                    }
                }
            }
        }
    }

    return builder.toAnnotatedString()
}

private sealed class MdBlock {
    data class Header(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val code: String, val language: String = "") : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class BulletList(val items: List<String>) : MdBlock()
    data class OrderedList(val items: List<String>) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
}

private fun parseMarkdown(src: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    var inCode = false
    var codeBuf = StringBuilder()
    var currentLang = ""
    val bulletBuf = mutableListOf<String>()
    val orderedBuf = mutableListOf<String>()

    fun flushLists() {
        if (bulletBuf.isNotEmpty()) { blocks += MdBlock.BulletList(bulletBuf.toList()); bulletBuf.clear() }
        if (orderedBuf.isNotEmpty()) { blocks += MdBlock.OrderedList(orderedBuf.toList()); orderedBuf.clear() }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            if (inCode) {
                blocks += MdBlock.CodeBlock(codeBuf.toString().trimEnd(), currentLang)
                codeBuf = StringBuilder()
                currentLang = ""
                inCode = false
            } else {
                flushLists()
                currentLang = trimmed.removePrefix("```").trim()
                inCode = true
            }
            i++
            continue
        }
        if (inCode) { codeBuf.appendLine(line); i++; continue }
        if (trimmed.isEmpty()) { flushLists(); i++; continue }
        when {
            trimmed.startsWith("# ") -> { flushLists(); blocks += MdBlock.Header(1, trimmed.removePrefix("# ").trim()); i++ }
            trimmed.startsWith("## ") -> { flushLists(); blocks += MdBlock.Header(2, trimmed.removePrefix("## ").trim()); i++ }
            trimmed.startsWith("### ") -> { flushLists(); blocks += MdBlock.Header(3, trimmed.removePrefix("### ").trim()); i++ }
            trimmed.startsWith("> ") -> { flushLists(); blocks += MdBlock.Quote(trimmed.removePrefix("> ").trim()); i++ }
            Regex("^[-*]\\s+").containsMatchIn(trimmed) -> { if (orderedBuf.isNotEmpty()) flushLists(); bulletBuf += trimmed.replace(Regex("^[-*]\\s+"), ""); i++ }
            Regex("^\\d+\\.\\s+").containsMatchIn(trimmed) -> { if (bulletBuf.isNotEmpty()) flushLists(); orderedBuf += trimmed.replace(Regex("^\\d+\\.\\s+"), ""); i++ }
            else -> { flushLists(); blocks += MdBlock.Paragraph(trimmed); i++ }
        }
    }
    flushLists()
    if (inCode && codeBuf.isNotEmpty()) blocks += MdBlock.CodeBlock(codeBuf.toString().trimEnd(), currentLang)
    return blocks
}

private fun buildInline(src: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var s = src
    // Very simple inline parsing: **bold**, *italic*, `code`, [text](url) -> text + link
    // We do sequential replacement with spans — keep it simple for Phase 2
    // Use a tiny state machine: scan for markers
    val linkRegex = Regex("""\[([^\]]+)]\((https?://[^\s)]+)\)""")
    val codeRegex = Regex("`([^`]+)`")
    val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
    val italicRegex = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")

    // For simplicity, build as plain with styles appended — handle links/code/bold/italic via AnnotatedString
    // We'll strip markers and push styles
    // Step: replace code spans first (protect interior)
    val codeSpans = mutableListOf<Pair<IntRange, String>>()
    // We build final by iterating char by char — simplest: just handle bold/italic/code via manual scan

    // Fallback simple: if no markers, return plain
    if (!src.contains("**") && !src.contains("`") && !src.contains("[") && !Regex("\\*[^*]+\\*").containsMatchIn(src)) {
        builder.append(src)
        return builder.toAnnotatedString()
    }

    // Tokenize: we handle **bold** -> bold, `code` -> monospace+bg, *italic* -> italic, [t](u) -> underline+link
    // Approach: replace links first
    var pos = 0
    val linkMatches = linkRegex.findAll(s).toList()
    if (linkMatches.isNotEmpty()) {
        // Build with links as underlined segments
        val linkRanges = mutableListOf<Triple<Int, Int, String>>()
        // We'll annotate after building full string without link syntax: [text](url) -> text
        val sb = StringBuilder()
        var lastEnd = 0
        for (m in linkMatches) {
            sb.append(s.substring(lastEnd, m.range.first))
            val linkText = m.groupValues[1]
            val url = m.groupValues[2]
            val start = sb.length
            sb.append(linkText)
            val end = sb.length
            linkRanges += Triple(start, end, url)
            lastEnd = m.range.last + 1
        }
        sb.append(s.substring(lastEnd))
        s = sb.toString()
        // Now s has links expanded to plain text; we can build inline for remaining markers and then annotate links
        val base = buildInlineSimple(s)
        // Re-apply link annotations
        val out = AnnotatedString.Builder()
        out.append(base)
        for ((st, en, url) in linkRanges) {
            out.addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = Color(0xFF7C5CFF)), st, en)
            out.addStringAnnotation("URL", url, st, en)
        }
        return out.toAnnotatedString()
    }

    return buildInlineSimple(s)
}

private fun buildInlineSimple(src: String): AnnotatedString {
    val b = AnnotatedString.Builder()
    b.append(src)
    // Bold
    for (m in Regex("\\*\\*(.+?)\\*\\*").findAll(src)) {
        val inner = m.groupValues[1]
        val start = m.range.first
        val end = m.range.last + 1
        b.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
    }
    // Inline code
    for (m in Regex("`([^`]+)`").findAll(src)) {
        val start = m.range.first
        val end = m.range.last + 1
        b.addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF1A1A2E), fontSize = 12.sp), start, end)
    }
    return b.toAnnotatedString()
}
