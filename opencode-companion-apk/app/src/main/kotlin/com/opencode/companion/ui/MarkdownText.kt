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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Terminal / CLI Wizard Markdown Renderer for OpenCode & Antigravity.
 * Estandarizado a tipografía de consola limpia (sans-serif / monospace en #E6EDF3 / #8B949E).
 * Elimina negritas y cursivas estridentes o invasivas, manteniendo legibilidad austera y rápida.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    cursor: String = "",
    onLinkClick: ((String) -> Unit)? = null
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            val isLast = index == blocks.lastIndex
            val trailingCursor = if (isLast) cursor else ""

            when (block) {
                is MdBlock.Header -> Text(
                    text = buildInline(block.text, trailingCursor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 22.sp
                        )
                        2 -> MaterialTheme.typography.titleSmall.copy(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 20.sp
                        )
                        else -> MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 19.sp
                        )
                    },
                    color = Color(0xFF79C0FF)
                )
                is MdBlock.CodeBlock -> {
                    CodeBlockItem(block)
                    if (trailingCursor.isNotBlank()) {
                        Text(
                            text = trailingCursor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF58A6FF)
                        )
                    }
                }
                is MdBlock.Quote -> Surface(
                    color = Color(0xFF161B22).copy(alpha = 0.6f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color(0xFF30363D))
                ) {
                    Text(
                        buildInline(block.text, trailingCursor),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp
                        ),
                        color = Color(0xFF8B949E)
                    )
                }
                is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { itemIdx, item ->
                        val itemCursor = if (isLast && itemIdx == block.items.lastIndex) trailingCursor else ""
                        Row {
                            Text(
                                "• ",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 14.5.sp
                                ),
                                color = Color(0xFF8B949E)
                            )
                            Text(
                                buildInline(item, itemCursor),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 14.5.sp,
                                    lineHeight = 21.sp
                                ),
                                color = Color(0xFFE6EDF3)
                            )
                        }
                    }
                }
                is MdBlock.OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { itemIdx, item ->
                        val itemCursor = if (isLast && itemIdx == block.items.lastIndex) trailingCursor else ""
                        Row {
                            Text(
                                "${itemIdx + 1}. ",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 14.5.sp
                                ),
                                color = Color(0xFF8B949E)
                            )
                            Text(
                                buildInline(item, itemCursor),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 14.5.sp,
                                    lineHeight = 21.sp
                                ),
                                color = Color(0xFFE6EDF3)
                            )
                        }
                    }
                }
                is MdBlock.Paragraph -> Text(
                    buildInline(block.text, trailingCursor),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp
                    ),
                    color = Color(0xFFE6EDF3)
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
        color = Color(0xFF0D1117),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFF30363D)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.language.ifBlank { "código" }.uppercase(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF8B949E)
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
                        tint = if (copied) Color(0xFF3FB950) else Color(0xFF8B949E)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (copied) "¡Copiado!" else "Copiar",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                        color = if (copied) Color(0xFF3FB950) else Color(0xFF8B949E)
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
                fontSize = 12.sp,
                lineHeight = 17.5.sp
            )
        }
    }
}

private fun highlightCode(code: String, language: String): AnnotatedString {
    val lang = language.lowercase().trim()
    val builder = AnnotatedString.Builder()

    val keywordColor = Color(0xFF79C0FF)      // Terminal cyan / blue
    val stringColor = Color(0xFF7EE787)       // Subtle green
    val commentColor = Color(0xFF8B949E)      // Muted gray
    val numberColor = Color(0xFFD2A8FF)       // Light purple
    val typeColor = Color(0xFFFFA657)         // Light orange
    val defaultColor = Color(0xFFE6EDF3)      // Crisp console text

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
            builder.withStyle(SpanStyle(color = commentColor)) {
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
                    builder.withStyle(SpanStyle(color = commentColor)) {
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
                    builder.withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Medium)) {
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

/**
 * Parses inline markdown: strips raw delimiters (**bold**, *italic*, `code`, [text](url))
 * and renders subtle console styling without invasive or strident bold fonts.
 */
private fun buildInline(src: String, cursor: String = ""): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var s = src

    // 1. First process links: [text](url) -> extract text
    val linkRegex = Regex("""\[([^\]]+)]\((https?://[^\s)]+)\)""")
    val linkMatches = linkRegex.findAll(s).toList()
    val linkRanges = mutableListOf<Triple<Int, Int, String>>()

    var cleanText = s
    if (linkMatches.isNotEmpty()) {
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
        cleanText = sb.toString()
    }

    // 2. Tokenize inline markers: `code`, **bold**, *italic*
    // Using a regex to extract clean spans
    val tokenRegex = Regex("""(`[^`]+`|\*\*[^*]+\*\*|\*[^*]+\*|[^\s`*]+|\s+)""")
    val tokens = tokenRegex.findAll(cleanText)

    for (match in tokens) {
        val t = match.value
        when {
            t.startsWith("`") && t.endsWith("`") && t.length >= 2 -> {
                val inner = t.substring(1, t.length - 1)
                builder.withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0xFF1E2228),
                        color = Color(0xFF79C0FF),
                        fontSize = 12.5.sp
                    )
                ) {
                    append(inner)
                }
            }
            t.startsWith("**") && t.endsWith("**") && t.length >= 4 -> {
                val inner = t.substring(2, t.length - 2)
                // Subtle bold: clean contrast in #FFFFFF with SemiBold, no heavy distortion
                builder.withStyle(
                    SpanStyle(
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFFFFFF)
                    )
                ) {
                    append(inner)
                }
            }
            t.startsWith("*") && t.endsWith("*") && t.length >= 2 -> {
                val inner = t.substring(1, t.length - 1)
                builder.withStyle(
                    SpanStyle(
                        fontStyle = FontStyle.Italic,
                        color = Color(0xFFD0D7DE)
                    )
                ) {
                    append(inner)
                }
            }
            else -> {
                builder.append(t)
            }
        }
    }

    // 3. Append cursor at end if provided
    if (cursor.isNotEmpty()) {
        builder.withStyle(
            SpanStyle(
                color = Color(0xFF58A6FF),
                fontWeight = FontWeight.Bold
            )
        ) {
            append(cursor)
        }
    }

    // 4. Re-apply link annotations if applicable
    val result = builder.toAnnotatedString()
    if (linkRanges.isNotEmpty()) {
        val finalB = AnnotatedString.Builder(result)
        for ((st, en, url) in linkRanges) {
            if (en <= finalB.length) {
                finalB.addStyle(
                    SpanStyle(
                        textDecoration = TextDecoration.Underline,
                        color = Color(0xFF58A6FF)
                    ),
                    st,
                    en
                )
                finalB.addStringAnnotation("URL", url, st, en)
            }
        }
        return finalB.toAnnotatedString()
    }

    return result
}
