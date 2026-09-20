package com.opencode.companion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opencode.companion.data.OpencodeSession
import com.opencode.companion.data.Project
import com.opencode.companion.util.relativeTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    sessions: List<OpencodeSession>,
    projects: List<Project>,
    isLoading: Boolean = false,
    error: String? = null,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateChatPlaceholder: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String) -> Unit,
    onMoveSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRefresh: () -> Unit = {},
    onClearError: () -> Unit = {}
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
        topBar = { TopAppBar(title = { Text("Chats") }, navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Volver" }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Volver") } }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onCreateChatPlaceholder, icon = { Icon(Icons.Filled.Add, contentDescription = "Nuevo chat") }, text = { Text("+ Nuevo chat") }, modifier = Modifier.semantics { contentDescription = "Nuevo chat" }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var isRefreshing by remember { mutableStateOf(false) }
            LaunchedEffect(isLoading) { if (!isLoading) isRefreshing = false }
            if (isLoading && sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Buscar chats") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Buscar chats" }, singleLine = true)
                PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { isRefreshing = true; onRefresh() }, modifier = Modifier.fillMaxSize()) {
                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Inbox, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("No tienes chats aún", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filtered, key = { it.resolvedId.ifBlank { it.hashCode().toString() } }) { sess ->
                                val projName = sessionToProject[sess.resolvedId]
                                Card(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = sess.resolvedTitle }.combinedClickable(onClick = { onOpenSession(sess.resolvedId) }, onLongClick = { menuTarget = sess })) {
                                    ListItem(
                                        headlineContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(sess.resolvedTitle, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                                ProviderBadge(sess.resolvedProvider)
                                            }
                                        },
                                        supportingContent = { Text(listOfNotNull(relativeTime(sess.lastActivityIso), projName).joinToString(" · "), maxLines = 1) },
                                        leadingContent = { Icon(Icons.Filled.ChatBubble, contentDescription = "Chat") }
                                    )
                                }
                                DropdownMenu(expanded = menuTarget?.resolvedId == sess.resolvedId, onDismissRequest = { menuTarget = null }) {
                                    DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menuTarget = null; renameTarget = sess }, modifier = Modifier.semantics { contentDescription = "Renombrar" })
                                    DropdownMenuItem(text = { Text("Fijar") }, onClick = { menuTarget = null; onPinSession(sess.resolvedId) }, modifier = Modifier.semantics { contentDescription = "Fijar" })
                                    DropdownMenuItem(text = { Text("Agregar a proyecto") }, onClick = { menuTarget = null; moveTarget = sess }, modifier = Modifier.semantics { contentDescription = "Agregar a proyecto" })
                                    DropdownMenuItem(text = { Text("Eliminar") }, onClick = { menuTarget = null; deleteTarget = sess }, modifier = Modifier.semantics { contentDescription = "Eliminar" })
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
            title = { Text("Agregar a proyecto") },
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
