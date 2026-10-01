package com.aegis.hub.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.fillMaxWidth

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
    onLinkClick: ((String) -> Unit)? = null,
    // Cuantos BLOQUES se pintan, por defecto todos. El corte es por bloques y no por
    // lineas porque parseMarkdown ya ha partido el texto: un maxLines de Compose no sabe
    // donde acaba un bloque sin volver a parsear, y cortar por dentro de uno parte una
    // lista o una tabla justo por la mitad, que es peor que mostrar un bloque de mas.
    maxBlocks: Int = Int.MAX_VALUE
) {
    // Sin esto el texto del asistente NO se puede seleccionar: una pulsación larga no
    // abre el menú de copiar y no hay forma de llevarme una respuesta fuera de la app.
    //
    // Se envuelve AQUÍ y no en los cuatro puntos de llamada de ChatScreen por dos
    // razones concretas: el texto en vivo del streaming pasa por este mismo composable
    // (y un quinto wrappers en el call site se olvidaría el primero que se añadiese), y
    // envolver el árbol entero desde ChatScreen haría seleccionables también las
    // tarjetas de herramienta, donde una selección no significa nada.
    //
    // Sin colores custom: se usa elSelectionContainer tal cual. Se intento darle el
    // del tema con LocalTextSelectionColors, pero ese simbolo no existe en
    // androidx.compose.ui de la version que fija compose-bom 2024.10.00, y el unico
    // sitio donde se compila de verdad es el CI (este host no tiene ni java ni gradle).
    // El bug que se arregla es "no se puede seleccionar ni copiar"; el tono del
    // resaltado es cosmetico, asi que se deja el de por defecto antes que arriesgar otra
    // vuelta de compilacion por un color.
    SelectionContainer {
        MarkdownTextContent(text, modifier, cursor, onLinkClick, maxBlocks)
    }
}

/** El render de verdad. Separado para que MarkdownText sea solo la envoltura. */
@Composable
private fun MarkdownTextContent(
    text: String,
    modifier: Modifier = Modifier,
    cursor: String = "",
    onLinkClick: ((String) -> Unit)? = null,
    maxBlocks: Int = Int.MAX_VALUE
) {
    val context = LocalContext.current
    val handleLink: (String) -> Unit = onLinkClick ?: { url ->
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No browser found to open link", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "No browser found to open link", Toast.LENGTH_SHORT).show()
        }
    }

    val blocks = remember(text) { parseMarkdown(text) }
    val truncado = blocks.size > maxBlocks
    val visibles = if (truncado) blocks.take(maxBlocks.coerceAtLeast(0)) else blocks
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visibles.forEachIndexed { index, block ->
            val isLast = index == visibles.lastIndex
            // El cursor de escritura va al ultimo bloque REAL del texto. Con la vista
            // truncada, el ultimo bloque VISIBLE no es el final del documento: pegarle
            // ahi pondria el cursor a mitad de una frase, que se lee como texto roto.
            val trailingCursor = if (isLast && !truncado) cursor else ""

            when (block) {
                is MdBlock.Header -> MarkdownInlineText(
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
                    color = Color(0xFF79C0FF),
                    onLinkClick = handleLink
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
                is MdBlock.ToolExecution -> {
                    ToolExecutionCard(
                        tool = block.tool,
                        command = block.command,
                        output = block.output,
                        status = block.status,
                        exitCode = block.exitCode,
                        duration = block.duration
                    )
                    if (trailingCursor.isNotBlank()) {
                        Text(
                            text = trailingCursor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF58A6FF)
                        )
                    }
                }
                is MdBlock.Table -> MarkdownTable(
                    headers = block.headers,
                    rows = block.rows,
                    align = block.align
                )
                is MdBlock.Quote -> Surface(
                    color = Color(0xFF161B22).copy(alpha = 0.6f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, Color(0xFF30363D))
                ) {
                    MarkdownInlineText(
                        text = buildInline(block.text, trailingCursor),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp
                        ),
                        color = Color(0xFF8B949E),
                        onLinkClick = handleLink
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
                            MarkdownInlineText(
                                text = buildInline(item, itemCursor),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 14.5.sp,
                                    lineHeight = 21.sp
                                ),
                                color = Color(0xFFE6EDF3),
                                onLinkClick = handleLink
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
                            MarkdownInlineText(
                                text = buildInline(item, itemCursor),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 14.5.sp,
                                    lineHeight = 21.sp
                                ),
                                color = Color(0xFFE6EDF3),
                                onLinkClick = handleLink
                            )
                        }
                    }
                }
                is MdBlock.Paragraph -> MarkdownInlineText(
                    text = buildInline(block.text, trailingCursor),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp
                    ),
                    color = Color(0xFFE6EDF3),
                    onLinkClick = handleLink
                )
            }
        }
    }
}

@Composable
private fun MarkdownInlineText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    onLinkClick: (String) -> Unit
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val hasLinks = remember(text) { text.getStringAnnotations("URL", 0, text.length).isNotEmpty() }

    val tapModifier = if (hasLinks) {
        modifier.pointerInput(text) {
            detectTapGestures { offset ->
                layoutResult?.let { layout ->
                    val position = layout.getOffsetForPosition(offset)
                    val annotation = text.getStringAnnotations("URL", position, position).firstOrNull()
                    if (annotation != null) {
                        onLinkClick(annotation.item)
                    }
                }
            }
        }
    } else {
        modifier
    }

    Text(
        text = text,
        modifier = tapModifier,
        style = style,
        color = color,
        onTextLayout = { layoutResult = it }
    )
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

internal sealed class MdBlock {
    data class Header(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val code: String, val language: String = "") : MdBlock()
    data class ToolExecution(
        val tool: String,
        val command: String,
        val output: String? = null,
        val status: String = "completed",
        val exitCode: Int? = 0,
        val duration: Double? = null
    ) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class BulletList(val items: List<String>) : MdBlock()
    data class OrderedList(val items: List<String>) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()

    /**
     * Una tabla de markdown. MEDIDO 2026-10-01: no existia ningun tipo de tabla, asi que
     * caia en [Paragraph] y se pintaba con los `|` dentro. En el CLI la misma tabla sale
     * alineada, y de ahi la complaint de que "todo se ve desordenado".
     *
     * `align` va en el mismo orden que `headers`, y lo dice la fila de separacion: `:---`
     * izquierda, `:---:` centro, `---:` derecha.
     */
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val align: List<TableAlign> = emptyList()
    ) : MdBlock()
}

/** Alineacion de una columna. Lo decide la fila de `|---|` que va bajo la cabecera. */
enum class TableAlign { Left, Center, Right }

/**
 * Cuantos bloques tiene un texto en markdown. Vive FUERA del composable a proposito: el
 * que decide si hace falta un "ver todo" lo pregunta ANTES de componer, no despues.
 *
 * Por que hace falta: con un `maxLines` de Compose, saber si el texto se corta de verdad
 * obliga a mirar `onTextLayout`, o sea un dato que llega en un segundo fotograma. Y como
 * `remember` se resetea con esa lectura, la tarjeta se cerraba sola en el instante en que
 * el subagente terminaba. Aqui la pregunta se responde con el mismo parser que pinta, sin
 * fotogramas y sin estado.
 */
/**
 * Pinta una tabla. MEDIDO 2026-10-01: no existia, y por eso las tablas salian como texto con
 * pipes dentro. En movil la decision de diseno importante es el scroll HORIZONTAL: una tabla
 * de la salida de una herramienta se sale de verdad del ancho de la pantalla, y sin scroll lo
 * que no cabe no existe para el usuario — igual que la hoja de agentes que se recortaba sola.
 */
@Composable
private fun MarkdownTable(
    headers: List<String>,
    rows: List<List<String>>,
    align: List<TableAlign>
) {
    val borde = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    fun alineacionDe(idx: Int): TextAlign = when (align.getOrNull(idx) ?: TableAlign.Left) {
        TableAlign.Left -> TextAlign.Start
        TableAlign.Center -> TextAlign.Center
        TableAlign.Right -> TextAlign.End
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            headers.forEachIndexed { idx, h ->
                Text(
                    text = h,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = alineacionDe(idx),
                    maxLines = 2,
                    modifier = Modifier.widthIn(min = 84.dp).padding(horizontal = 6.dp)
                )
            }
        }
        HorizontalDivider(color = borde)
        rows.forEachIndexed { rIdx, fila ->
            Row(
                modifier = Modifier
                    .background(
                        if (rIdx % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                // Una fila con menos celdas que la cabecera se rellena: el dato que falta no
                // puede empujar las demas columnas de sitio.
                repeat(headers.size) { idx ->
                    Text(
                        text = fila.getOrNull(idx).orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        textAlign = alineacionDe(idx),
                        maxLines = 4,
                        modifier = Modifier.widthIn(min = 84.dp).padding(horizontal = 6.dp)
                    )
                }
            }
            if (rIdx < rows.lastIndex) HorizontalDivider(color = borde.copy(alpha = 0.3f))
        }
    }
}

/** Partir una fila de tabla en sus celdas, quitando las pipes de los extremos. */
internal fun filaDeTabla(linea: String): List<String>? {
    if (!linea.contains('|')) return null
    val cruda = linea.trim().removePrefix("|").removeSuffix("|")
    val celdas = cruda.split('|').map { it.trim() }
    // Una fila de tabla tiene al menos dos celdas. Sin este filtro, cualquier linea con una
    // sola pipe (por ejemplo un comando de shell) contaria como tabla.
    if (celdas.size < 2) return null
    return celdas
}

/**
 * La fila de separacion `|---|---|`. MEDIDO: tiene que estar compuesta SOLO de `-`, `:` y `|`, y
 * llevar al menos un `-`. Es lo que distingue una cabecera real de un texto con pipes.
 */
internal fun filaDeSeparacion(linea: String): String? {
    if (!linea.contains('-') || !linea.contains('|')) return null
    if (!linea.all { it == '-' || it == ':' || it == '|' || it == ' ' }) return null
    return linea
}

/**
 * Deja la fila con la MISMA anchura que la cabecera: rellena con vacio lo que falte y recorta
 * lo que sobre. MEDIDO 2026-10-01 por un test propio: sin esto, una fila de 2 celdas bajo
 * una cabecera de 3 llegaba al renderizador descuadrada, y una de 4 se recortaba ahi con un
 * `repeat(headers.size)` que perdia la celda de más sin avisar.
 *
 * Que el dato sea consistente no es cosmetico: el renderizador puede asi Pintar celda a celda
 * sin preguntar por el indice, y cualquier consumidor futuro del parser hereda la misma
 * garantia.
 */
internal fun normalizarFila(celdas: List<String>, columnas: Int): List<String> =
    (0 until columnas).map { celdas.getOrNull(it).orEmpty() }

/** La alineacion de cada columna, leida de la fila de separacion. */
internal fun alineacionesDe(separacion: String?): List<TableAlign> {
    if (separacion == null) return emptyList()
    return filaDeTabla(separacion)?.map { celda ->
        val izq = celda.startsWith(':')
        val der = celda.endsWith(':')
        when {
            izq && der -> TableAlign.Center
            der -> TableAlign.Right
            else -> TableAlign.Left
        }
    } ?: emptyList()
}

fun markdownBlockCount(text: String): Int = parseMarkdown(text).size

internal fun parseMarkdown(src: String): List<MdBlock> {
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

        val toolMatch = Regex("^[❯●>]\\s*(bash|run_command|view_file|write_to_file|replace_file_content|sed_file|grep_search|list_dir|command_status|manage_task|edit|read|glob|grep|lsp|websearch|webfetch)\\((.*?)\\)", RegexOption.IGNORE_CASE).find(trimmed)

        when {
            toolMatch != null -> {
                flushLists()
                val rawTool = toolMatch.groupValues[1]
                val normTool = if (rawTool.equals("run_command", ignoreCase = true)) "bash" else rawTool.lowercase()
                val cmd = toolMatch.groupValues[2]
                blocks += MdBlock.ToolExecution(
                    tool = normTool,
                    command = cmd,
                    status = "completed",
                    exitCode = 0
                )
                i++
            }
            trimmed.startsWith("# ") -> { flushLists(); blocks += MdBlock.Header(1, trimmed.removePrefix("# ").trim()); i++ }
            trimmed.startsWith("## ") -> { flushLists(); blocks += MdBlock.Header(2, trimmed.removePrefix("## ").trim()); i++ }
            trimmed.startsWith("### ") -> { flushLists(); blocks += MdBlock.Header(3, trimmed.removePrefix("### ").trim()); i++ }
            trimmed.startsWith("> ") -> { flushLists(); blocks += MdBlock.Quote(trimmed.removePrefix("> ").trim()); i++ }
            Regex("^[-*]\\s+").containsMatchIn(trimmed) -> { if (orderedBuf.isNotEmpty()) flushLists(); bulletBuf += trimmed.replace(Regex("^[-*]\\s+"), ""); i++ }
            Regex("^\\d+\\.\\s+").containsMatchIn(trimmed) -> { if (bulletBuf.isNotEmpty()) flushLists(); orderedBuf += trimmed.replace(Regex("^\\d+\\.\\s+"), ""); i++ }
            // MEDIDO 2026-10-01: una tabla se reconoce por la fila de separacion que va
            // justo debajo de la cabecera. Sin este brazo caia en `Paragraph` y se veian los
            // `|` en crudo. Va antes del `else` porque ningun otro brazo la captura: sus
            // lineas empiezan por `|`, no por `#`, `>`, `-` ni un digito.
            filaDeTabla(trimmed) != null && i + 1 < lines.size &&
                    filaDeSeparacion(lines[i + 1].trim()) != null -> {
                flushLists()
                val cabeceras = filaDeTabla(trimmed)!!
                val alineaciones = alineacionesDe(filaDeSeparacion(lines[i + 1].trim()))
                i += 2
                val filas = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val t = lines[i].trim()
                    val celdas = filaDeTabla(t)
                    // Una tabla termina en la primera linea que no sea una fila suya. Blank
                    // linea vacia y una linea de texto suelta la cortan igual, que es lo correcto.
                    if (celdas == null) break
                    filas.add(normalizarFila(celdas, cabeceras.size))
                    i++
                }
                blocks += MdBlock.Table(cabeceras, filas, alineaciones)
            }
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

/**
 * CLI Wizard Continuous Tool Execution Card for Aegis & Antigravity.
 * Renderiza pasos de ejecución en tiempo real: comando ❯ bash(...), progreso,
 * código de salida (exit code) y bloque de consola terminal `#0D1117`.
 */
@Composable
fun ToolExecutionCard(
    tool: String,
    command: String,
    output: String? = null,
    status: String = "completed",
    exitCode: Int? = 0,
    duration: Double? = null,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val isRunning = status == "running"
    val isError = status == "error" || (exitCode != null && exitCode != 0)
    val statusColor = when {
        isRunning -> Color(0xFFD29922)
        isError -> Color(0xFFF85149)
        else -> Color(0xFF3FB950)
    }
    val statusBg = when {
        isRunning -> Color(0xFF2E2412)
        isError -> Color(0xFF381E20)
        else -> Color(0xFF1B2C20)
    }
    val statusText = when {
        isRunning -> "ejecutando…"
        isError -> "exit ${exitCode ?: 1}"
        else -> "exit 0"
    }

    Surface(
        color = Color(0xFF0D1117),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFF30363D)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            // Header row: prompt symbol ❯, tool(command), and status badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "❯",
                        color = Color(0xFF58A6FF),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    val cleanCmd = command.trim().removeSurrounding("\"")
                    val displayCmd = if (cleanCmd.isNotBlank()) "$tool($cleanCmd)" else "$tool()"
                    Text(
                        text = displayCmd,
                        color = Color(0xFF79C0FF),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        maxLines = 1
                    )
                }

                Spacer(Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (duration != null && duration > 0) {
                        Text(
                            text = String.format(java.util.Locale.US, "%.2fs", duration),
                            color = Color(0xFF8B949E),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    Surface(
                        color = statusBg,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(9.dp),
                                    strokeWidth = 1.5.dp,
                                    color = statusColor
                                )
                            }
                            Text(
                                text = statusText,
                                color = statusColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Console output container (if output present)
            if (!output.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CONSOLE OUTPUT",
                                color = Color(0xFF8B949E),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (copied) "¡Copiado!" else "Copiar",
                                color = if (copied) Color(0xFF3FB950) else Color(0xFF58A6FF),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                modifier = Modifier.clickable {
                                    clipboardManager.setText(AnnotatedString(output))
                                    copied = true
                                }
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = Color(0xFF090D13),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, Color(0xFF21262D)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = output.trim(),
                                color = Color(0xFFC9D1D9),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.5.sp,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        }
    }
}
