package com.opencode.companion.ui

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
import com.opencode.companion.data.OpencodeSession
import com.opencode.companion.data.Project
import com.opencode.companion.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavScreen(
    projects: List<Project>,
    sessions: List<OpencodeSession>,
    draftVm: ChatViewModel,
    onNavigateProjects: () -> Unit,
    onNavigateChats: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenSession: (String) -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Opencode Companion",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Chats") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateChats()
                    },
                    icon = { Icon(Icons.Filled.ChatBubble, contentDescription = null) }
                )
                NavigationDrawerItem(
                    label = { Text("Proyectos") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onNavigateProjects()
                    },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) }
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
                            onClick = {
                                scope.launch {
                                    if (drawerState.isClosed) drawerState.open() else drawerState.close()
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = "Menú" }
                        ) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menú")
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                ChatScreen(
                    sessionId = "",
                    vm = draftVm,
                    onBack = { },
                    onVoice = { }
                )
            }
        }
    }
}
