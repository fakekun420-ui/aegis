package com.aegis.hub.ui.tui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegis.hub.data.AttachedFile

/**
 * Paleta TUI monocromática estricta con estética terminal / hacker.
 */
private val TuiBorderFocused = Color(0xFF404040)
private val TuiTextMuted = Color(0xFF737373)
private val TuiAccentYellow = Color(0xFFE5C07B)
private val TuiChipBackground = Color(0xFF141414)
private val TuiChipBorder = Color(0xFF2A2A2A)

/**
 * Formatea el tamaño de un archivo para visualización en chip monoespaciado.
 */
private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> String.format(java.util.Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
}

/**
 * Composer rectangular monocromático con estilo terminal TUI para Aegis.
 *
 * Características:
 * - Marco rectangular con borde fino monocromático (sin bordes redondeados tipo píldora).
 * - Cursor de texto estilo bloque vertical "▌" parpadeante o bloque terminal.
 * - Línea intermedia de estado durante tareas de subagente:
 *   "⠆ [Subagent Name] — [Task description]"
 *   "Press ctrl+b to move running work to the background"
 * - Chip/selector inferior izquierdo dentro del marco con el agente y modelo actual:
 *   "[Agente] · [Modelo]" (al tocarlo abre el modal correspondiente o selector conjunto).
 * - Soporte para adjuntos/archivos (chips monoespaciados tipo "file 156667.png").
 */
@Composable
fun TuiComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    listening: Boolean,
    attachedFiles: List<AttachedFile>,
    onRemoveFile: (Int) -> Unit,
    selectedModelName: String,
    onSelectModelClick: () -> Unit,
    agentMode: String,
    onSelectAgentClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Estado de subagente en ejecución (si aplica)
    subagentName: String? = null,
    subagentTask: String? = null,
    onBackgroundWork: (() -> Unit)? = null,
    // Opciones adicionales de chat
    leerEnVoz: Boolean = false,
    onToggleLeerEnVoz: (() -> Unit)? = null,
    placeholder: String = "Type a message, @ to tag, / for commands..."
) {
    // Parpadeo del cursor bloque TUI "▌"
    val infiniteTransition = rememberInfiniteTransition(label = "tui_cursor_blink")
    val cursorVisible by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 530),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_opacity"
    )

    Surface(
        color = TuiBackground,
        shape = RoundedCornerShape(0.dp), // Marco estrictamente rectangular
        border = BorderStroke(1.dp, TuiBorder),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            // 1. Chips de adjuntos monoespaciados si existen
            if (attachedFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    attachedFiles.forEachIndexed { idx, file ->
                        TuiAttachmentChip(
                            file = file,
                            onRemove = { onRemoveFile(idx) }
                        )
                    }
                }
            }

            // 2. Línea intermedia de estado durante tareas de subagente
            if (!subagentName.isNullOrBlank()) {
                TuiSubagentStatusBar(
                    subagentName = subagentName,
                    taskDescription = subagentTask.orEmpty(),
                    onBackground = onBackgroundWork
                )
                Spacer(Modifier.height(6.dp))
            }

            // 3. Campo de texto con prompt terminal "> " y cursor bloque
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Terminal prompt input" },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = TuiTextPrimary,
                        lineHeight = 18.sp
                    ),
                    cursorBrush = SolidColor(Color.Transparent), // Ocultamos el cursor del sistema para dibujar el cursor bloque
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(onDone = { onSend() }),
                    decorationBox = { innerTextField ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "> ",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TuiTextMuted
                                )
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                if (text.isEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = placeholder,
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 13.sp,
                                                color = TuiTextMuted.copy(alpha = 0.7f)
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        // Cursor bloque cuando está vacío
                                        if (cursorVisible > 0.45f) {
                                            Text(
                                                text = "▌",
                                                style = TextStyle(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 13.sp,
                                                    color = TuiTextPrimary
                                                )
                                            )
                                        }
                                    }
                                }
                                innerTextField()
                            }
                        }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            // 4. Barra de herramientas inferior dentro del marco rectangular
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Selector inferior izquierdo: Agente + Modelo actual
                TuiAgentModelSelector(
                    agentMode = agentMode,
                    selectedModelName = selectedModelName,
                    onAgentClick = onSelectAgentClick,
                    onModelClick = onSelectModelClick
                )

                Spacer(Modifier.width(8.dp))

                // Controles de acción a la derecha
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Botón de adjuntar archivo
                    IconButton(
                        onClick = onAttach,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "Attach file",
                            tint = TuiTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Botón TTS (leer en voz alta) si está provisto
                    if (onToggleLeerEnVoz != null) {
                        IconButton(
                            onClick = onToggleLeerEnVoz,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = if (leerEnVoz) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                contentDescription = "TTS toggle",
                                tint = if (leerEnVoz) TuiAccentYellow else TuiTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Botón de micrófono
                    IconButton(
                        onClick = onMic,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = if (listening) Icons.Filled.Mic else Icons.Filled.MicNone,
                            contentDescription = if (listening) "Stop listening" else "Voice input",
                            tint = if (listening) Color(0xFFE06C75) else TuiTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Botón de enviar (estilo terminal con enter / flecha hacia arriba)
                    val canSend = text.isNotBlank() || attachedFiles.isNotEmpty()
                    Surface(
                        onClick = onSend,
                        enabled = canSend,
                        shape = RoundedCornerShape(0.dp),
                        color = if (canSend) Color(0xFF1E1E1E) else Color.Transparent,
                        border = BorderStroke(1.dp, if (canSend) Color(0xFF3E3E3E) else Color(0xFF1F1F1F)),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.North,
                                contentDescription = "Send",
                                tint = if (canSend) TuiTextPrimary else TuiTextMuted.copy(alpha = 0.4f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chip monoespaciado para archivos adjuntos ("file filename.png 12KB").
 */
@Composable
private fun TuiAttachmentChip(
    file: AttachedFile,
    onRemove: () -> Unit
) {
    Surface(
        color = TuiChipBackground,
        shape = RoundedCornerShape(0.dp),
        border = BorderStroke(1.dp, TuiChipBorder),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = "file ${file.name.take(20)} (${formatSize(file.size)})",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TuiTextPrimary
                )
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Remove",
                tint = TuiTextMuted,
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onRemove() }
            )
        }
    }
}

/**
 * Línea intermedia de estado durante tareas de subagente:
 * "⠆ [Subagent Name] — [Task description]"
 * "Press ctrl+b to move running work to the background"
 */
@Composable
private fun TuiSubagentStatusBar(
    subagentName: String,
    taskDescription: String,
    onBackground: (() -> Unit)?
) {
    Surface(
        color = Color(0xFF111111),
        shape = RoundedCornerShape(0.dp),
        border = BorderStroke(1.dp, Color(0xFF2B2B2B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⠆",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TuiAccentYellow
                    )
                )
                Spacer(Modifier.width(6.dp))
                val descriptionPart = if (taskDescription.isNotBlank()) " — $taskDescription" else ""
                Text(
                    text = "$subagentName$descriptionPart",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE5C07B)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Press ctrl+b to move running work to the background",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TuiTextMuted
                    )
                )
                if (onBackground != null) {
                    Text(
                        text = "[ctrl+b]",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TuiTextPrimary
                        ),
                        modifier = Modifier
                            .clickable { onBackground() }
                            .padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Chip/selector inferior izquierdo dentro del marco rectangular con el agente y modelo actual.
 * Ej: "Dir-Kaesoft · Gemini 3.8 Flash (Antigravity) Google"
 */
@Composable
private fun TuiAgentModelSelector(
    agentMode: String,
    selectedModelName: String,
    onAgentClick: () -> Unit,
    onModelClick: () -> Unit
) {
    Surface(
        color = TuiChipBackground,
        shape = RoundedCornerShape(0.dp),
        border = BorderStroke(1.dp, TuiChipBorder),
        modifier = Modifier.clickable {
            // Si el usuario toca el selector, dispara el cambio de modelo o agente
            onModelClick()
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            // Etiqueta del agente
            Text(
                text = agentMode.ifBlank { "Agent" },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF61AFEF)
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onAgentClick() }
            )

            Text(
                text = " · ",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TuiTextMuted
                )
            )

            // Etiqueta del modelo
            Text(
                text = selectedModelName.ifBlank { "Select Model" },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TuiTextPrimary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onModelClick() }
            )
        }
    }
}
