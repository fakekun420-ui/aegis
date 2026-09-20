package com.opencode.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.companion.data.Project
import com.opencode.companion.data.SessionRef
import com.opencode.companion.data.Skill
import com.opencode.companion.util.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    project: Project,
    sessions: List<SessionRef>,
    skills: List<Skill>,
    linkedProjects: List<Project>,
    isLoading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onSendNewSession: (String) -> Unit,
    onRefresh: () -> Unit,
    onClearError: () -> Unit,
    onCreateSkill: (String, String, String) -> Unit,
    onDeleteSkill: (String, String) -> Unit,
    onLinkProject: (String) -> Unit,
    onUnlinkProject: (String) -> Unit,
    onPatchInstructions: (String) -> Unit = {}
) {
    var composerText by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) } // 0: Chats, 1: Archivos e instrucciones
    var showSkillDialog by remember { mutableStateOf(false) }
    var showInstructionsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.semantics { contentDescription = project.name }
                    ) {
                        Text(
                            project.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Normal
                            ),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        ProviderBadge(project.resolvedProvider)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Volver" }
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onSendNewSession("") },
                icon = { Icon(Icons.Filled.Add, contentDescription = "Nuevo chat") },
                text = { Text("+ Nuevo chat", fontWeight = FontWeight.Medium) },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.semantics { contentDescription = "Nuevo chat" }
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.navigationBarsPadding().imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = composerText,
                        onValueChange = { composerText = it },
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Mensaje para ${project.name}" },
                        placeholder = { Text("Mensaje para ${project.name}…") },
                        shape = RoundedCornerShape(20.dp),
                        maxLines = 5
                    )
                    IconButton(
                        onClick = {
                            val t = composerText.trim()
                            if (t.isNotBlank()) {
                                onSendNewSession(t)
                                composerText = ""
                            }
                        },
                        enabled = composerText.isNotBlank(),
                        modifier = Modifier.semantics { contentDescription = "Enviar mensaje" }
                    ) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = "Enviar mensaje",
                            tint = if (composerText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("Chats (${sessions.size})", fontWeight = FontWeight.Medium) },
                    modifier = Modifier.semantics { contentDescription = "Chats" }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Archivos e instrucciones", fontWeight = FontWeight.Medium) },
                    modifier = Modifier.semantics { contentDescription = "Archivos e instrucciones" }
                )
            }

            if (isLoading && sessions.isEmpty() && skills.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                when (tab) {
                    0 -> {
                        if (sessions.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Inbox,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text("Sin chats vinculados", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Pulsa + Nuevo chat o escribe abajo para comenzar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 84.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(sessions, key = { it.sessionId }) { s ->
                                    OutlinedCard(
                                        onClick = { onOpenSession(s.sessionId) },
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .semantics(mergeDescendants = true) { contentDescription = s.title ?: s.sessionId.take(8) }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f, fill = false)
                                            ) {
                                                Icon(
                                                    Icons.Outlined.ChatBubbleOutline,
                                                    contentDescription = "Chat",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    s.title ?: s.sessionId.take(8),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                val timeStr = s.lastUsed ?: s.createdAt
                                                if (!timeStr.isNullOrBlank()) {
                                                    Text(
                                                        " ◦ ${relativeTime(timeStr)}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                            ProviderBadge(s.resolvedProvider(project.provider))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 84.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Instrucciones del proyecto
                            item {
                                OutlinedCard(
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Description,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Text(
                                                    "Instrucciones del proyecto",
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            IconButton(
                                                onClick = { showInstructionsDialog = true },
                                                modifier = Modifier.size(32.dp).semantics { contentDescription = "Editar instrucciones" }
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Edit,
                                                    contentDescription = "Editar instrucciones",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        val instructions = project.description?.trim()
                                        if (instructions.isNullOrBlank()) {
                                            Text(
                                                "Sin instrucciones personalizadas. Pulsa el lápiz para definir el contexto, estilo y comportamiento esperado para este proyecto.",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        } else {
                                            Text(
                                                instructions,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = FontFamily.Serif,
                                                    fontSize = 15.sp,
                                                    lineHeight = 22.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }

                            // Archivos y habilidades
                            item {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Extension,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            "Archivos adjuntos y habilidades",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    TextButton(onClick = { showSkillDialog = true }) {
                                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Añadir", fontWeight = FontWeight.Medium)
                                    }
                                }
                            }

                            if (skills.isEmpty()) {
                                item {
                                    Text(
                                        "No hay habilidades o archivos adjuntos configurados.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                items(skills, key = { "${it.scope}:${it.name}" }) { sk ->
                                    OutlinedCard(
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = sk.name }
                                    ) {
                                        ListItem(
                                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                            headlineContent = {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text(sk.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium))
                                                    SuggestionChip(
                                                        onClick = {},
                                                        label = { Text(sk.scope, style = MaterialTheme.typography.labelSmall) },
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                }
                                            },
                                            supportingContent = {
                                                Text(
                                                    "${sk.content.length} caracteres · ${sk.content.take(80)}…",
                                                    maxLines = 1,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                            trailingContent = {
                                                TextButton(
                                                    onClick = { onDeleteSkill(sk.scope, sk.name) },
                                                    modifier = Modifier.semantics { contentDescription = "Eliminar skill ${sk.name}" }
                                                ) {
                                                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }

                            // Proyectos vinculados
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        "Proyectos vinculados",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            if (linkedProjects.isEmpty() && (project.linkedProjects ?: emptyList()).isEmpty()) {
                                item {
                                    Text("Sin proyectos vinculados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                val ids = project.linkedProjects ?: emptyList()
                                items(ids) { pid ->
                                    val lp = linkedProjects.find { it.id == pid }
                                    OutlinedCard(
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = lp?.name ?: pid }
                                    ) {
                                        ListItem(
                                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                            headlineContent = { Text(lp?.name ?: pid, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium)) },
                                            trailingContent = {
                                                TextButton(
                                                    onClick = { onUnlinkProject(pid) },
                                                    modifier = Modifier.semantics { contentDescription = "Quitar ${lp?.name ?: pid}" }
                                                ) {
                                                    Text("Quitar")
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            error?.let {
                Snackbar(
                    modifier = Modifier.padding(8.dp),
                    action = { TextButton(onClick = { onClearError(); onRefresh() }) { Text("Reintentar") } }
                ) {
                    Text(it.take(300))
                }
            }
        }
    }

    // Diálogo de edición de instrucciones de proyecto
    if (showInstructionsDialog) {
        var instructionsInput by remember(project.id) { mutableStateOf(project.description ?: "") }
        AlertDialog(
            onDismissRequest = { showInstructionsDialog = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Instrucciones del proyecto", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Personaliza el comportamiento, directrices y contexto para este proyecto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = instructionsInput,
                        onValueChange = { instructionsInput = it },
                        label = { Text("Instrucciones") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onPatchInstructions(instructionsInput.trim())
                    showInstructionsDialog = false
                }) {
                    Text("Guardar", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstructionsDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showSkillDialog) {
        var name by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        var scope by remember { mutableStateOf("project") }
        AlertDialog(
            onDismissRequest = { showSkillDialog = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Nueva habilidad o archivo", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = scope == "global", onClick = { scope = "global" }, label = { Text("global") }, shape = RoundedCornerShape(10.dp))
                        FilterChip(selected = scope == "project", onClick = { scope = "project" }, label = { Text("proyecto") }, shape = RoundedCornerShape(10.dp))
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("Contenido / Directrices") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(), minLines = 3)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        onCreateSkill(scope, name.trim(), content)
                        showSkillDialog = false
                    }
                }) {
                    Text("Crear", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { showSkillDialog = false }) { Text("Cancelar") } }
        )
    }
}
