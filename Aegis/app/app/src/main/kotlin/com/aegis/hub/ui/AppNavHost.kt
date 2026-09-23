package com.aegis.hub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aegis.hub.ui.screens.ControlCenterScreen
import com.aegis.hub.ui.screens.SkillManagerScreen
import com.aegis.hub.ui.screens.WorkspaceScreen
import com.aegis.hub.ui.screens.WorkflowScreen
import com.aegis.hub.ui.viewmodel.ChatViewModel
import com.aegis.hub.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val vm: MainViewModel = viewModel()
    val draftVm: ChatViewModel = viewModel(key = "draft_chat")

    val projects by vm.projects.collectAsState()
    val sessions by vm.sessions.collectAsState()

    NavHost(navController = navController, startDestination = NavRoutes.DRAFT_CHAT) {
        composable(NavRoutes.DRAFT_CHAT) {
            MainNavScreen(
                projects = projects,
                sessions = sessions,
                draftVm = draftVm,
                onNavigateProjects = { navController.navigate(NavRoutes.PROJECTS) },
                onNavigateChats = { navController.navigate(NavRoutes.CHATS) },
                onNavigateControlCenter = { navController.navigate("control_center") },
                onNavigateSkillManager = { navController.navigate("skill_manager") },
                onNavigateWorkspace = { navController.navigate("workspace") },
                onOpenProject = { id -> navController.navigate("project/$id") },
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
                onCreateProject = { name, desc, provider -> vm.createProject(name, desc, provider) },
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
            val detailVm: com.aegis.hub.ui.viewmodel.ProjectDetailViewModel = viewModel(key = "project_$pid")
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
                    onUnlinkProject = { target -> detailVm.unlinkProject(target) },
                    onPatchInstructions = { text -> detailVm.patchInstructions(text) },
                    onRenameSession = { sid, name ->
                        detailVm.renameSession(sid, name)
                        vm.refreshAll()
                    },
                    onUnlinkSession = { sid ->
                        detailVm.unlinkSession(sid)
                        vm.refreshAll()
                    },
                    onDeleteSession = { sid ->
                        detailVm.deleteSession(sid)
                        vm.deleteSession(sid)
                    }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator() else Text("Proyecto no encontrado")
                }
            }
        }
        composable(NavRoutes.CHATS) {
            val scope = rememberCoroutineScope()
            ChatsScreen(
                sessions = sessions,
                projects = projects,
                isLoading = vm.loadingSessions.collectAsState().value,
                error = vm.error.collectAsState().value,
                onBack = { navController.popBackStack() },
                onOpenSession = { id -> navController.navigate(NavRoutes.chat(id)) },
                onCreateChatPlaceholder = {
                    scope.launch {
                        val sid = vm.createSessionForProject("", "Chat ${System.currentTimeMillis() % 10000}")
                        if (!sid.isNullOrBlank()) {
                            navController.navigate(NavRoutes.chat(sid))
                        }
                    }
                },
                onRenameSession = { sid, name -> vm.renameSession(sid, name) },
                onPinSession = { _ -> },
                onMoveSession = { sessionId, projectId -> vm.moveSession(sessionId, projectId) },
                onDeleteSession = { sid -> vm.deleteSession(sid) },
                onRefresh = { vm.refreshSessions() },
                onClearError = { vm.clearError() }
            )
        }
        composable(NavRoutes.CHAT_PLACEHOLDER, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { backStack ->
            val sid = backStack.arguments?.getString("sessionId") ?: ""
            val chatVm: ChatViewModel = viewModel(key = "chat_$sid")
            val session = sessions.find { it.resolvedId == sid || it.id == sid || it.ID == sid }
            val prov = session?.provider ?: if (sid.startsWith("agy_") || sid.isBlank()) "antigravity" else "opencode"
            LaunchedEffect(sid, session?.title) {
                if (!session?.title.isNullOrBlank()) {
                    chatVm.setSessionTitle(session.title)
                }
            }
            ChatScreen(
                sessionId = sid,
                vm = chatVm,
                onBack = { navController.popBackStack() },
                onVoice = { navController.navigate(NavRoutes.voice(sid)) },
                sessionProvider = prov
            )
        }
        composable(NavRoutes.VOICE, arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { backStack ->
            val sid = backStack.arguments?.getString("sessionId") ?: ""
            val chatVm: ChatViewModel = viewModel(key = "chat_$sid")
            VoiceConversationScreen(sessionId = sid, vm = chatVm, onBack = { navController.popBackStack() })
        }
        
        composable("control_center") {
            ControlCenterScreen(navController = navController)
        }
        composable("skill_manager") {
            SkillManagerScreen(navController = navController)
        }
        composable("workspace") {
            WorkspaceScreen(navController = navController)
        }
        composable("workflow/{projectId}") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
            WorkflowScreen(navController = navController, projectId = projectId)
        }
    }
}
