package org.levimc.zaphre.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.levimc.zaphre.mc.ZaphkielConfig
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class WorkspaceEntry(
    val name: String,
    val relativePath: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val category: String
) {
    fun formattedSize(): String {
        if (isDirectory) return "DIR"
        return when {
            sizeBytes < 1024L -> "$sizeBytes B"
            sizeBytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", sizeBytes / (1024.0 * 1024.0))
        }
    }

    fun formattedTime(): String {
        if (lastModifiedMs <= 0L) return ""
        return SimpleDateFormat("MMM dd HH:mm:ss", Locale.US).format(Date(lastModifiedMs))
    }
}

data class FileSlice(
    val relativePath: String,
    val startLine: Int,
    val endLine: Int,
    val totalLines: Int,
    val sizeBytes: Long,
    val truncated: Boolean,
    val content: String,
    val rawForEdit: Boolean = false
)

object WorkspaceFiles {

    fun resolveSafe(context: Context, relativeOrAbs: String): File {
        val root = ZaphkielConfig.ensureWorkspaceLayout(context).canonicalFile
        val trimmed = relativeOrAbs.trim().removePrefix("./")
        if (trimmed.isEmpty() || trimmed == ".") return root

        val candidate = if (trimmed.startsWith("/")) {
            File(trimmed).canonicalFile
        } else {
            File(root, trimmed).canonicalFile
        }
        // Allow access inside workspace root OR shared Levi kurumi directory
        val leviRoot = File(ZaphkielConfig.LEVI_KURUMI_PATH).canonicalFile
        val insideWorkspace = candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)
        val insideLevi = candidate.path == leviRoot.path || candidate.path.startsWith(leviRoot.path + File.separator)
        require(insideWorkspace || insideLevi) {
            "Path outside RE workspace is blocked: $relativeOrAbs"
        }
        return candidate
    }

    fun list(context: Context, subPath: String = ""): List<WorkspaceEntry> {
        val root = ZaphkielConfig.ensureWorkspaceLayout(context).canonicalFile
        val dir = runCatching { resolveSafe(context, subPath) }.getOrDefault(root)
        if (!dir.isDirectory) return emptyList()

        return dir.listFiles().orEmpty()
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() })
            .map { f ->
                val rel = f.canonicalPath.removePrefix(root.path).removePrefix(File.separator)
                    .ifEmpty { f.name }
                WorkspaceEntry(
                    name = f.name,
                    relativePath = rel,
                    absolutePath = f.absolutePath,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isFile) f.length() else 0L,
                    lastModifiedMs = f.lastModified(),
                    category = categorize(f)
                )
            }
    }

    private fun categorize(f: File): String {
        if (f.isDirectory) return "Folder"
        val n = f.name.lowercase()
        return when {
            n == "zaphkiel_config.txt" || n == "zinput.txt" || n == "sig_targets.txt" || n == "knames.json" -> "Config"
            n.startsWith("signatures.") -> "Signatures"
            n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".cc") -> "Pseudo-C"
            n.endsWith(".h") || n.endsWith(".hpp") -> "Headers"
            n.contains("dump") || n == "str.txt" || n.contains("strings") -> "Dumps"
            n.startsWith("zaphkiel_") -> "Outputs"
            else -> "Other"
        }
    }

    /**
     * Memory-safe line range reader — works smoothly even on 100 MB+ rtti_dump.txt / zaphkiel_fullout.txt.
     */
    fun readSlice(
        context: Context,
        path: String,
        startLine: Int = 1,
        maxLines: Int = 200,
        maxChars: Int = 28_000,
        withLineNumbers: Boolean = true
    ): Result<FileSlice> = runCatching {
        val root = ZaphkielConfig.ensureWorkspaceLayout(context).canonicalFile
        val file = resolveSafe(context, path)
        require(file.isFile) { "File not found: $path" }

        val first = startLine.coerceAtLeast(1)
        val limit = maxLines.coerceIn(1, 1500)
        val sb = StringBuilder()
        var lineNo = 0
        var lastIncluded = first - 1
        var hitCap = false

        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 65536).use { br ->
            while (true) {
                val line = br.readLine() ?: break
                lineNo++
                if (lineNo < first) continue
                if (lineNo >= first + limit || sb.length >= maxChars) {
                    hitCap = true
                    // For giant files (>8MB), avoid scanning the whole tail just to count total lines
                    if (file.length() > 8L * 1024L * 1024L) break
                    continue
                }
                val clipped = if (line.length > 600) line.take(600) + " ...[line clipped]" else line
                if (withLineNumbers) {
                    sb.append(lineNo).append(": ").appendLine(clipped)
                } else {
                    sb.appendLine(clipped)
                }
                lastIncluded = lineNo
            }
        }

        val rel = file.canonicalPath.removePrefix(root.path).removePrefix(File.separator).ifEmpty { file.name }
        FileSlice(
            relativePath = rel,
            startLine = first,
            endLine = lastIncluded.coerceAtLeast(first),
            totalLines = lineNo,
            sizeBytes = file.length(),
            truncated = hitCap,
            content = sb.toString()
        )
    }

    /** Complete bounded edit read; never use paged/clipped display text as edit source. */
    fun readForEdit(context: Context, path: String): Result<FileSlice> = runCatching {
        val file = resolveSafe(context, path)
        require(file.isFile) { "File not found" }
        require(file.length() <= 256 * 1024) { "File is too large for the editor" }
        val bytes = file.readBytes()
        require(bytes.size <= 256 * 1024 && bytes.none { it == 0.toByte() }) { "Only small UTF-8 text files can be edited" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        FileSlice(relativePath = path, startLine = 1, endLine = text.count { it == '\n' } + 1,
            totalLines = text.count { it == '\n' } + 1, sizeBytes = bytes.size.toLong(),
            truncated = false, content = text, rawForEdit = true)
    }

    fun rename(context: Context, path: String, newName: String): Result<File> = runCatching {
        require(newName.isNotBlank() && newName != "." && newName != ".." && !newName.contains('/') && !newName.contains('\\')) { "Enter a file name without path separators" }
        val source = resolveSafe(context, path)
        val root = ZaphkielConfig.ensureWorkspaceLayout(context).canonicalFile
        require(source.exists() && source.canonicalFile != root) { "Cannot rename this item" }
        val target = resolveSafe(context, File(source.parentFile, newName).path)
        require(!target.exists()) { "Destination already exists" }
        require(source.renameTo(target)) { "Rename failed" }
        target
    }

    /**
     * Streaming grep with optional context lines — searches 100 MB+ dumps without OOM.
     */
    fun searchInFile(
        context: Context,
        path: String,
        pattern: String,
        isRegex: Boolean = false,
        caseSensitive: Boolean = false,
        maxMatches: Int = 60,
        contextLines: Int = 1
    ): Result<String> = runCatching {
        require(pattern.isNotBlank()) { "Search pattern cannot be empty" }
        val file = resolveSafe(context, path)
        require(file.isFile) { "File not found: $path" }

        val cap = maxMatches.coerceIn(1, 300)
        val ctx = contextLines.coerceIn(0, 8)
        val regex = if (isRegex) {
            val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(pattern, opts)
        } else {
            null
        }

        val prevRing = ArrayDeque<Pair<Int, String>>(ctx + 1)
        val out = StringBuilder()
        var lineNo = 0
        var matches = 0
        var afterRemaining = 0
        var lastPrintedLine = 0

        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 65536).use { br ->
            while (true) {
                val line = br.readLine() ?: break
                lineNo++
                val isHit = if (regex != null) {
                    regex.containsMatchIn(line)
                } else {
                    line.contains(pattern, ignoreCase = !caseSensitive)
                }

                if (isHit) {
                    if (matches >= cap) {
                        out.appendLine("... (stopped after $cap matches)")
                        break
                    }
                    matches++
                    for ((pNo, pTxt) in prevRing) {
                        if (pNo > lastPrintedLine) {
                            if (lastPrintedLine > 0 && pNo > lastPrintedLine + 1) out.appendLine("  ---")
                            out.append("  ").append(pNo).append("- ").appendLine(pTxt.take(400))
                            lastPrintedLine = pNo
                        }
                    }
                    if (lastPrintedLine > 0 && lineNo > lastPrintedLine + 1) out.appendLine("  ---")
                    out.append("> ").append(lineNo).append(": ").appendLine(line.take(500))
                    lastPrintedLine = lineNo
                    afterRemaining = ctx
                } else if (afterRemaining > 0) {
                    out.append("  ").append(lineNo).append("+ ").appendLine(line.take(400))
                    lastPrintedLine = lineNo
                    afterRemaining--
                }

                if (ctx > 0) {
                    if (prevRing.size >= ctx) prevRing.removeFirst()
                    prevRing.addLast(lineNo to line)
                }
            }
        }

        "File: ${file.name} (${formatBytes(file.length())}) | Matches: $matches\n$out"
    }

    fun writeText(context: Context, path: String, content: String, append: Boolean = false): Result<File> = runCatching {
        val file = resolveSafe(context, path)
        file.parentFile?.mkdirs()
        if (append) {
            file.appendText(content)
        } else {
            file.writeText(content)
        }
        file
    }

    fun createFolder(context: Context, path: String): Result<File> = runCatching {
        val dir = resolveSafe(context, path)
        if (!dir.exists() && !dir.mkdirs()) error("Could not create directory: $path")
        dir
    }

    fun delete(context: Context, path: String): Result<Boolean> = runCatching {
        val root = ZaphkielConfig.ensureWorkspaceLayout(context).canonicalFile
        val target = resolveSafe(context, path)
        require(target.canonicalPath != root.path) { "Cannot delete workspace root" }
        target.deleteRecursively()
    }

    fun importFileIntoDir(context: Context, uri: Uri, targetSubDir: String = ""): Result<File> = runCatching {
        val name = queryName(context, uri) ?: "imported_${System.currentTimeMillis()}.txt"
        val safeName = name.replace(Regex("[/\\\\]"), "_")
        val dir = resolveSafe(context, targetSubDir)
        dir.mkdirs()
        val dst = File(dir, safeName)
        context.contentResolver.openInputStream(uri)?.use { inp ->
            FileOutputStream(dst).use { out -> inp.copyTo(out, 128 * 1024) }
        } ?: error("Cannot read selected file")
        dst
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    private fun queryName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
