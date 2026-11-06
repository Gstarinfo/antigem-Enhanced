package com.example.gemini.data.project

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.gemini.data.daemon.IdeApiClient
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Modular Project Importer
 * Handles safe unpacking of project ZIP archives, path normalization,
 * and registering workspaces without polluting core application logic.
 */
object ProjectImporter {
    private const val TAG = "ProjectImporter"

    data class ImportProgress(
        val stage: String,
        val currentEntry: String = "",
        val entriesExtracted: Int = 0,
        val totalEntries: Int = 0,
        val isComplete: Boolean = false,
        val error: String? = null
    )

    /**
     * Resolves display name and byte size of a file URI (content:// or file://)
     */
    fun queryFileInfo(context: Context, uri: Uri): Pair<String, Long> {
        var name = ""
        var size = 0L
        try {
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx != -1) name = cursor.getString(nameIdx) ?: ""
                        if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                    }
                }
            } else if (uri.scheme == "file" || uri.path != null) {
                val f = File(uri.path ?: "")
                if (f.exists()) {
                    name = f.name
                    size = f.length()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query file info for $uri: ${e.message}")
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported_project.zip"
        }
        return Pair(name, size)
    }

    /**
     * Extracts a ZIP archive into a workspace directory.
     * Automatically strips single enclosing root folders (common in GitHub downloads)
     * and guards against Zip-Slip vulnerabilities.
     */
    suspend fun extractZipToWorkspace(
        context: Context,
        zipUri: Uri?,
        zipFilePath: String?,
        projectName: String,
        targetDirOverride: File? = null,
        onProgress: (ImportProgress) -> Unit = {}
    ): Result<ProjectItem> = withContext(Dispatchers.IO) {
        val sanitizedProjectName = projectName.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "imported_project" }
        val projectsDir = targetDirOverride ?: File(LocalEnvironmentManager.getProjectsDir(context), sanitizedProjectName)

        val tempZipFile = File(context.cacheDir, "temp_import_${System.currentTimeMillis()}.zip")

        try {
            onProgress(ImportProgress(stage = "Preparing archive..."))

            // 1. Stage input stream to local cache for fast random-access extraction
            if (zipUri != null) {
                val inStream = context.contentResolver.openInputStream(zipUri)
                    ?: return@withContext Result.failure(IllegalArgumentException("Cannot open content URI: $zipUri"))
                inStream.use { input ->
                    FileOutputStream(tempZipFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } else if (!zipFilePath.isNullOrBlank()) {
                val srcFile = File(zipFilePath)
                if (!srcFile.exists() || !srcFile.canRead()) {
                    return@withContext Result.failure(IllegalArgumentException("File does not exist or cannot be read: $zipFilePath"))
                }
                FileInputStream(srcFile).use { input ->
                    FileOutputStream(tempZipFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                return@withContext Result.failure(IllegalArgumentException("No ZIP source provided"))
            }

            // 2. Open ZIP and inspect for common single top-level directory wrapper
            val zipFile = try {
                ZipFile(tempZipFile)
            } catch (e: Exception) {
                return@withContext Result.failure(IllegalArgumentException("Invalid or corrupted ZIP archive: ${e.message}"))
            }

            val topLevelDirs = mutableSetOf<String>()
            var hasRootFiles = false
            var totalEntries = 0

            val entriesSeq = zipFile.entries().asSequence()
            for (entry in entriesSeq) {
                val name = entry.name.removePrefix("/")
                if (name.isBlank() || isJunkEntry(name)) continue
                totalEntries++
                val firstSegment = name.substringBefore('/')
                if (name.contains('/')) {
                    topLevelDirs.add(firstSegment)
                } else if (!entry.isDirectory) {
                    hasRootFiles = true
                }
            }

            // If all files are inside a single folder (e.g. repo-main/...), unwrap it directly
            val commonPrefix = if (!hasRootFiles && topLevelDirs.size == 1) {
                "${topLevelDirs.first()}/"
            } else {
                ""
            }

            // 3. Prepare target workspace directory
            if (!projectsDir.exists()) {
                projectsDir.mkdirs()
            }
            val canonicalTarget = projectsDir.canonicalPath

            onProgress(ImportProgress(stage = "Extracting files...", totalEntries = totalEntries))

            var extractedCount = 0
            val buffer = ByteArray(16 * 1024)

            for (entry in zipFile.entries().asSequence()) {
                val rawName = entry.name.removePrefix("/")
                if (rawName.isBlank() || isJunkEntry(rawName)) continue

                val cleanRelativePath = rawName.removePrefix(commonPrefix)
                if (cleanRelativePath.isBlank()) continue

                val targetFile = File(projectsDir, cleanRelativePath)
                val canonicalDest = targetFile.canonicalPath

                // Zip-Slip security check
                if (!canonicalDest.startsWith(canonicalTarget + File.separator) && canonicalDest != canonicalTarget) {
                    Log.w(TAG, "Skipping suspicious entry outside target: $rawName")
                    continue
                }

                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    zipFile.getInputStream(entry).use { inStream ->
                        BufferedOutputStream(FileOutputStream(targetFile)).use { outStream ->
                            var read: Int
                            while (inStream.read(buffer).also { read = it } != -1) {
                                outStream.write(buffer, 0, read)
                            }
                        }
                    }

                    // Preserve executable bit for scripts
                    if (targetFile.name.endsWith(".sh") || targetFile.name == "gradlew") {
                        targetFile.setExecutable(true, false)
                    }
                }

                extractedCount++
                if (extractedCount % 5 == 0 || extractedCount == totalEntries) {
                    onProgress(
                        ImportProgress(
                            stage = "Extracting files...",
                            currentEntry = cleanRelativePath,
                            entriesExtracted = extractedCount,
                            totalEntries = totalEntries
                        )
                    )
                }
            }

            zipFile.close()

            // 4. Register workspace with IDE API client
            try {
                IdeApiClient.addSavedProject(projectsDir.absolutePath, sanitizedProjectName)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register project with IdeApiClient: ${e.message}")
            }

            onProgress(
                ImportProgress(
                    stage = "Complete!",
                    entriesExtracted = extractedCount,
                    totalEntries = totalEntries,
                    isComplete = true
                )
            )

            val item = ProjectItem(name = sanitizedProjectName, path = projectsDir.absolutePath)
            Result.success(item)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract project: ${e.message}", e)
            onProgress(ImportProgress(stage = "Error", isComplete = true, error = e.message))
            Result.failure(e)
        } finally {
            if (tempZipFile.exists()) {
                tempZipFile.delete()
            }
        }
    }

    private fun isJunkEntry(name: String): Boolean {
        return name.startsWith("__MACOSX/") ||
                name.contains("/__MACOSX/") ||
                name.endsWith(".DS_Store") ||
                name.endsWith("Thumbs.db")
    }
}
