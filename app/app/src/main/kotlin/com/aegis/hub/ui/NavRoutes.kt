package com.aegis.hub.ui

object NavRoutes {
    const val SETUP = "setup"
    const val DRAFT_CHAT = "draft-chat"
    const val PROJECTS = "projects"
    const val CHATS = "chats"
    const val CHAT_PLACEHOLDER = "chat/{sessionId}"
    fun chat(sessionId: String) = "chat/$sessionId"
}
