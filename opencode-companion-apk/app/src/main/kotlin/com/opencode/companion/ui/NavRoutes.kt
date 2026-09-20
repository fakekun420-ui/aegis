package com.opencode.companion.ui

object NavRoutes {
    const val MAIN = "main"
    const val DRAFT_CHAT = "draft-chat"
    const val PROJECTS = "projects"
    const val CHATS = "chats"
    const val CHAT_PLACEHOLDER = "chat/{sessionId}"
    const val VOICE = "voice/{sessionId}"
    fun chat(sessionId: String) = "chat/$sessionId"
    fun voice(sessionId: String) = "voice/$sessionId"
}
