package com.opencode.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.opencode.companion.data.OpencodeSession
import com.opencode.companion.data.Project
import com.opencode.companion.util.pickEpochMillis
import com.opencode.companion.util.relativeTime

data class RecentItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val epochMillis: Long?,
    val kind: String // "chat" | "project"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavScreen(
    projects: List<Project>,
    sessions: List<OpencodeSession>,
    onNavigateProjects: () -> Unit,
    onNavigateChats: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenSession: (String) -> Unit
) {
    val recents = remember(projects, sessions) { buildRecents(projects, sessions) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(24.dp))
                Text("Opencode Companion", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Inicio") },
                    selected = false,
                    onClick = { /* already on main */ },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) }
                )
                NavigationDrawerItem(
                    label = { Text("Proyectos") },
                    selected = false,
                    onClick = { onNavigateProjects() },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) }
                )
                NavigationDrawerItem(
                    label = { Text("Chats") },
                    selected = false,
                    onClick = { onNavigateChats() },
                    icon = { Icon(Icons.Filled.ChatBubble, contentDescription = null) }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Opencode Companion") },
                    navigationIcon = {
                        IconButton(
                            onClick = { kotlinx.coroutines.MainScope().launch { if (drawerState.isClosed) drawerState.open() else drawerState.close() } },
                            modifier = Modifier.semantics { contentDescription = "Menú" }
                        ) { Icon(Icons.Filled.Menu, contentDescription = "Menú") }
                    }
                )
            }
        ) { padding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item { SectionHeader("Navegación") }
            item {
                Card(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                    Column {
                        ListItem(
                            headlineContent = { Text("Proyectos") },
                            supportingContent = { Text("${projects.size} proyectos") },
                            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = "Proyectos") },
                            modifier = Modifier
                                .semantics { contentDescription = "Proyectos" }
                                .clickable { onNavigateProjects() }
                        )
                        Divider()
                        ListItem(
                            headlineContent = { Text("Chats") },
                            supportingContent = { Text("${sessions.size} chats") },
                            leadingContent = { Icon(Icons.Filled.ChatBubble, contentDescription = "Chats") },
                            modifier = Modifier
                                .semantics { contentDescription = "Chats" }
                                .clickable { onNavigateChats() }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(4.dp)); SectionHeader("Recientes") }
            if (recents.isEmpty()) {
                item { Text("Sin actividad reciente", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(recents.take(12)) { item ->
                    ListItem(
                        headlineContent = { Text(item.title, maxLines = 1) },
                        supportingContent = { Text(item.subtitle, maxLines = 1) },
                        leadingContent = { Icon(if (item.kind == "chat") Icons.Filled.ChatBubble else Icons.Filled.Folder, contentDescription = if (item.kind == "chat") "Chat" else "Proyecto") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(14.dp))
                                Text(relativeTime(item.epochMillis ?: 0L), style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = item.title }.clickable {
                            if (item.kind == "chat") onOpenSession(item.id) else onOpenProject(item.id)
                        }
                    )
                    Divider()
                }
            }
        }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun buildRecents(projects: List<Project>, sessions: List<OpencodeSession>): List<RecentItem> {
    val out = mutableListOf<RecentItem>()
    for (p in projects) {
        val epoch = pickEpochMillis(p.createdAt, p.archivedAt)
        out += RecentItem(id = p.id, title = p.name, subtitle = p.description ?: "Proyecto", epochMillis = epoch, kind = "project")
    }
    for (s in sessions) {
        val id = s.resolvedId
        if (id.isBlank()) continue
        val epoch = pickEpochMillis(s.lastActivityIso)
        val projHint = ""
        out += RecentItem(id = id, title = s.resolvedTitle, subtitle = "Chat${if (projHint.isNotBlank()) " · $projHint" else ""}", epochMillis = epoch, kind = "chat")
    }
    return out.sortedByDescending { it.epochMillis ?: 0L }
}
