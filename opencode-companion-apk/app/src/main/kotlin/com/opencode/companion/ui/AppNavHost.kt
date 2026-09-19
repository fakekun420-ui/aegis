package com.opencode.companion.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
                onBack = { navController.popBackStack() },
                onOpenProject = { /* already on projects; could show detail later */ },
                onCreateProject = { name, desc -> vm.createProject(name, desc) },
                onRenameProject = { id, name -> vm.renameProject(id, name) },
                onDeleteProject = { id -> vm.deleteProject(id) }
            )
        }
        composable(NavRoutes.CHATS) {
            ChatsScreen(
                sessions = sessions,
                projects = projects,
                onBack = { navController.popBackStack() },
                onOpenSession = { id -> navController.navigate(NavRoutes.chat(id)) },
                onCreateChatPlaceholder = { /* placeholder later */ },
                onRenameSession = { _, _ -> },
                onPinSession = { _ -> },
                onMoveSession = { sessionId, projectId -> vm.moveSession(sessionId, projectId) },
                onDeleteSession = { _ -> /* needs DELETE /opencode/session/:id when spec added */ }
            )
        }
        composable(NavRoutes.CHAT_PLACEHOLDER, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { backStack ->
            val sid = backStack.arguments?.getString("sessionId") ?: ""
            ChatPlaceholderScreen(sessionId = sid, onBack = { navController.popBackStack() })
        }
    }
}
