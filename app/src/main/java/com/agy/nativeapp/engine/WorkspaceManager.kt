package com.agy.nativeapp.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.agy.nativeapp.model.WorkspaceFileItem
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object WorkspaceManager {

    const val DEFAULT_PROJECTS_ROOT = "/storage/emulated/0/AGYProjects"
    const val PROMPT_FILE_NAME = ".agy_prompt.txt"
    const val OUTPUT_LOG_FILE_NAME = ".agy_out.log"
    const val DONE_MARKER_FILE_NAME = ".agy_done"

    private val internalMetaFiles = setOf(
        PROMPT_FILE_NAME,
        OUTPUT_LOG_FILE_NAME,
        DONE_MARKER_FILE_NAME
    )

    /**
     * Converts an Android Storage Access Framework tree URI (ACTION_OPEN_DOCUMENT_TREE)
     * into a real POSIX filesystem path under /storage/emulated/0/... or /storage/<volume>/...
     */
    fun resolveTreeUriToPath(treeUri: Uri): String? {
        val authority = treeUri.authority ?: return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null

        return when (authority) {
            "com.android.externalstorage.documents" -> resolveExternalStorageDocId(docId)
            "com.android.providers.downloads.documents" -> {
                if (docId.startsWith("raw:")) {
                    docId.removePrefix("raw:")
                } else {
                    "/storage/emulated/0/Download"
                }
            }
            else -> null
        }
    }

    internal fun resolveExternalStorageDocId(
        docId: String,
        primaryRootOverride: String? = null
    ): String? {
        val splitIdx = docId.indexOf(':')
        if (splitIdx < 0) return null
        val volume = docId.substring(0, splitIdx)
        val relative = docId.substring(splitIdx + 1).trimStart('/')

        val primaryRoot = primaryRootOverride ?: runCatching {
            Environment.getExternalStorageDirectory()?.absolutePath
        }.getOrNull() ?: "/storage/emulated/0"

        val root = when {
            volume.equals("primary", ignoreCase = true) -> primaryRoot
            volume.equals("home", ignoreCase = true) -> "$primaryRoot/Documents"
            else -> "/storage/$volume"
        }

        return if (relative.isEmpty()) root else "$root/$relative"
    }

    fun createProjectFolder(folderName: String): Result<File> = runCatching {
        val sanitized = folderName.trim()
            .replace(Regex("[^A-Za-z0-9._\\-а-яА-ЯёЁ ]"), "_")
            .trim()
            .ifBlank { "project-${System.currentTimeMillis()}" }
        val root = File(DEFAULT_PROJECTS_ROOT)
        if (!root.exists()) {
            check(root.mkdirs()) {
                "Cannot create $DEFAULT_PROJECTS_ROOT. Grant All Files Access in Settings."
            }
        }
        val target = File(root, sanitized)
        if (!target.exists()) {
            check(target.mkdirs()) { "Cannot create folder ${target.absolutePath}" }
        }
        target
    }

    fun ensureDefaultWorkspaceExists(): String {
        val dir = File(DEFAULT_PROJECTS_ROOT, "default")
        runCatching {
            if (!dir.exists()) dir.mkdirs()
        }
        return dir.absolutePath
    }

    fun listWorkspaceFiles(
        workdir: String,
        includeHiddenControlFiles: Boolean = false
    ): List<WorkspaceFileItem> {
        val root = File(workdir)
        if (!root.exists() || !root.isDirectory) return emptyList()

        val items = mutableListOf<WorkspaceFileItem>()
        fun walk(dir: File) {
            if (items.size >= 500) return
            val children = dir.listFiles()?.sortedWith(
                compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) }
            ) ?: return

            for (child in children) {
                if (items.size >= 500) break
                val name = child.name
                if (name == ".git") continue
                if (!includeHiddenControlFiles && name in internalMetaFiles) continue

                val rel = child.relativeTo(root).path.replace('\\', '/')
                items += WorkspaceFileItem(
                    name = name,
                    relativePath = rel,
                    absolutePath = child.absolutePath,
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isDirectory) 0L else child.length(),
                    lastModifiedMs = child.lastModified()
                )
                if (child.isDirectory) {
                    walk(child)
                }
            }
        }
        walk(root)
        return items
    }

    fun findIndexHtml(workdir: String): File? {
        val root = File(workdir)
        if (!root.exists() || !root.isDirectory) return null
        val rootIndex = File(root, "index.html")
        if (rootIndex.exists() && rootIndex.isFile) return rootIndex

        // Fallback: check one level deep (e.g. dist/index.html, build/index.html, site/index.html)
        return root.listFiles()
            ?.firstOrNull { sub -> sub.isDirectory && File(sub, "index.html").isFile }
            ?.let { File(it, "index.html") }
    }

    fun exportWorkspaceToZip(context: Context, workdir: String): Result<File> = runCatching {
        val root = File(workdir)
        require(root.exists() && root.isDirectory) { "Workspace does not exist: $workdir" }

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeName = root.name.ifBlank { "workspace" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val zipFile = File(exportDir, "${safeName}-${stamp}.zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            val buffer = ByteArray(16 * 1024)
            fun addDir(dir: File) {
                val children = dir.listFiles() ?: return
                for (child in children) {
                    if (child.name == ".git" || child.name in internalMetaFiles) continue
                    val rel = child.relativeTo(root).path.replace('\\', '/')
                    if (child.isDirectory) {
                        zos.putNextEntry(ZipEntry("$rel/"))
                        zos.closeEntry()
                        addDir(child)
                    } else {
                        zos.putNextEntry(ZipEntry(rel))
                        BufferedInputStream(FileInputStream(child)).use { bis ->
                            while (true) {
                                val read = bis.read(buffer)
                                if (read <= 0) break
                                zos.write(buffer, 0, read)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
            addDir(root)
        }

        // Also mirror to /storage/emulated/0/Download when accessible
        runCatching {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloads.exists() || downloads.mkdirs()) {
                val copyTarget = File(downloads, zipFile.name)
                zipFile.copyTo(copyTarget, overwrite = true)
            }
        }

        zipFile
    }

    fun shareZipFile(context: Context, zipFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zipFile.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Экспорт проекта (.zip)").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
