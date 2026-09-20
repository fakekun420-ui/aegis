package com.opencode.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    onUnlinkProject: (String) -> Unit
) {
    var composerText by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) } // 0 sessions, 1 skills/linked
    var showSkillDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column(modifier = Modifier.semantics { contentDescription = project.name }) { Text(project.name, maxLines = 1); Text(project.description ?: "—", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, maxLines = 1) } },
                navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Volver" }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Volver") } }
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = composerText, onValueChange = { composerText = it },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Mensaje para ${project.name}" },
                    placeholder = { Text("Mensaje para ${project.name}…") },
                    maxLines = 5
                )
                Button(onClick = {
                    val t = composerText.trim()
                    if (t.isNotBlank()) { onSendNewSession(t); composerText = "" }
                }, enabled = composerText.isNotBlank(), modifier = Modifier.semantics { contentDescription = "Enviar mensaje" }) { Text("Enviar") }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sesiones (${sessions.size})") }, modifier = Modifier.semantics { contentDescription = "Sesiones" })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Skills & Vínculos") }, modifier = Modifier.semantics { contentDescription = "Skills" })
            }
            if (isLoading && sessions.isEmpty() && skills.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                when (tab) {
                    0 -> {
                        if (sessions.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Sin sesiones vinculadas", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Escribe abajo para crear la primera", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(sessions, key = { it.sessionId }) { s ->
                                    Card(onClick = { onOpenSession(s.sessionId) }, modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = s.title ?: s.sessionId.take(8) }) {
                                        ListItem(
                                            headlineContent = {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Text(s.title ?: s.sessionId.take(8), modifier = Modifier.weight(1f, fill = false))
                                                    ProviderBadge(s.resolvedProvider(project.provider))
                                                }
                                            },
                                            supportingContent = { Text(s.lastUsed ?: s.createdAt ?: "", maxLines = 1) },
                                            leadingContent = { Icon(Icons.Filled.ChatBubble, contentDescription = "Sesión") }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Skills", style = MaterialTheme.typography.titleSmall)
                                    TextButton(onClick = { showSkillDialog = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Añadir") }
                                }
                            }
                            if (skills.isEmpty()) item { Text("Sin skills", style = MaterialTheme.typography.bodySmall) }
                            else items(skills, key = { "${it.scope}:${it.name}" }) { sk ->
                                Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = sk.name }) {
                                    ListItem(
                                        headlineContent = { Text("${sk.name} [${sk.scope}]") },
                                        supportingContent = { Text(sk.content.take(120), maxLines = 2) },
                                        trailingContent = { TextButton(onClick = { onDeleteSkill(sk.scope, sk.name) }, modifier = Modifier.semantics { contentDescription = "Eliminar skill ${sk.name}" }) { Text("Eliminar") } }
                                    )
                                }
                            }
                            item { Divider() }
                            item { Text("Proyectos vinculados", style = MaterialTheme.typography.titleSmall) }
                            if (linkedProjects.isEmpty() && (project.linkedProjects ?: emptyList()).isEmpty()) {
                                item { Text("Sin vínculos", style = MaterialTheme.typography.bodySmall) }
                            } else {
                                val ids = project.linkedProjects ?: emptyList()
                                items(ids) { pid ->
                                    val lp = linkedProjects.find { it.id == pid }
                                    Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = lp?.name ?: pid }) {
                                        ListItem(
                                            headlineContent = { Text(lp?.name ?: pid) },
                                            trailingContent = { TextButton(onClick = { onUnlinkProject(pid) }, modifier = Modifier.semantics { contentDescription = "Quitar ${lp?.name ?: pid}" }) { Text("Quitar") } }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            error?.let {
                Snackbar(modifier = Modifier.padding(8.dp), action = { TextButton(onClick = { onClearError(); onRefresh() }) { Text("Reintentar") } }) { Text(it.take(300)) }
            }
        }
    }

    if (showSkillDialog) {
        var name by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        var scope by remember { mutableStateOf("project") }
        AlertDialog(
            onDismissRequest = { showSkillDialog = false },
            title = { Text("Nueva skill") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = scope == "global", onClick = { scope = "global" }, label = { Text("global") })
                        FilterChip(selected = scope == "project", onClick = { scope = "project" }, label = { Text("proyecto") })
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("Contenido") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) { onCreateSkill(scope, name.trim(), content); showSkillDialog = false }
                }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { showSkillDialog = false }) { Text("Cancelar") } }
        )
    }
}
