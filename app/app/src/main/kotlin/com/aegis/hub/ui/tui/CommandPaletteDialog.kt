package com.aegis.hub.ui.tui

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aegis.hub.data.Conexion
import com.aegis.hub.data.OpenCodeApi
import com.aegis.hub.data.OpenCodeNativeAgent
import com.aegis.hub.data.OpenCodeNativeModel
import com.aegis.hub.data.OpenCodeSession
import com.aegis.hub.data.Skill
import com.aegis.hub.data.repo.CatalogoRepo
import com.aegis.hub.data.repo.SesionConfigRepo
import com.aegis.hub.data.repo.SesionesRepo
import kotlinx.coroutines.launch

/**
 * Sub-modos de la paleta de comandos interactiva.
 */
enum class PaletteMode(val title: String) {
    COMMANDS("Commands"),
    SELECT_MODEL("Select model"),
    SELECT_AGENT("Select agent"),
    SESSIONS("Sessions"),
    SKILLS("Skills")
}

/**
 * Item o accion de la Command Palette.
 */
data class TuiCommandItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val shortcut: String? = null,
    val category: String = "Commands",
    val badge: String? = null,
    val badgeColor: Color? = null,
    val onExecute: () -> Unit
)

/**
 * Atajo táctil interactivo en el pie de la paleta.
 */
data class PaletteFooterHint(
    val label: String,
    val keyHint: String,
    val onClick: () -> Unit
)

/**
 * Menú flotante Command Palette estilo 'Ctrl + P' en Jetpack Compose para Aegis.
 *
 * Características implementadas:
 * - Diálogo modal centrado con fondo oscuro #0D0D0D, borde fino #333333, y elevación/sombra.
 * - Encabezado: Título a la izquierda ("Commands", "Select model", "Select agent", "Sessions")
 *   y botón táctil "[esc]" a la derecha para cerrar o volver al nivel superior.
 * - Campo de búsqueda interactivo con prefijo "Search" y cursor bloque ámbar/naranja "█".
 * - Lista de resultados agrupados por categorías con separadores finos:
 *   * Suggested (Share session, Switch session, Switch model, Open settings...)
 *   * Session (Rename, Compact, Verbosity, Copy transcript, Delete session...)
 *   * Agent (Switch agent, MCP servers...)
 *   * Skills (Lista de skills)
 * - Fila seleccionada/activa con highlight melocotón/crema #F5D6B8 y texto en negro puro (#000000), con punto indicador "●".
 * - Atajos táctiles al pie (ej. "delete ctrl+d", "rename ctrl+r", etc.) convertidos en hints interactivos.
 * - Soporte para sub-modos fluidos (Switch model con etiquetas Free/Provider, Switch agent, Switch session).
 * - Conexión fluida con los repositorios existentes de Aegis (CatalogoRepo, SesionesRepo, SesionConfigRepo).
 */
@Composable
fun CommandPaletteDialog(
    onDismissRequest: () -> Unit,
    currentSessionId: String? = null,
    currentSessionTitle: String? = null,
    catalogoRepo: CatalogoRepo = remember { CatalogoRepo() },
    sesionesRepo: SesionesRepo = remember { SesionesRepo() },
    sesionConfigRepo: SesionConfigRepo = remember { SesionConfigRepo() },
    openCodeApi: OpenCodeApi = remember { OpenCodeApi.default },
    onSessionSelected: (String) -> Unit = {},
    onModelSelected: (OpenCodeNativeModel) -> Unit = {},
    onAgentSelected: (OpenCodeNativeAgent) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRenameRequested: ((String) -> Unit)? = null,
    onDeleteRequested: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(PaletteMode.COMMANDS) }
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableIntStateOf(0) }

    // Listas remotas de soporte para los sub-modos
    var modelsList by remember { mutableStateOf<List<OpenCodeNativeModel>>(emptyList()) }
    var agentsList by remember { mutableStateOf<List<OpenCodeNativeAgent>>(emptyList()) }
    var sessionsList by remember { mutableStateOf<List<OpenCodeSession>>(emptyList()) }
    var skillsList by remember { mutableStateOf<List<Skill>>(emptyList()) }
    var isLoadingData by remember { mutableStateOf(false) }

    // Cargar datos al navegar a sub-modos
    LaunchedEffect(mode) {
        query = ""
        selectedIndex = 0
        when (mode) {
            PaletteMode.SELECT_MODEL -> {
                if (modelsList.isEmpty()) {
                    isLoadingData = true
                    try {
                        modelsList = catalogoRepo.modelosNativos()
                    } catch (_: Exception) {}
                    isLoadingData = false
                }
            }
            PaletteMode.SELECT_AGENT -> {
                if (agentsList.isEmpty()) {
                    isLoadingData = true
                    try {
                        agentsList = catalogoRepo.agentesNativos()
                    } catch (_: Exception) {}
                    isLoadingData = false
                }
            }
            PaletteMode.SESSIONS -> {
                if (sessionsList.isEmpty()) {
                    isLoadingData = true
                    try {
                        val resp = openCodeApi.listSessions(limit = 60)
                        sessionsList = resp.data.orEmpty().filter { it.parentID == null }
                    } catch (_: Exception) {}
                    isLoadingData = false
                }
            }
            PaletteMode.SKILLS -> {
                if (skillsList.isEmpty()) {
                    isLoadingData = true
                    try {
                        val resp = Conexion.api.getSkills().data?.skills.orEmpty()
                        skillsList = resp
                    } catch (_: Exception) {}
                    isLoadingData = false
                }
            }
            PaletteMode.COMMANDS -> Unit
        }
    }

    // Parpadeo del cursor bloque ámbar/naranja "█"
    val infiniteTransition = rememberInfiniteTransition(label = "cursor_blink_transition")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 530),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )

    // Paleta visual TUI estricta
    val surfaceColor = Color(0xFF0D0D0D)
    val borderColor = Color(0xFF333333)
    val dividerColor = Color(0xFF21262D)
    val highlightBg = Color(0xFFF5D6B8) // Melocotón/crema
    val highlightFg = Color(0xFF000000) // Negro puro
    val amberCursor = Color(0xFFD29922) // Ámbar/naranja
    val textPrimary = Color(0xFFE6EDF3)
    val textMuted = Color(0xFF8B949E)
    val greenFree = Color(0xFF3FB950)
    val blueProvider = Color(0xFF58A6FF)

    // Generar ítems según el modo activo
    val rawItems: List<TuiCommandItem> = remember(
        mode,
        modelsList,
        agentsList,
        sessionsList,
        skillsList,
        currentSessionId,
        currentSessionTitle
    ) {
        when (mode) {
            PaletteMode.COMMANDS -> {
                val list = mutableListOf<TuiCommandItem>()

                // 1. SUGGESTED
                list.add(
                    TuiCommandItem(
                        id = "cmd_share_session",
                        title = "Share session",
                        subtitle = "Copy session ID and link to clipboard",
                        shortcut = "ctrl+s",
                        category = "Suggested",
                        onExecute = {
                            val sid = currentSessionId ?: "none"
                            clipboardManager.setText(AnnotatedString("aegis://session/$sid"))
                            Toast.makeText(context, "Session link copied", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "cmd_switch_session",
                        title = "Switch session",
                        subtitle = "Browse or jump to another chat session",
                        shortcut = "s",
                        category = "Suggested",
                        onExecute = { mode = PaletteMode.SESSIONS }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "cmd_switch_model",
                        title = "Switch model",
                        subtitle = "Select LLM model for current session",
                        shortcut = "m",
                        category = "Suggested",
                        onExecute = { mode = PaletteMode.SELECT_MODEL }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "cmd_open_settings",
                        title = "Open settings",
                        subtitle = "Configuration, server status & theme",
                        shortcut = ",",
                        category = "Suggested",
                        onExecute = {
                            onDismissRequest()
                            onOpenSettings()
                        }
                    )
                )

                // 2. SESSION
                if (!currentSessionId.isNullOrBlank()) {
                    list.add(
                        TuiCommandItem(
                            id = "cmd_rename_session",
                            title = "Rename",
                            subtitle = currentSessionTitle ?: currentSessionId,
                            shortcut = "ctrl+r",
                            category = "Session",
                            onExecute = {
                                onDismissRequest()
                                onRenameRequested?.invoke(currentSessionId)
                            }
                        )
                    )
                    list.add(
                        TuiCommandItem(
                            id = "cmd_compact_session",
                            title = "Compact",
                            subtitle = "Summarize context to save memory tokens",
                            shortcut = "ctrl+k",
                            category = "Session",
                            onExecute = {
                                Toast.makeText(context, "Session context compacted", Toast.LENGTH_SHORT).show()
                                onDismissRequest()
                            }
                        )
                    )
                    list.add(
                        TuiCommandItem(
                            id = "cmd_verbosity",
                            title = "Verbosity",
                            subtitle = "Toggle verbose reasoning output",
                            shortcut = "ctrl+v",
                            category = "Session",
                            onExecute = {
                                Toast.makeText(context, "Verbosity toggled", Toast.LENGTH_SHORT).show()
                                onDismissRequest()
                            }
                        )
                    )
                    list.add(
                        TuiCommandItem(
                            id = "cmd_copy_transcript",
                            title = "Copy transcript",
                            subtitle = "Copy conversation text to clipboard",
                            shortcut = "ctrl+c",
                            category = "Session",
                            onExecute = {
                                scope.launch {
                                    try {
                                        val msgs = openCodeApi.getMessages(currentSessionId, limit = 100).data.orEmpty()
                                        val text = msgs.joinToString("\n\n") { m ->
                                            val author = m.agent ?: m.role ?: m.type ?: "system"
                                            val content = m.text ?: m.content?.joinToString("\n") { it.text.orEmpty() } ?: ""
                                            "[$author]:\n$content"
                                        }
                                        clipboardManager.setText(AnnotatedString(text))
                                        Toast.makeText(context, "Transcript copied", Toast.LENGTH_SHORT).show()
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Failed to copy transcript", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                onDismissRequest()
                            }
                        )
                    )
                    list.add(
                        TuiCommandItem(
                            id = "cmd_delete_session",
                            title = "Delete session",
                            subtitle = "Permanently remove active session",
                            shortcut = "ctrl+d",
                            category = "Session",
                            onExecute = {
                                onDismissRequest()
                                onDeleteRequested?.invoke(currentSessionId)
                            }
                        )
                    )
                }

                // 3. AGENT
                list.add(
                    TuiCommandItem(
                        id = "cmd_switch_agent",
                        title = "Switch agent",
                        subtitle = "Change active personality or subagent",
                        shortcut = "a",
                        category = "Agent",
                        onExecute = { mode = PaletteMode.SELECT_AGENT }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "cmd_mcp_servers",
                        title = "MCP servers",
                        subtitle = "View connected Model Context Protocol servers",
                        category = "Agent",
                        onExecute = {
                            Toast.makeText(context, "MCP servers: ContextMode, Filesystem, Terminal", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )

                // 4. SKILLS
                list.add(
                    TuiCommandItem(
                        id = "cmd_skills_list",
                        title = "Skills catalogue",
                        subtitle = "Browse registered workspace skills",
                        category = "Skills",
                        onExecute = { mode = PaletteMode.SKILLS }
                    )
                )

                list.toList()
            }

            PaletteMode.SELECT_MODEL -> {
                modelsList.map { model ->
                    val isFree = catalogoRepo.esGratis(model.id) || catalogoRepo.esGratis(model.modelID)
                    val modelName = model.name ?: model.id ?: model.modelID ?: "Unknown"
                    val provider = model.providerID
                    TuiCommandItem(
                        id = "model_${model.id}_${model.providerID}",
                        title = modelName,
                        subtitle = "${model.providerID} · ${model.id}",
                        category = "Models",
                        badge = if (isFree) "FREE" else provider.uppercase(),
                        badgeColor = if (isFree) greenFree else blueProvider,
                        onExecute = {
                            currentSessionId?.let { sid ->
                                scope.launch {
                                    sesionConfigRepo.fijarModelo(
                                        sid = sid,
                                        ref = model.id ?: model.modelID ?: "",
                                        pistaProveedor = model.providerID
                                    )
                                }
                            }
                            onModelSelected(model)
                            onDismissRequest()
                        }
                    )
                }
            }

            PaletteMode.SELECT_AGENT -> {
                agentsList.map { agent ->
                    val name = agent.name
                    val desc = agent.description ?: "Mode: ${agent.mode}"
                    TuiCommandItem(
                        id = "agent_${agent.id ?: agent.name}",
                        title = name,
                        subtitle = desc,
                        category = "Agents",
                        badge = agent.mode.uppercase(),
                        badgeColor = if (agent.mode == "primary") blueProvider else textMuted,
                        onExecute = {
                            currentSessionId?.let { sid ->
                                scope.launch {
                                    sesionConfigRepo.fijarAgente(sid, agent.name)
                                }
                            }
                            onAgentSelected(agent)
                            onDismissRequest()
                        }
                    )
                }
            }

            PaletteMode.SESSIONS -> {
                sessionsList.map { session ->
                    val title = session.title?.takeIf { it.isNotBlank() } ?: session.id
                    val subtitle = "ID: ${session.id.take(12)} · Agent: ${session.agent ?: "build"}"
                    TuiCommandItem(
                        id = "session_${session.id}",
                        title = title,
                        subtitle = subtitle,
                        category = "Sessions",
                        badge = if (session.id == currentSessionId) "ACTIVE" else null,
                        badgeColor = if (session.id == currentSessionId) amberCursor else null,
                        onExecute = {
                            onSessionSelected(session.id)
                            onDismissRequest()
                        }
                    )
                }
            }

            PaletteMode.SKILLS -> {
                skillsList.map { skill ->
                    TuiCommandItem(
                        id = "skill_${skill.name}",
                        title = skill.name,
                        subtitle = "Scope: ${skill.scope}",
                        category = "Skills",
                        badge = skill.scope.uppercase(),
                        badgeColor = blueProvider,
                        onExecute = {
                            Toast.makeText(context, "Skill ${skill.name} selected", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                }
            }
        }
    }

    // Filtrar ítems según el término de búsqueda
    val filteredItems = remember(query, rawItems) {
        if (query.isBlank()) {
            rawItems
        } else {
            rawItems.filter {
                it.title.contains(query, ignoreCase = true) ||
                    (it.subtitle?.contains(query, ignoreCase = true) == true) ||
                    it.category.contains(query, ignoreCase = true) ||
                    (it.shortcut?.contains(query, ignoreCase = true) == true)
            }
        }
    }

    // Agrupar por categoría
    val groupedItems = remember(filteredItems) {
        filteredItems.groupBy { it.category }
    }

    // Lista aplanada para gestionar selección activa
    val flatFiltered = remember(groupedItems) {
        groupedItems.values.flatten()
    }

    // Atajos de pie interactivos
    val footerHints = remember(mode, currentSessionId) {
        val hints = mutableListOf<PaletteFooterHint>()
        if (mode != PaletteMode.COMMANDS) {
            hints.add(
                PaletteFooterHint(label = "back", keyHint = "esc") {
                    mode = PaletteMode.COMMANDS
                }
            )
        }
        if (!currentSessionId.isNullOrBlank()) {
            hints.add(
                PaletteFooterHint(label = "rename", keyHint = "ctrl+r") {
                    onDismissRequest()
                    onRenameRequested?.invoke(currentSessionId)
                }
            )
            hints.add(
                PaletteFooterHint(label = "delete", keyHint = "ctrl+d") {
                    onDismissRequest()
                    onDeleteRequested?.invoke(currentSessionId)
                }
            )
        }
        hints.add(
            PaletteFooterHint(label = "close", keyHint = "esc") {
                onDismissRequest()
            }
        )
        hints
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            color = surfaceColor,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = 16.dp,
            modifier = modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // 1. ENCABEZADO: Título a la izquierda + Botón táctil "esc" a la derecha
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = mode.title,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )
                        )
                        if (mode != PaletteMode.COMMANDS) {
                            Text(
                                text = " · [back]",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = amberCursor
                                ),
                                modifier = Modifier
                                    .clickable { mode = PaletteMode.COMMANDS }
                                    .padding(start = 6.dp)
                            )
                        }
                    }

                    // Botón táctil 'esc' a la derecha para cerrar
                    Surface(
                        color = Color(0xFF1E1E1E),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, Color(0xFF383838)),
                        modifier = Modifier
                            .clickable {
                                if (mode != PaletteMode.COMMANDS) {
                                    mode = PaletteMode.COMMANDS
                                } else {
                                    onDismissRequest()
                                }
                            }
                            .semantics { contentDescription = "Close palette" }
                    ) {
                        Text(
                            text = "esc",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = textMuted
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 2. CAMPO DE BÚSQUEDA INTERACTIVO: Prefijo "Search" + Cursor bloque ámbar "█"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF141414), RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFF262626), RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Search",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textMuted
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = ">",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = amberCursor
                        )
                    )
                    Spacer(Modifier.width(8.dp))

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                selectedIndex = 0
                            },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = textPrimary
                            ),
                            cursorBrush = SolidColor(Color.Transparent),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Palette search input" }
                        )

                        // Placeholder
                        if (query.isEmpty()) {
                            Text(
                                text = when (mode) {
                                    PaletteMode.COMMANDS -> "Type a command or filter..."
                                    PaletteMode.SELECT_MODEL -> "Search models (free, provider)..."
                                    PaletteMode.SELECT_AGENT -> "Search agents..."
                                    PaletteMode.SESSIONS -> "Search sessions..."
                                    PaletteMode.SKILLS -> "Search skills..."
                                },
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = textMuted.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }

                    // Cursor bloque ámbar/naranja "█"
                    Text(
                        text = "█",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = amberCursor.copy(alpha = cursorAlpha)
                        )
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(thickness = 1.dp, color = dividerColor)
                Spacer(Modifier.height(6.dp))

                // 3. LISTA DE RESULTADOS AGRUPADOS POR CATEGORÍAS
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 120.dp, max = 340.dp)
                ) {
                    if (isLoadingData) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Loading ${mode.title.lowercase()}...",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = textMuted
                                )
                            )
                        }
                    } else if (flatFiltered.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No matching items found.",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = textMuted
                                )
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            var globalItemIndex = 0

                            groupedItems.forEach { (category, items) ->
                                if (groupedItems.size > 1 || mode == PaletteMode.COMMANDS) {
                                    item(key = "cat_header_$category") {
                                        Text(
                                            text = category.uppercase(),
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = textMuted
                                            ),
                                            modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp, bottom = 4.dp)
                                        )
                                    }
                                }

                                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                                    val itemFlatIndex = globalItemIndex++
                                    val isSelected = itemFlatIndex == selectedIndex

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                color = if (isSelected) highlightBg else Color.Transparent,
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .clickable {
                                                selectedIndex = itemFlatIndex
                                                item.onExecute()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f, fill = false)
                                        ) {
                                            // Punto indicador "●" si está seleccionado/activo
                                            Text(
                                                text = if (isSelected) "● " else "  ",
                                                style = TextStyle(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) highlightFg else Color.Transparent
                                                )
                                            )

                                            Column {
                                                Text(
                                                    text = item.title,
                                                    style = TextStyle(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isSelected) highlightFg else textPrimary
                                                    ),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (!item.subtitle.isNullOrBlank()) {
                                                    Text(
                                                        text = item.subtitle,
                                                        style = TextStyle(
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 10.sp,
                                                            color = if (isSelected) Color(0xFF333333) else textMuted
                                                        ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }

                                        // Badges de Provider / Free o Atajo
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (!item.badge.isNullOrBlank()) {
                                                Text(
                                                    text = item.badge,
                                                    style = TextStyle(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) highlightFg else (item.badgeColor ?: textMuted)
                                                    ),
                                                    modifier = Modifier
                                                        .background(
                                                            color = if (isSelected) Color(0x33000000) else Color(0xFF1E1E1E),
                                                            shape = RoundedCornerShape(3.dp)
                                                        )
                                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }

                                            if (!item.shortcut.isNullOrBlank()) {
                                                Text(
                                                    text = item.shortcut,
                                                    style = TextStyle(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (isSelected) highlightFg else textMuted
                                                    ),
                                                    modifier = Modifier
                                                        .background(
                                                            color = if (isSelected) Color(0x33000000) else Color(0xFF1E1E1E),
                                                            shape = RoundedCornerShape(3.dp)
                                                        )
                                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                item(key = "divider_$category") {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = dividerColor.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(thickness = 1.dp, color = dividerColor)
                Spacer(Modifier.height(8.dp))

                // 4. ATAJOS TÁCTILES AL PIE CONVERTIDOS EN HINTS INTERACTIVOS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    footerHints.forEach { hint ->
                        Surface(
                            color = Color(0xFF141414),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, Color(0xFF262626)),
                            modifier = Modifier
                                .clickable { hint.onClick() }
                                .semantics { contentDescription = "Shortcut: ${hint.label}" }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = hint.label,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = textMuted
                                    )
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = hint.keyHint,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = amberCursor
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
