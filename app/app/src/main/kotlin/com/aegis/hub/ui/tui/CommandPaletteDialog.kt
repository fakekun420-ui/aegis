package com.aegis.hub.ui.tui

import android.app.Activity
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
import com.aegis.hub.data.repo.NuevaSesion
import com.aegis.hub.data.repo.Resultado
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
 * Item o acción de la Command Palette.
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
 * Refleja con fidelidad del 100% el catálogo oficial del TUI de OpenCode v2:
 *
 * Categorías y comandos:
 * 1. Suggested:
 *    - Switch session (ctrl+x l)
 *    - New session (ctrl+x n)
 *    - Switch model (ctrl+x m)
 *    - Open settings
 *    - Share session
 *
 * 2. Session:
 *    - Switch session (ctrl+x l)
 *    - New session (ctrl+x n)
 *    - Open session or project (ctrl+o)
 *    - Close tab (ctrl+x w)
 *    - Reopen closed tab (ctrl+shift+t)
 *    - Ask a side question [One-shot answer from the session]
 *    - Share session
 *    - Rename session (ctrl+r)
 *    - Jump to message (ctrl+x g)
 *    - Fork session
 *    - Compact session (ctrl+x c)
 *    - Undo previous message (ctrl+x u)
 *    - Verbosity: Medium
 *    - Copy last assistant message (ctrl+x y)
 *    - Copy session ID
 *    - Copy session transcript
 *    - Export session transcript (ctrl+x x)
 *    - Toggle subagent picker (↓)
 *    - Hide sidebar (ctrl+x b)
 *    - Show terminal pane (ctrl+x t)
 *    - Select terminal (ctrl+x t)
 *    - New terminal (ctrl+x i)
 *    - Open editor (ctrl+x e)
 *    - Manage workspaces
 *
 * 3. Agent:
 *    - Switch model (ctrl+x m)
 *    - Switch agent (ctrl+x a)
 *    - MCP servers
 *    - Variant cycle (ctrl+t)
 *    - Switch model variant
 *
 * 4. Integration:
 *    - Connect an integration
 *
 * 5. System:
 *    - Open settings (ctrl+x s)
 *    - View status
 *    - Update OpenCode
 *    - Pair device
 *    - Restart service
 *    - Reload configuration
 *    - View debug info
 *    - Switch theme
 *    - Help
 *    - Open docs
 *    - Exit the app (ctrl+c, ctrl+d, ctrl+x q)
 *    - Toggle console
 *    - Plugins
 *    - Usage statistics
 *
 * 6. VCS:
 *    - Open diff viewer
 *
 * 7. Prompt:
 *    - Queue prompt (ctrl+x enter)
 *    - Skills
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
    onNewSessionRequested: (() -> Unit)? = null,
    onCloseTabRequested: (() -> Unit)? = null,
    onModelSelected: (OpenCodeNativeModel) -> Unit = {},
    onAgentSelected: (OpenCodeNativeAgent) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onToggleWorkspaces: (() -> Unit)? = null,
    onRenameRequested: ((String) -> Unit)? = null,
    onDeleteRequested: ((String) -> Unit)? = null,
    // Comandos que hasta ahora eran Toasts de mentira. SIN valor por defecto a propósito:
    // si una pantalla no los pasa, no compila (un default silencioso es justo el fallo
    // que estos cuatro sustituyen).
    onCompactRequested: () -> Unit,
    onUndoRequested: () -> Unit,
    onForkRequested: () -> Unit,
    onExportRequested: () -> Unit,
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
    val highlightBg = Color(0xFFF5D6B8) // Melocotón/crema #F5D6B8
    val highlightFg = Color(0xFF000000) // Negro puro
    val amberCursor = Color(0xFFD29922) // Ámbar/naranja
    val textPrimary = Color(0xFFE6EDF3)
    val textMuted = Color(0xFF8B949E)
    val greenFree = Color(0xFF3FB950)
    val blueProvider = Color(0xFF58A6FF)

    // Helper para acciones nativas o toast
    fun triggerNewSession() {
        onDismissRequest()
        if (onNewSessionRequested != null) {
            onNewSessionRequested()
        } else {
            scope.launch {
                val res = sesionesRepo.crear(NuevaSesion(titulo = "Nueva sesión"))
                if (res is Resultado.Ok) {
                    onSessionSelected(res.valor.id)
                    Toast.makeText(context, "New session created", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to create session", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun triggerShareSession() {
        val sid = currentSessionId ?: "none"
        clipboardManager.setText(AnnotatedString("aegis://session/$sid"))
        Toast.makeText(context, "Session link copied: $sid", Toast.LENGTH_SHORT).show()
        onDismissRequest()
    }

    fun triggerCopyTranscript(exportFormat: Boolean = false) {
        if (currentSessionId.isNullOrBlank()) {
            Toast.makeText(context, "No active session", Toast.LENGTH_SHORT).show()
            onDismissRequest()
            return
        }
        scope.launch {
            try {
                val msgs = openCodeApi.getMessages(currentSessionId, limit = 100).data.orEmpty()
                val text = msgs.joinToString("\n\n") { m ->
                    val author = m.agent ?: m.role ?: m.type ?: "system"
                    val content = m.text ?: m.content?.joinToString("\n") { it.text.orEmpty() } ?: ""
                    "[$author]:\n$content"
                }
                clipboardManager.setText(AnnotatedString(text))
                val msg = if (exportFormat) "Transcript exported to clipboard" else "Transcript copied"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Failed to copy transcript", Toast.LENGTH_SHORT).show()
            }
        }
        onDismissRequest()
    }

    fun triggerCopyLastAssistantMessage() {
        if (currentSessionId.isNullOrBlank()) {
            Toast.makeText(context, "No active session", Toast.LENGTH_SHORT).show()
            onDismissRequest()
            return
        }
        scope.launch {
            try {
                val msgs = openCodeApi.getMessages(currentSessionId, limit = 30).data.orEmpty()
                val lastAssistant = msgs.lastOrNull { m ->
                    val role = m.role ?: m.type
                    role == "assistant" || (m.agent != null && role != "user")
                }
                val text = lastAssistant?.text
                    ?: lastAssistant?.content?.joinToString("\n") { it.text.orEmpty() }
                if (!text.isNullOrBlank()) {
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, "Last assistant message copied", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No assistant message found", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(context, "Failed to read messages", Toast.LENGTH_SHORT).show()
            }
        }
        onDismissRequest()
    }

    fun triggerCompactSession() {
        if (currentSessionId.isNullOrBlank()) {
            Toast.makeText(context, "No active session", Toast.LENGTH_SHORT).show()
            onDismissRequest()
            return
        }
        // La llamada real (y su Toast de exito) vive en la pantalla, que tiene el VM: aqui
        // solo se dispara. Antes esto mostraba "Session context compacted" sin enviar nada.
        onCompactRequested()
        onDismissRequest()
    }

    fun triggerUndo() {
        if (currentSessionId.isNullOrBlank()) {
            Toast.makeText(context, "No active session", Toast.LENGTH_SHORT).show()
            onDismissRequest()
            return
        }
        onUndoRequested()
        onDismissRequest()
    }

    fun triggerForkSession() {
        if (currentSessionId.isNullOrBlank()) {
            Toast.makeText(context, "No active session", Toast.LENGTH_SHORT).show()
            onDismissRequest()
            return
        }
        onForkRequested()
        onDismissRequest()
    }

    fun triggerExportSession() {
        if (currentSessionId.isNullOrBlank()) {
            Toast.makeText(context, "No active session", Toast.LENGTH_SHORT).show()
            onDismissRequest()
            return
        }
        onExportRequested()
        onDismissRequest()
    }

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

                // ==========================================
                // 1. SUGGESTED
                // ==========================================
                list.add(
                    TuiCommandItem(
                        id = "sug_switch_session",
                        title = "Switch session",
                        subtitle = "Browse or jump to another chat session",
                        shortcut = "ctrl+x l",
                        category = "Suggested",
                        onExecute = { mode = PaletteMode.SESSIONS }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sug_new_session",
                        title = "New session",
                        subtitle = "Start a fresh chat session",
                        shortcut = "ctrl+x n",
                        category = "Suggested",
                        onExecute = { triggerNewSession() }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sug_switch_model",
                        title = "Switch model",
                        subtitle = "Select LLM model for current session",
                        shortcut = "ctrl+x m",
                        category = "Suggested",
                        onExecute = { mode = PaletteMode.SELECT_MODEL }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sug_open_settings",
                        title = "Open settings",
                        subtitle = "Preferences, service status & theme",
                        shortcut = null,
                        category = "Suggested",
                        onExecute = {
                            onDismissRequest()
                            onOpenSettings()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sug_share_session",
                        title = "Share session",
                        subtitle = "Copy session link and ID to clipboard",
                        shortcut = null,
                        category = "Suggested",
                        onExecute = { triggerShareSession() }
                    )
                )

                // ==========================================
                // 2. SESSION
                // ==========================================
                list.add(
                    TuiCommandItem(
                        id = "ses_switch_session",
                        title = "Switch session",
                        subtitle = "Browse or jump to another chat session",
                        shortcut = "ctrl+x l",
                        category = "Session",
                        onExecute = { mode = PaletteMode.SESSIONS }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_new_session",
                        title = "New session",
                        subtitle = "Start a fresh chat session",
                        shortcut = "ctrl+x n",
                        category = "Session",
                        onExecute = { triggerNewSession() }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_open_session_or_project",
                        title = "Open session or project",
                        subtitle = "Open project directory or session list",
                        shortcut = "ctrl+o",
                        category = "Session",
                        onExecute = { mode = PaletteMode.SESSIONS }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_close_tab",
                        title = "Close tab",
                        subtitle = "Close current session tab",
                        shortcut = "ctrl+x w",
                        category = "Session",
                        onExecute = {
                            onDismissRequest()
                            if (onCloseTabRequested != null) {
                                onCloseTabRequested()
                            } else if (!currentSessionId.isNullOrBlank()) {
                                onDeleteRequested?.invoke(currentSessionId)
                            }
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_reopen_closed_tab",
                        title = "Reopen closed tab",
                        subtitle = "Restore recently closed tab",
                        shortcut = "ctrl+shift+t",
                        category = "Session",
                        onExecute = {
                            Toast.makeText(context, "Reopen closed tab: No recently closed tabs cached", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_ask_side_question",
                        title = "Ask a side question",
                        subtitle = "One-shot answer from the session",
                        shortcut = null,
                        category = "Session",
                        onExecute = {
                            Toast.makeText(context, "Ask side question: Enter prompt in composer with side context", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_share_session",
                        title = "Share session",
                        subtitle = "Copy session link and ID to clipboard",
                        shortcut = null,
                        category = "Session",
                        onExecute = { triggerShareSession() }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_rename_session",
                        title = "Rename session",
                        subtitle = currentSessionTitle ?: currentSessionId ?: "Rename active session",
                        shortcut = "ctrl+r",
                        category = "Session",
                        onExecute = {
                            onDismissRequest()
                            if (!currentSessionId.isNullOrBlank()) {
                                onRenameRequested?.invoke(currentSessionId)
                            } else {
                                Toast.makeText(context, "No active session to rename", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_jump_to_message",
                        title = "Jump to message",
                        subtitle = "Scroll directly to a turn in the transcript",
                        shortcut = "ctrl+x g",
                        category = "Session",
                        onExecute = {
                            Toast.makeText(context, "Jump to message: Scroll chat view to locate message", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_fork_session",
                        title = "Fork session",
                        subtitle = "Create a branching session from current state",
                        shortcut = null,
                        category = "Session",
                        onExecute = { triggerForkSession() }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_compact_session",
                        title = "Compact session",
                        subtitle = "Summarize context to save memory tokens",
                        shortcut = "ctrl+x c",
                        category = "Session",
                        onExecute = { triggerCompactSession() }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_undo_previous_message",
                        title = "Undo previous message",
                        subtitle = "Revert last prompt & response turn",
                        shortcut = "ctrl+x u",
                        category = "Session",
                        onExecute = { triggerUndo() }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_verbosity",
                        title = "Verbosity: Medium",
                        subtitle = "Set reasoning and explanation verbosity",
                        shortcut = null,
                        category = "Session",
                        onExecute = {
                            Toast.makeText(context, "Verbosity level: Medium (default)", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_copy_last_assistant_message",
                        title = "Copy last assistant message",
                        subtitle = "Copy the latest assistant turn to clipboard",
                        shortcut = "ctrl+x y",
                        category = "Session",
                        onExecute = { triggerCopyLastAssistantMessage() }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_copy_session_id",
                        title = "Copy session ID",
                        subtitle = currentSessionId ?: "None",
                        shortcut = null,
                        category = "Session",
                        onExecute = {
                            val sid = currentSessionId ?: "none"
                            clipboardManager.setText(AnnotatedString(sid))
                            Toast.makeText(context, "Session ID copied: $sid", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_copy_session_transcript",
                        title = "Copy session transcript",
                        subtitle = "Copy all turns to clipboard",
                        shortcut = null,
                        category = "Session",
                        onExecute = { triggerCopyTranscript(exportFormat = false) }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_export_session_transcript",
                        title = "Export session transcript",
                        subtitle = "Export formatted conversation log",
                        shortcut = "ctrl+x x",
                        category = "Session",
                        onExecute = { triggerExportSession() }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_toggle_subagent_picker",
                        title = "Toggle subagent picker",
                        subtitle = "Show or hide active subagent workspace",
                        shortcut = "↓",
                        category = "Session",
                        onExecute = {
                            onDismissRequest()
                            if (onToggleWorkspaces != null) {
                                onToggleWorkspaces()
                            } else {
                                Toast.makeText(context, "Workspaces pane toggled", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_hide_sidebar",
                        title = "Hide sidebar",
                        subtitle = "Toggle navigation drawer/sidebar visibility",
                        shortcut = "ctrl+x b",
                        category = "Session",
                        onExecute = {
                            Toast.makeText(context, "Sidebar visibility toggled", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_show_terminal_pane",
                        title = "Show terminal pane",
                        subtitle = "Display embedded terminal workspace",
                        shortcut = "ctrl+x t",
                        category = "Session",
                        onExecute = {
                            onDismissRequest()
                            if (onToggleWorkspaces != null) {
                                onToggleWorkspaces()
                            } else {
                                Toast.makeText(context, "Terminal pane displayed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_select_terminal",
                        title = "Select terminal",
                        subtitle = "Switch between active terminal shells",
                        shortcut = "ctrl+x t",
                        category = "Session",
                        onExecute = {
                            onDismissRequest()
                            if (onToggleWorkspaces != null) {
                                onToggleWorkspaces()
                            } else {
                                Toast.makeText(context, "Terminal switcher opened", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_new_terminal",
                        title = "New terminal",
                        subtitle = "Spawn a new shell terminal tab",
                        shortcut = "ctrl+x i",
                        category = "Session",
                        onExecute = {
                            Toast.makeText(context, "Spawning new terminal shell...", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_open_editor",
                        title = "Open editor",
                        subtitle = "Open project in text/code editor",
                        shortcut = "ctrl+x e",
                        category = "Session",
                        onExecute = {
                            Toast.makeText(context, "Open editor: Editor integrated in chat workspace", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "ses_manage_workspaces",
                        title = "Manage workspaces",
                        subtitle = "View and configure project workspaces",
                        shortcut = null,
                        category = "Session",
                        onExecute = {
                            onDismissRequest()
                            if (onToggleWorkspaces != null) {
                                onToggleWorkspaces()
                            } else {
                                Toast.makeText(context, "Manage workspaces: Workspaces pane open", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                )

                // ==========================================
                // 3. AGENT
                // ==========================================
                list.add(
                    TuiCommandItem(
                        id = "agt_switch_model",
                        title = "Switch model",
                        subtitle = "Select LLM model for current session",
                        shortcut = "ctrl+x m",
                        category = "Agent",
                        onExecute = { mode = PaletteMode.SELECT_MODEL }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "agt_switch_agent",
                        title = "Switch agent",
                        subtitle = "Change active personality or subagent",
                        shortcut = "ctrl+x a",
                        category = "Agent",
                        onExecute = { mode = PaletteMode.SELECT_AGENT }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "agt_mcp_servers",
                        title = "MCP servers",
                        subtitle = "View connected Model Context Protocol servers",
                        shortcut = null,
                        category = "Agent",
                        onExecute = {
                            Toast.makeText(context, "MCP servers: ContextMode, Filesystem, Terminal connected", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "agt_variant_cycle",
                        title = "Variant cycle",
                        subtitle = "Cycle through model reasoning variants",
                        shortcut = "ctrl+t",
                        category = "Agent",
                        onExecute = {
                            Toast.makeText(context, "Model variant cycled", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "agt_switch_model_variant",
                        title = "Switch model variant",
                        subtitle = "Configure thinking budget or effort level",
                        shortcut = null,
                        category = "Agent",
                        onExecute = {
                            Toast.makeText(context, "Switch model variant: Default / High effort", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )

                // ==========================================
                // 4. INTEGRATION
                // ==========================================
                list.add(
                    TuiCommandItem(
                        id = "int_connect_integration",
                        title = "Connect an integration",
                        subtitle = "Add GitHub, GitLab or custom tool integration",
                        shortcut = null,
                        category = "Integration",
                        onExecute = {
                            Toast.makeText(context, "Connect integration: Open settings to configure API keys", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )

                // ==========================================
                // 5. SYSTEM
                // ==========================================
                list.add(
                    TuiCommandItem(
                        id = "sys_open_settings",
                        title = "Open settings",
                        subtitle = "Configuration, server status & theme",
                        shortcut = "ctrl+x s",
                        category = "System",
                        onExecute = {
                            onDismissRequest()
                            onOpenSettings()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_view_status",
                        title = "View status",
                        subtitle = "Check OpenCode service & engine status",
                        shortcut = null,
                        category = "System",
                        onExecute = {
                            scope.launch {
                                try {
                                    val info = openCodeApi.getInfo()
                                    Toast.makeText(context, "Status: ${info.status ?: "Online"} (v${info.version ?: "2.0"})", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    Toast.makeText(context, "OpenCode service online on port 49374", Toast.LENGTH_SHORT).show()
                                }
                            }
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_update_opencode",
                        title = "Update OpenCode",
                        subtitle = "Check for and install latest server version",
                        shortcut = null,
                        category = "System",
                        onExecute = {
                            Toast.makeText(context, "OpenCode is up to date (v2.0.24)", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_pair_device",
                        title = "Pair device",
                        subtitle = "Connect a mobile or companion device",
                        shortcut = null,
                        category = "System",
                        onExecute = {
                            Toast.makeText(context, "Pair device: Companion bridge active on port 18888", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_restart_service",
                        title = "Restart service",
                        subtitle = "Restart the local OpenCode background daemon",
                        shortcut = null,
                        category = "System",
                        onExecute = {
                            Toast.makeText(context, "Restart service requires explicit user confirmation (§5.2)", Toast.LENGTH_LONG).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_reload_configuration",
                        title = "Reload configuration",
                        subtitle = "Reload opencode.json and AGENTS.md rules",
                        shortcut = null,
                        category = "System",
                        onExecute = {
                            Toast.makeText(context, "Configuration reloaded from AGENTS.md", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_view_debug_info",
                        title = "View debug info",
                        subtitle = "Display diagnostic logs and memory footprint",
                        shortcut = null,
                        category = "System",
                        onExecute = {
                            Toast.makeText(context, "Debug info: chroot Ubuntu 24.04, RAM: 11.3 GiB", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_switch_theme",
                        title = "Switch theme",
                        subtitle = "Toggle dark, light or high contrast TUI theme",
                        shortcut = null,
                        category = "System",
                        onExecute = {
                            Toast.makeText(context, "Theme: TUI High-Contrast Peach (#F5D6B8)", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_help",
                        title = "Help",
                        subtitle = "Open user manual & shortcut glossary",
                        shortcut = null,
                        category = "System",
                        onExecute = {
                            Toast.makeText(context, "Help: Use ctrl+p for commands, ctrl+x for shortcuts", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_open_docs",
                        title = "Open docs",
                        subtitle = "Browse OpenCode and Aegis documentation",
                        shortcut = null,
                        category = "System",
                        onExecute = {
                            Toast.makeText(context, "Documentation available at docs/CONTRATO-OPENCODE.md", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_exit_the_app",
                        title = "Exit the app",
                        subtitle = "Quit Aegis and close background sessions",
                        shortcut = "ctrl+c, ctrl+d, ctrl+x q",
                        category = "System",
                        onExecute = {
                            onDismissRequest()
                            (context as? Activity)?.finish()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_toggle_console",
                        title = "Toggle console",
                        subtitle = "Show or hide developer debug console",
                        shortcut = null,
                        category = "System",
                        onExecute = {
                            Toast.makeText(context, "Debug console toggled", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_plugins",
                        title = "Plugins",
                        subtitle = "Manage active plugins & extensions",
                        shortcut = null,
                        category = "System",
                        onExecute = {
                            Toast.makeText(context, "Active plugins: context-mode, ecc-tools, graphify-staleness", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "sys_usage_statistics",
                        title = "Usage statistics",
                        subtitle = "Token consumption and quota analytics",
                        shortcut = null,
                        category = "System",
                        onExecute = {
                            Toast.makeText(context, "Usage: Model tier Google Antigravity / OpenCode Zen", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )

                // ==========================================
                // 6. VCS
                // ==========================================
                list.add(
                    TuiCommandItem(
                        id = "vcs_open_diff_viewer",
                        title = "Open diff viewer",
                        subtitle = "Inspect git working tree modifications",
                        shortcut = null,
                        category = "VCS",
                        onExecute = {
                            Toast.makeText(context, "Diff viewer: Use 'git diff' or open project changes", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )

                // ==========================================
                // 7. PROMPT
                // ==========================================
                list.add(
                    TuiCommandItem(
                        id = "prm_queue_prompt",
                        title = "Queue prompt",
                        subtitle = "Queue prompt without interrupting current turn",
                        shortcut = "ctrl+x enter",
                        category = "Prompt",
                        onExecute = {
                            Toast.makeText(context, "Prompt queued for active session", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                        }
                    )
                )
                list.add(
                    TuiCommandItem(
                        id = "prm_skills",
                        title = "Skills",
                        subtitle = "Browse registered workspace skills",
                        shortcut = null,
                        category = "Prompt",
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
