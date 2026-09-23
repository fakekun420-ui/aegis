package com.aegis.hub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aegis.hub.data.OpencodeSession
import com.aegis.hub.data.Project
import com.aegis.hub.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavScreen(
    projects: List<Project>,
    sessions: List<OpencodeSession>,
    draftVm: ChatViewModel,
    onNavigateProjects: () -> Unit,
    onNavigateChats: () -> Unit,
    onNavigateControlCenter: () -> Unit,
    onNavigateSkillManager: () -> Unit,
    onNavigateWorkspace: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenSession: (String) -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            ) {
                Spacer(Modifier.height(36.dp))
                Text(
                    "Aegis",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Chats", fontWeight = FontWeight.Medium) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateChats()
                    },
                    icon = { Icon(Icons.Filled.ChatBubble, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Proyectos", fontWeight = FontWeight.Medium) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateProjects()
                    },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Control Center", fontWeight = FontWeight.Medium) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateControlCenter()
                    },
                    icon = { Text("🏠") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Workspace (Hub)", fontWeight = FontWeight.Medium) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateWorkspace()
                    },
                    icon = { Text("📁") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Skills Manager", fontWeight = FontWeight.Medium) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateSkillManager()
                    },
                    icon = { Text("🔧") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Aegis", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    if (drawerState.isClosed) drawerState.open() else drawerState.close()
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = "Menú" }
                        ) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menú")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                ChatScreen(
                    sessionId = "",
                    vm = draftVm,
                    onBack = { },
                    onVoice = { },
                    showTopBar = false
                )
            }
        }
    }
}
