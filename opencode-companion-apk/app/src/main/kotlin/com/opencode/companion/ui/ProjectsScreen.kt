package com.opencode.companion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.opencode.companion.data.Project
import com.opencode.companion.util.relativeTime

@Composable
fun ProviderBadge(provider: String, modifier: Modifier = Modifier) {
    val isAgy = provider.equals("antigravity", ignoreCase = true)
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (isAgy) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
    ) {
        Text(
            text = if (isAgy) "Antigravity" else "OpenCode",
            style = MaterialTheme.typography.labelSmall,
            color = if (isAgy) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectsScreen(
    projects: List<Project>,
    isLoading: Boolean = false,
    error: String? = null,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onCreateProject: (String, String, String) -> Unit = { _, _, _ -> },
    onRenameProject: (String, String) -> Unit,
    onPatchProject: (String, String?, String?) -> Unit = { _, _, _ -> },
    onArchiveProject: (String) -> Unit = {},
    onDeleteProject: (String) -> Unit,
    onRefresh: () -> Unit = {},
    onClearError: () -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var editTarget by remember { mutableStateOf<Project?>(null) }
    var archiveTarget by remember { mutableStateOf<Project?>(null) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var menuTarget by remember { mutableStateOf<Project?>(null) }
    var pinnedIds by remember { mutableStateOf(setOf<String>()) }

    val filtered = remember(projects, query) {
        if (query.isBlank()) projects else projects.filter { it.name.contains(query, ignoreCase = true) || (it.description ?: "").contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Proyectos") }, navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Volver" }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Volver") } })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = "Nuevo proyecto") },
                text = { Text("+ Nuevo proyecto") },
                modifier = Modifier.semantics { contentDescription = "Nuevo proyecto" }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var isRefreshing by remember { mutableStateOf(false) }
            LaunchedEffect(isLoading) { if (!isLoading) isRefreshing = false }
            if (isLoading && projects.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Buscar") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Buscar proyectos" }, singleLine = true)
                PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { isRefreshing = true; onRefresh() }, modifier = Modifier.fillMaxSize()) {
                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Inbox, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("No tienes proyectos aún — crea el primero", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filtered, key = { it.id }) { proj ->
                                Card(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = proj.name }.combinedClickable(onClick = { onOpenProject(proj.id) }, onLongClick = { menuTarget = proj })) {
                                    ListItem(
                                        headlineContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(proj.name, modifier = Modifier.weight(1f, fill = false))
                                                ProviderBadge(proj.resolvedProvider)
                                            }
                                        },
                                        supportingContent = { Text("${proj.description ?: "—"} · ${relativeTime(proj.createdAt)}", maxLines = 1) },
                                        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = "Proyecto") }
                                    )
                                }
                                DropdownMenu(expanded = menuTarget?.id == proj.id, onDismissRequest = { menuTarget = null }) {
                                    DropdownMenuItem(
                                        text = { Text(if (pinnedIds.contains(proj.id)) "Desfijar" else "Fijar") },
                                        onClick = { menuTarget = null; pinnedIds = if (pinnedIds.contains(proj.id)) pinnedIds - proj.id else pinnedIds + proj.id },
                                        modifier = Modifier.semantics { contentDescription = "Fijar" }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Editar detalles") },
                                        onClick = { menuTarget = null; editTarget = proj },
                                        modifier = Modifier.semantics { contentDescription = "Editar detalles" }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Archivar") },
                                        onClick = { menuTarget = null; archiveTarget = proj },
                                        modifier = Modifier.semantics { contentDescription = "Archivar" }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Eliminar") },
                                        onClick = { menuTarget = null; deleteTarget = proj },
                                        modifier = Modifier.semantics { contentDescription = "Eliminar" }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            error?.let {
                Snackbar(modifier = Modifier.padding(top = 8.dp), action = { TextButton(onClick = { onClearError(); onRefresh() }) { Text("Reintentar") } }) { Text(it.take(300)) }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var selectedProvider by remember { mutableStateOf("opencode") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Nuevo proyecto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Descripción (opcional)") }, modifier = Modifier.fillMaxWidth())
                    Text("Proveedor / Agente:", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = selectedProvider == "opencode",
                            onClick = { selectedProvider = "opencode" },
                            label = { Text("OpenCode") }
                        )
                        FilterChip(
                            selected = selectedProvider == "antigravity",
                            onClick = { selectedProvider = "antigravity" },
                            label = { Text("Antigravity") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (name.isNotBlank()) { onCreateProject(name.trim(), desc.trim(), selectedProvider); showCreate = false } }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancelar") } }
        )
    }
    editTarget?.let { proj ->
        var name by remember(proj.id) { mutableStateOf(proj.name) }
        var desc by remember(proj.id) { mutableStateOf(proj.description ?: "") }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Editar detalles") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { onPatchProject(proj.id, name.trim(), desc.trim().ifBlank { null }); editTarget = null } }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancelar") } }
        )
    }
    archiveTarget?.let { proj ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text("Archivar proyecto") },
            text = { Text("¿Archivar \"${proj.name}\"? Podrás verlo en la sección de archivados.") },
            confirmButton = { TextButton(onClick = { onArchiveProject(proj.id); archiveTarget = null }) { Text("Archivar") } },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("Cancelar") } }
        )
    }
    deleteTarget?.let { proj ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Eliminar proyecto") },
            text = { Text("¿Eliminar \"${proj.name}\"? Se archivará.") },
            confirmButton = { TextButton(onClick = { onDeleteProject(proj.id); deleteTarget = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } }
        )
    }
}
