package com.opencode.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPlaceholderScreen(sessionId: String, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Chat") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(24.dp)) {
                Text("Vista de mensajes — próxima fase", style = MaterialTheme.typography.titleMedium)
                Text("sessionId: $sessionId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("La vista de chat nativa (mensajes + composer) se migrará en Phase 2. Por ahora el backend sigue en WebView / 8765.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
