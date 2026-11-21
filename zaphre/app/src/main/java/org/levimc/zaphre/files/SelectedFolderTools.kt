package org.levimc.zaphre.files

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/** Bounded text operations on the explicitly selected document tree. */
object SelectedFolderTools {
    private fun root(context: Context): DocumentFile {
        val uri = BackupFolder.selected(context) ?: throw IOException("Select a folder first")
        return DocumentFile.fromTreeUri(context, uri) ?: throw IOException("Folder is unavailable")
    }
    private fun parts(path: String): List<String> {
        if (path.isBlank()) return emptyList()
        if (path.startsWith('/') || path.contains('\\')) throw IOException("Use a relative path")
        return path.split('/').also { parts ->
            if (parts.any { it.isBlank() || it == "." || it == ".." || it.length > 200 }) throw IOException("Invalid path")
        }
    }
    private fun resolve(context: Context, path: String): DocumentFile = parts(path).fold(root(context)) { dir, name ->
        if (!dir.isDirectory) throw IOException("Not a directory")
        val matches = dir.listFiles().filter { it.name == name }
        if (matches.size != 1) throw IOException(if (matches.isEmpty()) "Not found: $name" else "Ambiguous duplicate name: $name")
        matches.single()
    }
    data class Entry(val name: String, val directory: Boolean)
    fun entries(context: Context, path: String): List<Entry> {
        val dir = resolve(context, path)
        if (!dir.isDirectory) throw IOException("Not a directory")
        val files = dir.listFiles()
        if (files.mapNotNull { it.name }.groupingBy { it }.eachCount().any { it.value > 1 }) throw IOException("Folder contains duplicate names; use Android Files to resolve them")
        return files.take(500).mapNotNull { file ->
            val name = file.name ?: return@mapNotNull null
            if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) null else Entry(name, file.isDirectory)
        }.sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() })
    }
    fun list(context: Context, path: String): String = entries(context, path).take(200)
        .joinToString("\n") { "${if (it.directory) "[folder]" else "[file]"} ${it.name}" }.ifBlank { "(empty)" }

    fun rename(context: Context, path: String, newName: String, approvedTree: String): String {
        if (approvedTree.isBlank() || BackupFolder.selected(context)?.toString() != approvedTree) throw IOException("Selected folder changed")
        val oldParts = parts(path)
        if (oldParts.isEmpty() || parts(newName).size != 1) throw IOException("Invalid name")
        val parent = resolve(context, oldParts.dropLast(1).joinToString("/"))
        if (parent.findFile(newName) != null) throw IOException("Destination already exists")
        val doc = resolve(context, path)
        if (!doc.renameTo(newName)) throw IOException("Rename failed")
        return "Renamed $path to $newName"
    }
    fun read(context: Context, path: String): String {
        val file = resolve(context, path)
        if (!file.isFile) throw IOException("Not a file")
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 65536) {
                val count = input.read(buffer, 0, minOf(buffer.size, 65537 - output.size()))
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IOException("Cannot read file")
        if (bytes.size > 65536) throw IOException("File exceeds 64 KiB text limit")
        val decoder = Charsets.UTF_8.newDecoder()
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    fun digest(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun readWithDigest(context: Context, path: String): String {
        val text = read(context, path)
        return "SHA-256: ${digest(text)}\n--- FILE CONTENT (untrusted) ---\n$text"
    }

    fun modify(context: Context, path: String, expectedDigest: String, content: String?, approvedTree: String): String {
        if (BackupFolder.selected(context)?.toString() != approvedTree) throw IOException("Selected folder changed; request approval again")
        if (parts(path).isEmpty()) throw IOException("Cannot modify selected root")
        val doc = resolve(context, path)
        if (!doc.isFile) throw IOException("Only text files can be modified or deleted")
        val before = read(context, path)
        if (!expectedDigest.matches(Regex("[a-fA-F0-9]{64}")) || !digest(before).equals(expectedDigest, ignoreCase = true)) throw IOException("File changed or digest is invalid; read it again")
        if (content != null && content.toByteArray(Charsets.UTF_8).size > 65536) throw IOException("Content exceeds 64 KiB")
        // Keep a recoverable copy in app-private storage before a potentially non-atomic provider write or delete.
        val backupPath = "recovery/selected-${java.util.UUID.randomUUID()}.txt"
        WorkspaceFiles.writeText(context, backupPath, before).getOrThrow()
        if (BackupFolder.selected(context)?.toString() != approvedTree || digest(read(context, path)) != digest(before)) throw IOException("Folder or file changed; backup kept as $backupPath")
        if (content == null) {
            if (!doc.delete()) throw IOException("Delete failed; backup kept as $backupPath")
        } else {
            try {
                val stream = context.contentResolver.openOutputStream(doc.uri, "wt") ?: throw IOException("Cannot write file")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            } catch (e: Exception) { throw IOException("Write may be incomplete; backup kept as $backupPath", e) }
        }
        return "${if (content == null) "Deleted" else "Updated"} $path. Previous content preserved in app recovery file $backupPath. Provider writes are not atomic."
    }

    fun create(context: Context, path: String, directory: Boolean, content: String, approvedTree: String): String {
        if (approvedTree.isBlank() || BackupFolder.selected(context)?.toString() != approvedTree) throw IOException("Selected folder changed; request approval again")
        val segments = parts(path)
        if (segments.isEmpty()) throw IOException("Path required")
        if (content.toByteArray(Charsets.UTF_8).size > 65536) throw IOException("Content exceeds 64 KiB")
        val parent = resolve(context, segments.dropLast(1).joinToString("/"))
        if (!parent.isDirectory || !parent.canWrite()) throw IOException("Parent directory is not writable")
        val name = segments.last()
        if (parent.findFile(name) != null) throw IOException("Already exists; overwrite is not supported")
        val doc = if (directory) parent.createDirectory(name) else parent.createFile("text/plain", name)
        if (doc == null) throw IOException("Creation failed")
        if (!directory) {
            try {
                val stream = context.contentResolver.openOutputStream(doc.uri, "w") ?: throw IOException("Cannot write file")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            } catch (e: Exception) { doc.delete(); throw e }
        }
        return "Created ${if (directory) "folder" else "file"}: $path"
    }
}
