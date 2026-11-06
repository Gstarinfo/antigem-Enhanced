package com.example.gemini.ui.project

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.project.ProjectImporter
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.theme.QuotaRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val CLONED_PATH_MARKER = "ANTIGEM_CLONED_PATH="

/**
 * Mobile-Harness style Modular Project Import Dialog
 * Allows bringing in existing projects via ZIP file (SAF File Picker or path)
 * or via Git (with support for branch selection and upstream remote separation).
 */
@Composable
fun ProjectImportDialog(
    onProjectImported: (ProjectItem) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) } // 0 = ZIP, 1 = Git

    // ZIP state
    var selectedZipUri by remember { mutableStateOf<Uri?>(null) }
    var selectedZipName by remember { mutableStateOf("") }
    var selectedZipSize by remember { mutableStateOf(0L) }
    var manualZipPath by remember { mutableStateOf("") }
    var zipProjectName by remember { mutableStateOf("") }
    var zipExtracting by remember { mutableStateOf(false) }
    var zipProgress by remember { mutableStateOf<ProjectImporter.ImportProgress?>(null) }
    var zipError by remember { mutableStateOf<String?>(null) }

    // SAF file picker launcher
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                selectedZipUri = uri
                manualZipPath = ""
                zipError = null
                val (name, size) = ProjectImporter.queryFileInfo(context, uri)
                selectedZipName = name
                selectedZipSize = size
                if (zipProjectName.isBlank()) {
                    zipProjectName = name.removeSuffix(".zip").removeSuffix(".tar.gz").replace(Regex("[^A-Za-z0-9._-]"), "_")
                }
            }
        }
    )

    // Git state
    var repoUrl by remember { mutableStateOf("") }
    var gitProjectName by remember { mutableStateOf("") }
    var branchName by remember { mutableStateOf("") }
    var trackUpstream by remember { mutableStateOf(false) }
    var upstreamUrl by remember { mutableStateOf("") }
    var gitRunning by remember { mutableStateOf(false) }
    val gitLogs = remember { mutableStateListOf<String>() }
    var gitExitCode by remember { mutableStateOf<Int?>(null) }
    val gitListState = rememberLazyListState()

    // Auto-derive project name from repo url
    LaunchedEffect(repoUrl) {
        val trimmed = repoUrl.trim().trimEnd('/')
        if (trimmed.isNotBlank() && gitProjectName.isBlank()) {
            val name = trimmed.substringAfterLast('/').removeSuffix(".git")
            if (name.isNotBlank()) gitProjectName = name
        }
    }

    Dialog(
        onDismissRequest = {
            if (!zipExtracting && !gitRunning) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (!zipExtracting && !gitRunning) onDismiss()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.82f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (selectedTab == 0) Icons.Outlined.Archive else Icons.Outlined.CloudDownload,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Import Project",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Bring existing code into workspace",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            enabled = !zipExtracting && !gitRunning,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Mode Tabs
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        contentColor = ClaudeTerracotta,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { if (!zipExtracting && !gitRunning) selectedTab = 0 },
                            text = { Text("ZIP Archive", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) },
                            icon = { Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(17.dp)) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { if (!zipExtracting && !gitRunning) selectedTab = 1 },
                            text = { Text("Git Repository", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) },
                            icon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(17.dp)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Tab Content
                    if (selectedTab == 0) {
                        // ==================== TAB 0: ZIP IMPORT ====================
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // File Picker Button
                            OutlinedButton(
                                onClick = {
                                    zipPickerLauncher.launch(
                                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
                                    )
                                },
                                enabled = !zipExtracting,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.7f))
                            ) {
                                Icon(Icons.Outlined.FileOpen, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (selectedZipName.isNotBlank()) "Change ZIP File..." else "Choose ZIP File From Device...",
                                    color = ClaudeTerracotta,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp
                                )
                            }

                            // Chosen File Details
                            if (selectedZipName.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = ClaudeTerracotta.copy(alpha = 0.1f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Outlined.Archive, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = selectedZipName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (selectedZipSize > 0) {
                                                val sizeMb = selectedZipSize / (1024f * 1024f)
                                                Text(
                                                    text = String.format("%.2f MB", sizeMb),
                                                    fontSize = 11.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Manual path input (fallback)
                            OutlinedTextField(
                                value = manualZipPath,
                                onValueChange = {
                                    manualZipPath = it
                                    if (it.isNotBlank()) {
                                        selectedZipUri = null
                                        selectedZipName = File(it).name
                                        if (zipProjectName.isBlank()) {
                                            zipProjectName = File(it).name.removeSuffix(".zip").replace(Regex("[^A-Za-z0-9._-]"), "_")
                                        }
                                    }
                                },
                                label = { Text("Or Enter ZIP File Path", fontSize = 12.sp) },
                                placeholder = { Text("/sdcard/Download/my-project.zip", fontSize = 12.sp) },
                                singleLine = true,
                                enabled = !zipExtracting,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Project Name
                            OutlinedTextField(
                                value = zipProjectName,
                                onValueChange = { zipProjectName = it },
                                label = { Text("Project / Workspace Name", fontSize = 12.sp) },
                                placeholder = { Text("my-awesome-app", fontSize = 12.sp) },
                                singleLine = true,
                                enabled = !zipExtracting,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Target location hint
                            val targetPathHint = "~/projects/${zipProjectName.ifBlank { "<project-name>" }}"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Destination: $targetPathHint",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }

                            // Progress & Extraction status
                            if (zipExtracting) {
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = ClaudeTerracotta
                                )
                                zipProgress?.let { prog ->
                                    Text(
                                        text = "${prog.stage} (${prog.entriesExtracted}/${prog.totalEntries}) ${prog.currentEntry}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            zipError?.let { err ->
                                Text(text = "❌ $err", fontSize = 12.sp, color = QuotaRed)
                            }
                        }

                        // Bottom Action Button for ZIP
                        Button(
                            onClick = {
                                val name = zipProjectName.trim()
                                if (name.isBlank()) {
                                    zipError = "Please specify a project name."
                                    return@Button
                                }
                                if (selectedZipUri == null && manualZipPath.isBlank()) {
                                    zipError = "Please select or provide a ZIP file path."
                                    return@Button
                                }

                                zipExtracting = true
                                zipError = null
                                scope.launch {
                                    val res = ProjectImporter.extractZipToWorkspace(
                                        context = context,
                                        zipUri = selectedZipUri,
                                        zipFilePath = manualZipPath.takeIf { it.isNotBlank() },
                                        projectName = name
                                    ) { prog ->
                                        zipProgress = prog
                                    }
                                    zipExtracting = false
                                    res.onSuccess { item ->
                                        Toast.makeText(context, "Project '${item.name}' imported successfully!", Toast.LENGTH_SHORT).show()
                                        onProjectImported(item)
                                        onDismiss()
                                    }.onFailure { err ->
                                        zipError = err.message ?: "Extraction failed."
                                    }
                                }
                            },
                            enabled = !zipExtracting && (selectedZipUri != null || manualZipPath.isNotBlank()) && zipProjectName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            if (zipExtracting) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text("Extracting Archive...")
                            } else {
                                Text("Import & Open Workspace", fontWeight = FontWeight.Bold)
                            }
                        }

                    } else {
                        // ==================== TAB 1: GIT CLONE ====================
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = repoUrl,
                                onValueChange = { repoUrl = it },
                                label = { Text("Repository HTTPS URL", fontSize = 12.sp) },
                                placeholder = { Text("https://github.com/user/my-repo.git", fontSize = 12.sp) },
                                singleLine = true,
                                enabled = !gitRunning,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = gitProjectName,
                                    onValueChange = { gitProjectName = it },
                                    label = { Text("Folder Name", fontSize = 12.sp) },
                                    singleLine = true,
                                    enabled = !gitRunning,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = branchName,
                                    onValueChange = { branchName = it },
                                    label = { Text("Branch (optional)", fontSize = 12.sp) },
                                    placeholder = { Text("main", fontSize = 12.sp) },
                                    singleLine = true,
                                    enabled = !gitRunning,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Keep Original Repo Separate Card (Upstream)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { if (!gitRunning) trackUpstream = !trackUpstream }
                                    ) {
                                        Checkbox(
                                            checked = trackUpstream,
                                            onCheckedChange = { if (!gitRunning) trackUpstream = it },
                                            colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text("Track Original Upstream Repo", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                            Text(
                                                "Keep original repo and your edits separated clean",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                    }

                                    AnimatedVisibility(visible = trackUpstream) {
                                        OutlinedTextField(
                                            value = upstreamUrl,
                                            onValueChange = { upstreamUrl = it },
                                            label = { Text("Original / Upstream Git URL", fontSize = 11.5.sp) },
                                            placeholder = { Text("https://github.com/original-author/repo.git", fontSize = 11.5.sp) },
                                            singleLine = true,
                                            enabled = !gitRunning,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            // Live Git Terminal Logs
                            if (gitLogs.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF1E1E1E),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp)
                                ) {
                                    LazyColumn(
                                        state = gitListState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp)
                                    ) {
                                        items(gitLogs) { line ->
                                            Text(
                                                text = line,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = when {
                                                    line.contains("✅") -> QuotaGreen
                                                    line.contains("❌") || line.contains("Error") -> QuotaRed
                                                    line.contains("⚡") -> ClaudeTerracotta
                                                    else -> Color(0xFFD4D4D4)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Bottom Action Button for Git
                        Button(
                            onClick = {
                                val url = repoUrl.trim()
                                if (!url.startsWith("https://")) {
                                    Toast.makeText(context, "Please provide a valid https:// repository URL", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val folder = gitProjectName.trim()
                                val branch = branchName.trim()
                                val upstream = if (trackUpstream) upstreamUrl.trim() else ""

                                gitRunning = true
                                gitLogs.clear()
                                gitLogs.add("⚡ Starting git clone...")
                                scope.launch {
                                    var clonedPath: String? = null
                                    val args = listOfNotNull(
                                        url,
                                        folder.takeIf { it.isNotEmpty() },
                                        branch.takeIf { it.isNotEmpty() },
                                        upstream.takeIf { it.isNotEmpty() }
                                    )
                                    val res = LocalEnvironmentManager.executeScriptLive(
                                        context = context,
                                        scriptName = "git_clone",
                                        args = args
                                    ) { line ->
                                        if (line.startsWith(CLONED_PATH_MARKER)) {
                                            clonedPath = line.removePrefix(CLONED_PATH_MARKER).trim()
                                            return@executeScriptLive
                                        }
                                        if (line.isBlank()) return@executeScriptLive
                                        gitLogs.add(line)
                                    }
                                    gitExitCode = res.exitCode
                                    gitRunning = false
                                    val path = clonedPath
                                    if (res.exitCode == 0 && path != null) {
                                        delay(800)
                                        val item = ProjectItem(name = File(path).name, path = path)
                                        onProjectImported(item)
                                        onDismiss()
                                    }
                                }
                            },
                            enabled = !gitRunning && repoUrl.trim().startsWith("https://"),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            if (gitRunning) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text("Cloning Repository...")
                            } else {
                                Text("Clone & Open Workspace", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
