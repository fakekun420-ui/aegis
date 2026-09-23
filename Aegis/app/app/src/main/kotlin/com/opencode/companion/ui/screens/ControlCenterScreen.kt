package com.opencode.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.opencode.companion.ui.viewmodel.ControlCenterViewModel
import com.opencode.companion.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlCenterScreen(
    navController: NavController,
    viewModel: ControlCenterViewModel = viewModel()
) {
    val health by viewModel.health.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startAutoRefresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AEGIS CONTROL CENTER", fontFamily = FontFamily.Monospace, color = ClaudeOnSurface) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ClaudeBackground),
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = ClaudeOnSurface)
                    }
                }
            )
        },
        containerColor = ClaudeBackground
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (error != null) {
                item {
                    Text("Error: $error", color = ClaudeError, fontFamily = FontFamily.Monospace)
                }
            }
            
            health?.let { h ->
                item {
                    StatusCard(h)
                }
                item {
                    MetricGrid(h)
                }
                item {
                    Text("SKILLS", fontFamily = FontFamily.Monospace, color = ClaudeOnSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    SkillsList(h.skills?.installed ?: emptyList())
                }
                item {
                    Text("ADAPTERS", fontFamily = FontFamily.Monospace, color = ClaudeOnSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    AdaptersStatus(h.adapters ?: emptyMap())
                }
            } ?: item {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ClaudePrimary)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(health: com.opencode.companion.data.HealthData) {
    val isOnline = health.server == "running" || health.server == "healthy" || health.server == "ok"
    val statusColor = if (isOnline) Color(0xFF3FB950) else ClaudeError
    
    Card(
        colors = CardDefaults.cardColors(containerColor = ClaudeSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(statusColor, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = if (isOnline) "ONLINE" else "OFFLINE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
                Text(
                    text = "Uptime: ${health.uptime ?: 0}s",
                    fontFamily = FontFamily.Monospace,
                    color = ClaudeOnSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun MetricGrid(health: com.opencode.companion.data.HealthData) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        MetricCard(
            title = "MEMORY",
            value = "${health.memory?.heapUsed ?: "?"} / ${health.memory?.heapTotal ?: "?"}",
            modifier = Modifier.weight(1f)
        )
        MetricCard(
            title = "PROJECTS",
            value = "${health.projects ?: 0} workspace",
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        MetricCard(
            title = "AGENTS",
            value = "${health.agents?.active ?: 0} active / ${health.agents?.registered ?: 0} reg",
            modifier = Modifier.weight(1f)
        )
        MetricCard(
            title = "JOBS",
            value = "${health.jobs?.active ?: 0} active",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ClaudeSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontFamily = FontFamily.Monospace, color = ClaudeOnSurfaceVariant, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, fontFamily = FontFamily.Monospace, color = ClaudeOnSurface, fontSize = 14.sp)
        }
    }
}

@Composable
fun SkillsList(skills: List<String>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(skills) { skill ->
            Box(
                modifier = Modifier
                    .border(1.dp, ClaudeOutline, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(skill, fontFamily = FontFamily.Monospace, color = ClaudeOnSurface, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun AdaptersStatus(adapters: Map<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        adapters.forEach { (name, status) ->
            val isOk = status == "healthy" || status == "ok"
            val color = if (isOk) Color(0xFF3FB950) else ClaudeError
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("$name: $status", fontFamily = FontFamily.Monospace, color = ClaudeOnSurface, fontSize = 12.sp)
            }
        }
    }
}
