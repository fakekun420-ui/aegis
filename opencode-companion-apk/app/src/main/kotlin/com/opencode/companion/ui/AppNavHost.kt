package com.opencode.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opencode.companion.ui.viewmodel.ChatViewModel
import com.opencode.companion.ui.viewmodel.MainViewModel

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val vm: MainViewModel = viewModel()

    // Poll system ready was already handled by MainActivity overlay; Compose just shows nav.
    // Collect state
    val projects by vm.projects.collectAsState()
    val sessions by vm.sessions.collectAsState()

    NavHost(navController = navController, startDestination = NavRoutes.MAIN) {
        composable(NavRoutes.MAIN) {
            MainNavScreen(
                projects = projects,
                sessions = sessions,
                onNavigateProjects = { navController.navigate(NavRoutes.PROJECTS) },
                onNavigateChats = { navController.navigate(NavRoutes.CHATS) },
                onOpenProject = { id -> navController.navigate(NavRoutes.PROJECTS) },
                onOpenSession = { id -> navController.navigate(NavRoutes.chat(id)) }
            )
        }
        composable(NavRoutes.PROJECTS) {
            ProjectsScreen(
                projects = projects,
                isLoading = vm.loadingProjects.collectAsState().value,
                error = vm.error.collectAsState().value,
                onBack = { navController.popBackStack() },
                onOpenProject = { id -> navController.navigate("project/$id") },
                onCreateProject = { name, desc -> vm.createProject(name, desc) },
                onRenameProject = { id, name -> vm.renameProject(id, name) },
                onPatchProject = { id, name, desc -> vm.patchProject(id, name, desc) },
                onArchiveProject = { id -> vm.archiveProject(id) },
                onDeleteProject = { id -> vm.deleteProject(id) },
                onRefresh = { vm.refreshProjects() },
                onClearError = { vm.clearError() }
            )
        }
        composable(
            route = "project/{projectId}",
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { backStack ->
            val pid = backStack.arguments?.getString("projectId") ?: return@composable
            val detailVm: com.opencode.companion.ui.viewmodel.ProjectDetailViewModel = viewModel(key = "project_$pid")
            val project by detailVm.project.collectAsState()
            val detailSessions by detailVm.sessions.collectAsState()
            val skills by detailVm.skills.collectAsState()
            val linked by detailVm.linkedProjects.collectAsState()
            val loading by detailVm.loading.collectAsState()
            val err by detailVm.error.collectAsState()
            LaunchedEffect(pid) { detailVm.load(pid) }
            val p = project
            if (p != null) {
                ProjectDetailScreen(
                    project = p,
                    sessions = detailSessions,
                    skills = skills,
                    linkedProjects = linked,
                    isLoading = loading,
                    error = err,
                    onBack = { navController.popBackStack() },
                    onOpenSession = { sid -> navController.navigate(NavRoutes.chat(sid)) },
                    onSendNewSession = { text -> detailVm.sendNewSession(pid, text) { sid -> navController.navigate(NavRoutes.chat(sid)) } },
                    onRefresh = { detailVm.load(pid) },
                    onClearError = { detailVm.clearError() },
                    onCreateSkill = { scope, name, content -> detailVm.createSkill(scope, name, content) },
                    onDeleteSkill = { scope, name -> detailVm.deleteSkill(scope, name) },
                    onLinkProject = { target -> detailVm.linkProject(target) },
                    onUnlinkProject = { target -> detailVm.unlinkProject(target) }
                )
            } else {
                // Still loading project
                androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    if (loading) androidx.compose.material3.CircularProgressIndicator() else androidx.compose.material3.Text("Proyecto no encontrado")
                }
            }
        }
        composable(NavRoutes.CHATS) {
            ChatsScreen(
                sessions = sessions,
                projects = projects,
                isLoading = vm.loadingSessions.collectAsState().value,
                error = vm.error.collectAsState().value,
                onBack = { navController.popBackStack() },
                onOpenSession = { id -> navController.navigate(NavRoutes.chat(id)) },
                onCreateChatPlaceholder = { /* TODO: create new chat */ },
                onRenameSession = { _, _ -> },
                onPinSession = { _ -> },
                onMoveSession = { sessionId, projectId -> vm.moveSession(sessionId, projectId) },
                onDeleteSession = { _ -> /* needs DELETE /opencode/session/:id when spec added */ },
                onRefresh = { vm.refreshSessions() },
                onClearError = { vm.clearError() }
            )
        }
        composable(NavRoutes.CHAT_PLACEHOLDER, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { backStack ->
            val sid = backStack.arguments?.getString("sessionId") ?: ""
            val chatVm: ChatViewModel = viewModel(key = "chat_$sid")
            ChatScreen(sessionId = sid, vm = chatVm, onBack = { navController.popBackStack() }, onVoice = { navController.navigate(NavRoutes.voice(sid)) })
        }
        composable(NavRoutes.VOICE, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { backStack ->
            val sid = backStack.arguments?.getString("sessionId") ?: ""
            val chatVm: ChatViewModel = viewModel(key = "chat_$sid")
            VoiceConversationScreen(sessionId = sid, vm = chatVm, onBack = { navController.popBackStack() })
        }
    }
}
