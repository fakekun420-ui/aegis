package com.opencode.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        if (query.isBlank()) sessions else sessions.filter {
            it.resolvedTitle.contains(query, ignoreCase = true) || it.resolvedId.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Volver" }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateChatPlaceholder,
                icon = { Icon(Icons.Filled.Add, contentDescription = "Nuevo chat") },
                text = { Text("+ Nuevo chat", fontWeight = FontWeight.Medium) },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.semantics { contentDescription = "Nuevo chat" }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            var isRefreshing by remember { mutableStateOf(false) }
            LaunchedEffect(isLoading) { if (!isLoading) isRefreshing = false }

            if (isLoading && sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                // Barra de búsqueda estilo píldora
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Buscar chats…") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = "Buscar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Limpiar búsqueda",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Buscar chats" },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { isRefreshing = true; onRefresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    Icons.Outlined.Inbox,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text("No tienes chats aún", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 72.dp)
                        ) {
                            items(filtered, key = { it.resolvedId.ifBlank { it.hashCode().toString() } }) { sess ->
                                val projName = sessionToProject[sess.resolvedId]

                                Box {
                                    OutlinedCard(
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .semantics(mergeDescendants = true) { contentDescription = sess.resolvedTitle }
                                            .combinedClickable(
                                                onClick = { onOpenSession(sess.resolvedId) },
                                                onLongClick = { menuTarget = sess }
                                            )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 13.dp),
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
                                                    sess.resolvedTitle,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                val timeStr = relativeTime(sess.lastActivityIso)
                                                Text(
                                                    " ◦ $timeStr",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                                if (!projName.isNullOrBlank()) {
                                                    Text(
                                                        " · $projName",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            ProviderBadge(sess.resolvedProvider)
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = menuTarget?.resolvedId == sess.resolvedId,
                                        onDismissRequest = { menuTarget = null },
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Renombrar") },
                                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
                                            onClick = { menuTarget = null; renameTarget = sess },
                                            modifier = Modifier.semantics { contentDescription = "Renombrar" }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Fijar") },
                                            leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
                                            onClick = { menuTarget = null; onPinSession(sess.resolvedId) },
                                            modifier = Modifier.semantics { contentDescription = "Fijar" }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Agregar a proyecto") },
                                            leadingIcon = { Icon(Icons.Outlined.DriveFileMove, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
                                            onClick = { menuTarget = null; moveTarget = sess },
                                            modifier = Modifier.semantics { contentDescription = "Agregar a proyecto" }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Eliminar", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) },
                                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                            onClick = { menuTarget = null; deleteTarget = sess },
                                            modifier = Modifier.semantics { contentDescription = "Eliminar" }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            error?.let {
                Snackbar(modifier = Modifier.padding(top = 8.dp), action = { TextButton(onClick = { onClearError(); onRefresh() }) { Text("Reintentar") } }) {
                    Text(it.take(300))
                }
            }
        }
    }

    renameTarget?.let { sess ->
        var name by remember(sess.resolvedId) { mutableStateOf(sess.resolvedTitle) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Renombrar chat", fontWeight = FontWeight.SemiBold) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { onRenameSession(sess.resolvedId, name.trim()); renameTarget = null } }) { Text("Guardar", fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancelar") } }
        )
    }

    deleteTarget?.let { sess ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Eliminar chat", fontWeight = FontWeight.SemiBold) },
            text = { Text("¿Eliminar \"${sess.resolvedTitle}\"? Esta acción no se puede deshacer.") },
            confirmButton = { TextButton(onClick = { onDeleteSession(sess.resolvedId); deleteTarget = null }) { Text("Eliminar", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } }
        )
    }

    moveTarget?.let { sess ->
        var selected by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Agregar a proyecto", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    projects.forEach { p ->
                        Surface(
                            onClick = { selected = p.id },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected == p.id) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selected == p.id, onClick = { selected = p.id })
                                Text(p.name, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (projects.isEmpty()) Text("Sin proyectos disponibles", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = selected != null, onClick = { selected?.let { onMoveSession(sess.resolvedId, it) }; moveTarget = null }) { Text("Mover", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { moveTarget = null }) { Text("Cancelar") } }
        )
    }
}
