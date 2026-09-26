package com.aegis.hub.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.aegis.hub.data.Project
import com.aegis.hub.ui.theme.AgyBadgeBg
import com.aegis.hub.ui.theme.AgyBadgeBorder
import com.aegis.hub.ui.theme.AgyBadgeFg
import com.aegis.hub.ui.theme.OpenCodeBadgeBg
import com.aegis.hub.ui.theme.OpenCodeBadgeBorder
import com.aegis.hub.ui.theme.OpenCodeBadgeFg
import com.aegis.hub.util.relativeTime

@Composable
fun ProviderBadge(provider: String, modifier: Modifier = Modifier) {
    val isAgy = provider.equals("antigravity", ignoreCase = true)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isAgy) AgyBadgeBg else OpenCodeBadgeBg,
        border = BorderStroke(1.dp, if (isAgy) AgyBadgeBorder else OpenCodeBadgeBorder),
        modifier = modifier
    ) {
        Text(
            text = if (isAgy) "Antigravity" else "OpenCode",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (isAgy) AgyBadgeFg else OpenCodeBadgeFg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

// ============================================================================
// CANONICAL DEFINITION: ProjectsScreen vs WorkspaceScreen
// - ProjectsScreen (CANÓNICA): Vista principal de Proyectos para el usuario.
//   Maneja proyectos lógicos (/api/projects), sus sesiones, archivado, skills
//   e instrucciones de proyecto en la base de datos projects.json.
// - WorkspaceScreen (SECUNDARIA/HERRAMIENTA TÉCNICA): Vista técnica del sistema
//   de archivos físico (/sdcard/projects/ vía /api/workspace/projects) para
//   auditoría de repositorios git, inicialización de carpetas .hub e indexación.
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectsScreen(
    projects: List<Project>,
    isLoading: Boolean = false,
    error: String? = null,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onCreateProject: (String, String, String) -> Unit = { _, _, _ -> },
    onLinkFolder: (String) -> Unit = { },
    onRenameProject: (String, String) -> Unit,
    onPatchProject: (String, String?, String?) -> Unit = { _, _, _ -> },
    onArchiveProject: (String) -> Unit = {},
    onDeleteProject: (String) -> Unit,
    onRefresh: () -> Unit = {},
    onClearError: () -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    // Selector "¿crear o vincular?" que aparece al pulsar + Nuevo proyecto.
    var showModeChooser by remember { mutableStateOf(false) }
    var pendingLinkError by remember { mutableStateOf<String?>(null) }

    // Gestor de archivos del sistema (Storage Access Framework). Devuelve un content://,
    // no una ruta, así que hay que traducirlo: para el almacenamiento externo el
    // documentId tiene forma "primary:MiCarpeta" -> /sdcard/MiCarpeta, que es donde el
    // Hub espera las carpetas (PROJECTS_ROOT = /sdcard/projects).
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = treeUriToFsPath(uri)
        if (path == null) {
            pendingLinkError = "No se pudo traducir la carpeta seleccionada. Elige una carpeta del almacenamiento interno."
        } else {
            pendingLinkError = null
            onLinkFolder(path)
        }
    }
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var editTarget by remember { mutableStateOf<Project?>(null) }
    var archiveTarget by remember { mutableStateOf<Project?>(null) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var menuTarget by remember { mutableStateOf<Project?>(null) }
    var pinnedIds by remember { mutableStateOf(setOf<String>()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val filtered = remember(projects, query) {
        if (query.isBlank()) projects else projects.filter {
            it.name.contains(query, ignoreCase = true) || (it.description ?: "").contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Proyectos", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Volver" }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showModeChooser = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = "Nuevo proyecto") },
                text = { Text("+ Nuevo proyecto", fontWeight = FontWeight.Medium) },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.semantics { contentDescription = "Nuevo proyecto" }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            var isRefreshing by remember { mutableStateOf(false) }
            LaunchedEffect(isLoading) { if (!isLoading) isRefreshing = false }

            if (isLoading && projects.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                // Barra de búsqueda estilo píldora
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Buscar proyectos…") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = "Buscar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Limpiar búsqueda",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Buscar proyectos" },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { isRefreshing = true; onRefresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    Icons.Outlined.Inbox,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text("No tienes proyectos aún — crea el primero", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 72.dp)
                        ) {
                            items(filtered, key = { it.id }) { proj ->
                                Box {
                                    OutlinedCard(
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .semantics(mergeDescendants = true) { contentDescription = proj.name }
                                            .combinedClickable(
                                                onClick = { onOpenProject(proj.id) },
                                                onLongClick = { menuTarget = proj }
                                            )
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = proj.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                val timeStr = relativeTime(proj.createdAt)
                                                val metaText = if (!proj.description.isNullOrBlank()) "$timeStr · ${proj.description}" else timeStr
                                                Text(
                                                    text = metaText,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                ProviderBadge(proj.resolvedProvider)
                                            }
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = menuTarget?.id == proj.id,
                                        onDismissRequest = { menuTarget = null },
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(if (pinnedIds.contains(proj.id)) "Desfijar" else "Fijar") },
                                            leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
                                            onClick = {
                                                menuTarget = null
                                                pinnedIds = if (pinnedIds.contains(proj.id)) pinnedIds - proj.id else pinnedIds + proj.id
                                            },
                                            modifier = Modifier.semantics { contentDescription = "Fijar" }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Editar detalles") },
                                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
                                            onClick = { menuTarget = null; editTarget = proj },
                                            modifier = Modifier.semantics { contentDescription = "Editar detalles" }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Archivar") },
                                            leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
                                            onClick = { menuTarget = null; archiveTarget = proj },
                                            modifier = Modifier.semantics { contentDescription = "Archivar" }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Eliminar", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) },
                                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                            onClick = { menuTarget = null; deleteTarget = proj },
                                            modifier = Modifier.semantics { contentDescription = "Eliminar" }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            error?.let {
                Snackbar(modifier = Modifier.padding(top = 8.dp), action = { TextButton(onClick = { onClearError(); onRefresh() }) { Text("Reintentar") } }) {
                    Text(it.take(300))
                }
            }
        }
    }

    if (showModeChooser) {
        AlertDialog(
            onDismissRequest = { showModeChooser = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("¿Qué quieres hacer?", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Crea un proyecto nuevo con su carpeta, o vincula una carpeta que ya exista en /sdcard/projects.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    pendingLinkError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showModeChooser = false
                    showCreate = true          // flujo ya existente: crea proyecto + carpeta
                }) { Text("Crear nuevo proyecto", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showModeChooser = false
                    pendingLinkError = null
                    folderPicker.launch(null)    // abre el gestor de archivos del sistema
                }) { Text("Vincular carpeta") }
            }
        )
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var selectedProvider by remember { mutableStateOf("antigravity") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Nuevo proyecto", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Descripción (opcional)") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    Text("Proveedor / Agente:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = selectedProvider == "opencode",
                            onClick = { selectedProvider = "opencode" },
                            label = { Text("OpenCode") },
                            shape = RoundedCornerShape(10.dp)
                        )
                        FilterChip(
                            selected = selectedProvider == "antigravity",
                            onClick = { selectedProvider = "antigravity" },
                            label = { Text("Antigravity") },
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        val trimmedName = name.trim()
                        onCreateProject(trimmedName, desc.trim(), selectedProvider)
                        showCreate = false
                        val safe = trimmedName.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9_-]"), "")
                        scope.launch {
                            snackbarHostState.showSnackbar("Proyecto creado en /sdcard/projects/$safe/")
                        }
                    }
                }) { Text("Crear", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancelar") } }
        )
    }

    editTarget?.let { proj ->
        var name by remember(proj.id) { mutableStateOf(proj.name) }
        var desc by remember(proj.id) { mutableStateOf(proj.description ?: "") }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Editar detalles", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Descripción") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { onPatchProject(proj.id, name.trim(), desc.trim().ifBlank { null }); editTarget = null } }) { Text("Guardar", fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancelar") } }
        )
    }

    archiveTarget?.let { proj ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Archivar proyecto", fontWeight = FontWeight.SemiBold) },
            text = { Text("¿Archivar \"${proj.name}\"? Podrás verlo en la sección de archivados.") },
            confirmButton = { TextButton(onClick = { onArchiveProject(proj.id); archiveTarget = null }) { Text("Archivar", fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("Cancelar") } }
        )
    }

    deleteTarget?.let { proj ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Eliminar proyecto", fontWeight = FontWeight.SemiBold) },
            text = { Text("¿Eliminar \"${proj.name}\"? Se archivará.") },
            confirmButton = { TextButton(onClick = { onDeleteProject(proj.id); deleteTarget = null }) { Text("Eliminar", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } }
        )
    }
}

/**
 * Traduce el content:// que devuelve el selector de carpetas del sistema a una ruta real.
 *
 * Solo se soporta el almacenamiento externo primario, que es donde vive /sdcard/projects:
 * el documentId tiene la forma "primary:MiCarpeta" y se traduce a /sdcard/MiCarpeta.
 * Para otros volúmenes (tarjetas SD) se devuelve null y la UI lo dice, en vez de
 * inventar una ruta que el Hub iba a rechazar.
 */
/**
 * Traduce un content:// del SAF a una ruta del sistema de ficheros.
 *
 * BUG QUE ARREGLA (2026-09-26): se usaba `pathSegments.firstOrNull()`, que en una URI
 * de ARBOL devuelve el literal "tree", no el documentId. OpenDocumentTree devuelve
 *
 *   content://com.android.externalstorage.documents/tree/primary%3Aprojects%2FX
 *                                            [0]=tree   [1]=primary:projects/X
 *
 * Asi que volume salia "tree" (sin ':'), volume != "primary" y la funcion devolvia
 * null SIEMPRE: "Vincular carpeta" no registraba nada y no llegaba a llamar al Hub.
 *
 * Ahora se busca el segmento que de verdad lleva el documentId (el que contiene ':'
 * y no es un literal de navigational), y se aceptan las autoridades de almacenamiento
 * externo y de Descargas. El volumen `primary` se traduce a /sdcard, que es donde el
 * Hub espera las carpetas (PROJECTS_ROOT = /sdcard/projects).
 */
private fun treeUriToFsPath(uri: Uri): String? {
    val authority = uri.authority ?: return null

    // Downloads: primary:Download/sub -> /sdcard/Download/sub
    if (authority == "com.android.providers.downloads.documents") {
        val treeId = uri.pathSegments.firstOrNull { it.contains(':') } ?: return null
        val decoded = Uri.decode(treeId)
        val rel = decoded.substringAfter(':', missingDelimiterValue = "").trim('/')
        if (rel.isBlank()) return null
        return "/sdcard/$rel"
    }

    if (authority != "com.android.externalstorage.documents") return null

    // El documentId NO es necesariamente el primer segmento: en las URIs de arbol es
    // el segundo (el primero es "tree"). Se busca el que lleva el ':' del volumen.
    val treeId = uri.pathSegments.firstOrNull { seg ->
        seg.contains(':') && seg != "tree" && seg != "document" && seg != "root"
    } ?: return null

    val decoded = Uri.decode(treeId)
    val volume = decoded.substringBefore(':', missingDelimiterValue = "")
    if (volume != "primary") return null
    val rel = decoded.substringAfter(':', missingDelimiterValue = "").trim('/')
    if (rel.isBlank()) return null
    return "/sdcard/$rel"
}
