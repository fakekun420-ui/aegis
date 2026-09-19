package com.opencode.companion.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opencode.companion.data.Project
import com.opencode.companion.util.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    projects: List<Project>,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onCreateProject: (String, String) -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDeleteProject: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var menuTarget by remember { mutableStateOf<Project?>(null) }

    val filtered = remember(projects, query) {
        if (query.isBlank()) projects else projects.filter { it.name.contains(query, ignoreCase = true) || (it.description ?: "").contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Proyectos") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showCreate = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("+ Nuevo proyecto") })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Buscar") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.id }) { proj ->
                    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenProject(proj.id) }, onLongClick = { menuTarget = proj })) {
                        ListItem(
                            headlineContent = { Text(proj.name) },
                            supportingContent = { Text("${proj.description ?: "—"} · ${relativeTime(proj.createdAt)}", maxLines = 1) },
                            leadingContent = { Icon(Icons.Filled.Folder, null) }
                        )
                    }
                    DropdownMenu(expanded = menuTarget?.id == proj.id, onDismissRequest = { menuTarget = null }) {
                        DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menuTarget = null; renameTarget = proj })
                        DropdownMenuItem(text = { Text("Eliminar") }, onClick = { menuTarget = null; deleteTarget = proj })
                    }
                }
                if (filtered.isEmpty()) { item { Text("Sin proyectos", style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Nuevo proyecto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Descripción (opcional)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { if (name.isNotBlank()) { onCreateProject(name.trim(), desc.trim()); showCreate = false } }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancelar") } }
        )
    }
    renameTarget?.let { proj ->
        var name by remember(proj.id) { mutableStateOf(proj.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Renombrar proyecto") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { onRenameProject(proj.id, name.trim()); renameTarget = null } }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancelar") } }
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
