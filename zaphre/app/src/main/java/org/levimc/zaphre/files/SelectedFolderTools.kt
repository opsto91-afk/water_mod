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
        dir.findFile(name) ?: throw IOException("Not found: $name")
    }
    fun list(context: Context, path: String): String {
        val dir = resolve(context, path)
        if (!dir.isDirectory) throw IOException("Not a directory")
        return dir.listFiles().take(200).joinToString("\n") { "${if (it.isDirectory) "[folder]" else "[file]"} ${it.name}" }.ifBlank { "(empty)" }
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
    fun create(context: Context, path: String, directory: Boolean, content: String): String {
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
