package org.levimc.zaphre.ai

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/** Explicit user-selected, chat-only interchange. Does not access another package's private files. */
object ChatArchive {
    private const val MAX_BYTES = 8 * 1024 * 1024
    fun export(context: Context, uri: Uri, sessions: List<ChatSession>) {
        val all = JSONArray()
        sessions.forEach { session ->
            all.put(JSONObject().put("id", session.id).put("title", session.title).put("updatedAtMs", session.updatedAtMs)
                .put("messages", JSONArray(session.messages.map { it.toJson() })))
        }
        val bytes = JSONObject().put("format", "peta-chat-archive-1").put("sessions", all).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Archive exceeds 8 MiB; export fewer chats")
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write archive")
        stream.use { it.write(bytes) }
    }
    fun import(context: Context, uri: Uri): Int {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) throw IOException("Archive exceeds 8 MiB")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw IOException("Cannot open archive")
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val root = JSONObject(text)
        val sessions = when {
            root.optString("format") == "peta-chat-archive-1" -> root.getJSONArray("sessions")
            root.has("messages") && root.has("title") -> JSONArray().put(root) // individual legacy session JSON if user has a copy
            else -> throw IOException("Not a supported chat archive or session JSON")
        }
        if (sessions.length() == 0 || sessions.length() > 200) throw IOException("Expected between 1 and 200 sessions")
        val imported = (0 until sessions.length()).map { index ->
            val obj = sessions.getJSONObject(index)
            val messages = obj.getJSONArray("messages")
            if (messages.length() > 5000) throw IOException("Too many messages")
            val parsed = (0 until messages.length()).map { i ->
                val msg = messages.getJSONObject(i)
                if (msg.optString("role") !in setOf("USER", "ASSISTANT", "SYSTEM_NOTE")) throw IOException("Invalid message role")
                ChatUiMessage.fromJson(msg).copy(id = UUID.randomUUID().toString())
            }
            ChatSession(id = UUID.randomUUID().toString(), title = obj.optString("title", "Imported chat").take(160),
                updatedAtMs = System.currentTimeMillis(), messages = parsed)
        }
        imported.forEach { ChatHistoryStore.saveSession(context, it) }
        return imported.size
    }
}
