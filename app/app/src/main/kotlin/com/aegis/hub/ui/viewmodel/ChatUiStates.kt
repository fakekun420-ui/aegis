package com.aegis.hub.ui.viewmodel

import com.aegis.hub.data.Agente
import com.aegis.hub.data.LiveToolExecution
import com.aegis.hub.data.Message
import com.aegis.hub.data.ModeloElegible
import com.aegis.hub.data.PendingForm
import com.aegis.hub.data.PendingPermission

/**
 * G4/F9: Agrupacion de StateFlows de ChatViewModel en UiStates cohesivos
 * para reducir recomposiciones y llamadas collectAsState en ChatScreen.
 */

data class ChatConversationUiState(
    val messages: List<Message> = emptyList(),
    val sessionTitle: String? = "Nuevo chat",
    val turnInProgress: Boolean = false,
    val turnFinished: Boolean = false
)

data class ChatDeliveryUiState(
    val loading: Boolean = false,
    val sendingInFlight: Boolean = false,
    val error: String? = null,
    val servidorAlcanzable: Boolean = true,
    val streamingText: String? = null,
    val streamingTools: List<LiveToolExecution> = emptyList()
)

data class ChatConfigUiState(
    val models: List<ModeloElegible> = emptyList(),
    val selectedModel: String? = null,
    val selectedProvider: String? = null,
    val modelsLoading: Boolean = false,
    val agentMode: String = "build",
    val agents: List<Agente> = emptyList(),
    val agentsLoading: Boolean = false
)

data class ChatInteractionUiState(
    val pendingForms: List<PendingForm> = emptyList(),
    val pendingPermissions: List<PendingPermission> = emptyList(),
    val replyingForm: Boolean = false,
    val replyingPermission: Boolean = false
)
