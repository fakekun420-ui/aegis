package com.aegis.hub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.aegis.hub.ui.viewmodel.WorkflowViewModel
import com.aegis.hub.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowScreen(
    navController: NavController,
    projectId: String,
    viewModel: WorkflowViewModel = viewModel()
) {
    val workflows by viewModel.workflows.collectAsState()
    val status by viewModel.status.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()

    LaunchedEffect(projectId) {
        viewModel.loadWorkflows(projectId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$projectId WORKFLOWS", fontFamily = FontFamily.Monospace, color = ClaudeOnSurface) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ClaudeBackground)
            )
        },
        containerColor = ClaudeBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (isRunning || status != null) {
                WorkflowProgressPanel(status)
                Spacer(Modifier.height(16.dp))
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(workflows) { wf ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ClaudeSurface),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, ClaudeOutlineVariant, RoundedCornerShape(8.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(wf.name, color = ClaudeOnSurface, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("${wf.steps} steps", color = ClaudeOnSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                            Text(
                                "[EJECUTAR]",
                                color = ClaudePrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { viewModel.runWorkflow(projectId, wf.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WorkflowProgressPanel(status: com.aegis.hub.data.WorkflowStatus?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ClaudeSurfaceVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("CURRENT RUN: ${status?.id ?: "Unknown"}", color = ClaudeOnSurface, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            
            val stColor = when (status?.status) {
                "completed" -> Color(0xFF3FB950)
                "failed" -> ClaudeError
                "running" -> Color(0xFFD29922)
                else -> ClaudeOnSurfaceVariant
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Status: ", color = ClaudeOnSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Text(status?.status?.uppercase() ?: "PENDING", color = stColor, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text("Step: ${status?.currentStep ?: "-"}", color = ClaudeOnSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = (status?.progress ?: 0) / 100f,
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = ClaudePrimary,
                trackColor = ClaudeBackground
            )
        }
    }
}
