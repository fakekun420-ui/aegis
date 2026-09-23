package com.aegis.hub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.aegis.hub.ui.NavRoutes
import com.aegis.hub.ui.viewmodel.WorkspaceViewModel
import com.aegis.hub.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    navController: NavController,
    viewModel: WorkspaceViewModel = viewModel()
) {
    val projects by viewModel.projects.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadProjects()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PROJECTS", fontFamily = FontFamily.Monospace, color = ClaudeOnSurface) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ClaudeBackground),
                actions = {
                    IconButton(onClick = { viewModel.loadProjects() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = ClaudeOnSurface)
                    }
                }
            )
        },
        containerColor = ClaudeBackground
    ) { padding ->
        if (projects.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No se encontraron proyectos en /sdcard/projects/", color = ClaudeOnSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects) { p ->
                    ProjectCard(
                        project = p,
                        onOpen = { navController.navigate("project/${p.id}") },
                        onInitHub = { viewModel.initProject(p.id) },
                        onIndex = { viewModel.indexProject(p.id) },
                        onWorkflows = { navController.navigate("workflow/${p.id}") }
                    )
                }
            }
        }
    }
}

@Composable
fun ProjectCard(
    project: com.aegis.hub.data.ProjectItem,
    onOpen: () -> Unit,
    onInitHub: () -> Unit,
    onIndex: () -> Unit,
    onWorkflows: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ClaudeSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, ClaudeOutlineVariant, RoundedCornerShape(8.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(project.name, color = ClaudeOnSurface, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.width(12.dp))
                if (project.hasHub) {
                    BadgeText("[HUB]", Color(0xFF3FB950))
                } else {
                    BadgeText("[NO HUB]", ClaudeOnSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(project.path ?: "", color = ClaudeOnSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (project.lastCommit != null) {
                Spacer(Modifier.height(4.dp))
                Text("Commit: ${project.lastCommit}", color = ClaudeOnSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionButton("[ABRIR]", ClaudePrimary, onOpen)
                if (!project.hasHub) {
                    ActionButton("[INIT HUB]", ClaudeOnSurface, onInitHub)
                } else {
                    ActionButton("[INDEXAR]", ClaudeTertiary, onIndex)
                    ActionButton("[WORKFLOWS]", ClaudeSecondary, onWorkflows)
                }
            }
        }
    }
}

@Composable
fun BadgeText(text: String, color: Color) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun ActionButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(onClick = onClick)
    )
}
