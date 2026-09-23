package com.aegis.hub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.aegis.hub.ui.viewmodel.SkillManagerViewModel
import com.aegis.hub.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagerScreen(
    navController: NavController,
    viewModel: SkillManagerViewModel = viewModel()
) {
    val skillsData by viewModel.skills.collectAsState()
    val installingId by viewModel.installingId.collectAsState()
    val installLog by viewModel.installLog.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadSkills()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("SKILL MANAGER", fontFamily = FontFamily.Monospace, color = ClaudeOnSurface) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = ClaudeBackground),
                    actions = {
                        IconButton(onClick = { viewModel.loadSkills() }) {
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
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                skillsData?.installed?.let { installed ->
                    item {
                        Text("INSTALLED SKILLS", color = ClaudeOnSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    items(installed) { skill ->
                        SkillCard(
                            name = skill.name,
                            version = skill.version,
                            desc = skill.description,
                            actionLabel = "[UNINSTALL]",
                            actionColor = ClaudeError,
                            onAction = { viewModel.uninstallSkill(skill.id) }
                        )
                    }
                }

                skillsData?.available?.let { available ->
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("AVAILABLE SKILLS", color = ClaudeOnSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    items(available) { skill ->
                        SkillCard(
                            name = skill.name,
                            version = skill.version,
                            desc = skill.description,
                            actionLabel = "[INSTALL]",
                            actionColor = ClaudePrimary,
                            onAction = { viewModel.installSkill(skill.id) }
                        )
                    }
                }
            }
        }

        if (installingId != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ClaudeSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Text("Installing $installingId...", color = ClaudeOnSurface, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(16.dp))
                        installLog.forEach { line ->
                            Text(line, color = ClaudeOnSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SkillCard(
    name: String,
    version: String?,
    desc: String?,
    actionLabel: String,
    actionColor: Color,
    onAction: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ClaudeSurface),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, ClaudeOutlineVariant, RoundedCornerShape(8.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = ClaudeOnSurface, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (version != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(version, color = ClaudeOnSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (desc != null) {
                Text(desc, color = ClaudeOnSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(24.dp)
            ) {
                Text(actionLabel, color = actionColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}
