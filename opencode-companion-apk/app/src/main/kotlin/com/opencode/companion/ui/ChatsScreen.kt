package com.opencode.companion.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opencode.companion.data.OpencodeSession
import com.opencode.companion.data.Project
import com.opencode.companion.util.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    sessions: List<OpencodeSession>,
    projects: List<Project>,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateChatPlaceholder: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String) -> Unit,
    onMoveSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var menuTarget by remember { mutableStateOf<OpencodeSession?>(null) }
    var renameTarget by remember { mutableStateOf<OpencodeSession?>(null) }
    var deleteTarget by remember { mutableStateOf<OpencodeSession?>(null) }
    var moveTarget by remember { mutableStateOf<OpencodeSession?>(null) }

    // Build sessionId -> projectName map
    val sessionToProject = remember(projects) {
        val m = mutableMapOf<String, String>()
        for (p in projects) for (s in (p.sessions ?: emptyList())) m[s.sessionId] = p.name
        m
    }

    val filtered = remember(sessions, query) {
        if (query.isBlank()) sessions else sessions.filter { it.resolvedTitle.contains(query, ignoreCase = true) || it.resolvedId.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Chats") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onCreateChatPlaceholder, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("+ Nuevo chat") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Buscar chats") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.resolvedId.ifBlank { it.hashCode().toString() } }) { sess ->
                    val projName = sessionToProject[sess.resolvedId]
                    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenSession(sess.resolvedId) }, onLongClick = { menuTarget = sess })) {
                        ListItem(
                            headlineContent = { Text(sess.resolvedTitle, maxLines = 1) },
                            supportingContent = { Text(listOfNotNull(relativeTime(sess.lastActivityIso), projName).joinToString(" · "), maxLines = 1) },
                            leadingContent = { Icon(Icons.Filled.ChatBubble, null) }
                        )
                    }
                    DropdownMenu(expanded = menuTarget?.resolvedId == sess.resolvedId, onDismissRequest = { menuTarget = null }) {
                        DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menuTarget = null; renameTarget = sess })
                        DropdownMenuItem(text = { Text("Fijar") }, onClick = { menuTarget = null; onPinSession(sess.resolvedId) })
                        DropdownMenuItem(text = { Text("Cambiar proyecto") }, onClick = { menuTarget = null; moveTarget = sess })
                        DropdownMenuItem(text = { Text("Eliminar") }, onClick = { menuTarget = null; deleteTarget = sess })
                    }
                }
                if (filtered.isEmpty()) { item { Text("Sin chats", style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }

    renameTarget?.let { sess ->
        var name by remember(sess.resolvedId) { mutableStateOf(sess.resolvedTitle) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Renombrar chat") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { onRenameSession(sess.resolvedId, name.trim()); renameTarget = null } }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancelar") } }
        )
    }
    deleteTarget?.let { sess ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Eliminar chat") },
            text = { Text("¿Eliminar \"${sess.resolvedTitle}\"? Esta acción no se puede deshacer.") },
            confirmButton = { TextButton(onClick = { onDeleteSession(sess.resolvedId); deleteTarget = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } }
        )
    }
    moveTarget?.let { sess ->
        var selected by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("Cambiar proyecto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    projects.forEach { p ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = selected == p.id, onClick = { selected = p.id })
                            Text(p.name, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                        }
                    }
                    if (projects.isEmpty()) Text("Sin proyectos", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = selected != null, onClick = { selected?.let { onMoveSession(sess.resolvedId, it) }; moveTarget = null }) { Text("Mover") }
            },
            dismissButton = { TextButton(onClick = { moveTarget = null }) { Text("Cancelar") } }
        )
    }
}
