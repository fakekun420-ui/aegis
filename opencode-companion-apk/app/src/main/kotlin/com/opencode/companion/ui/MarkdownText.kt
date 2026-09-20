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
            Text(
                text = block.code,
                modifier = Modifier
                    .padding(12.dp)
                    .horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
