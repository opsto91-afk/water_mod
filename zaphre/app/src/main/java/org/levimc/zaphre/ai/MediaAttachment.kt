package org.levimc.zaphre.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Gemini inline media: bounded before encoding, never persisted in chat history. */
object MediaAttachment {
    const val MAX_BYTES = 12 * 1024 * 1024
    data class Item(val name: String, val mime: String, val bytes: ByteArray)
    fun read(context: Context, uri: Uri): Item {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "attachment"
        val mime = resolver.getType(uri)?.lowercase()?.substringBefore(';') ?: throw IOException("Could not identify file type")
        if (mime != "application/pdf" && mime !in setOf("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif") && mime !in setOf("video/mp4", "video/mpeg", "video/quicktime", "video/webm", "video/x-matroska", "video/3gpp")) throw IOException("Unsupported image, PDF or video type")
        if (mime.startsWith("video/")) {
            val retriever = MediaMetadataRetriever()
            val duration = try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (_: Exception) { null } finally { retriever.release() }
            if (duration == null || duration <= 0 || duration > 60_000) throw IOException("Video duration must be readable and no more than 60 seconds")
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) throw IOException("File exceeds the 12 MiB inline attachment limit")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw IOException("Cannot read attachment")
        if (bytes.isEmpty()) throw IOException("Attachment is empty")
        return Item(name.replace(Regex("[\\r\\n]"), " ").take(160), mime, bytes)
    }
}
