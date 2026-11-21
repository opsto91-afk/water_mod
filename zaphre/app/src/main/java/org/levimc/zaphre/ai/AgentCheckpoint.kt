package org.levimc.zaphre.ai

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Safe restart points only. Never automatically replay a tool whose outcome may be unknown. */
object AgentCheckpoint {
    data class Snapshot(
        val sessionId: String, val providerId: String, val baseUrl: String, val model: String,
        val nextStep: Int, val text: String, val history: List<AgentTurnItem>, val steps: List<ToolStepRecord>,
        val state: String, val result: ChatUiMessage? = null, val needsReview: Boolean = false
    )
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "agent_checkpoint.json"))
    fun clear(context: Context) { file(context).delete() }
    @Synchronized fun save(context: Context, snapshot: Snapshot) {
        val arr = JSONArray()
        snapshot.history.forEach { item ->
            val obj = when (item) {
                is AgentTurnItem.User -> {
                    require(item.media == null) { "Media turns cannot be restored automatically" }
                    JSONObject().put("kind", "user").put("text", item.text)
                }
                is AgentTurnItem.Assistant -> JSONObject().put("kind", "assistant").put("text", item.text)
                    .put("calls", JSONArray().apply { item.toolCalls.forEach { call ->
                        put(JSONObject().put("id", call.id).put("name", call.name).put("args", call.args).put("signature", call.thoughtSignature))
                    } }).put("raw", item.rawGeminiParts)
                is AgentTurnItem.ToolResult -> JSONObject().put("kind", "tool").put("id", item.callId).put("name", item.toolName).put("output", item.output)
            }
            arr.put(obj)
        }
        val steps = JSONArray().apply { snapshot.steps.forEach { step ->
            put(JSONObject().put("id", step.id).put("name", step.toolName).put("args", step.argsSummary)
                .put("output", step.output.take(26000)).put("running", step.running).put("error", step.isError).put("duration", step.durationMs))
        } }
        val data = JSONObject().put("version", 1).put("session", snapshot.sessionId).put("provider", snapshot.providerId)
            .put("url", snapshot.baseUrl).put("model", snapshot.model).put("step", snapshot.nextStep).put("text", snapshot.text)
            .put("history", arr).put("steps", steps).put("state", snapshot.state).put("result", snapshot.result?.toJson()).put("needsReview", snapshot.needsReview)
            .toString().toByteArray(Charsets.UTF_8)
        if (data.size > 10 * 1024 * 1024) throw IOException("Agent checkpoint exceeds 10 MiB")
        val f = file(context); val stream = f.startWrite()
        try { stream.write(data); f.finishWrite(stream) } catch (t: Throwable) { f.failWrite(stream); throw t }
    }
    @Synchronized fun load(context: Context): Snapshot? {
        val f = file(context)
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) return null
        val json = f.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        require(json.getInt("version") == 1)
        val array = json.getJSONArray("history")
        val history = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            when (obj.getString("kind")) {
                "user" -> AgentTurnItem.User(obj.getString("text"))
                "assistant" -> {
                    val calls = obj.getJSONArray("calls")
                    AgentTurnItem.Assistant(obj.optString("text"), (0 until calls.length()).map { j ->
                        val c = calls.getJSONObject(j)
                        ToolCallSpec(c.getString("id"), c.getString("name"), c.getJSONObject("args"), c.optString("signature").takeIf { it.isNotBlank() })
                    }, obj.optJSONArray("raw"))
                }
                "tool" -> AgentTurnItem.ToolResult(obj.getString("id"), obj.getString("name"), obj.getString("output"))
                else -> throw IOException("Invalid checkpoint item")
            }
        }
        val steps = json.getJSONArray("steps")
        return Snapshot(json.getString("session"), json.getString("provider"), json.getString("url"), json.getString("model"),
            json.getInt("step"), json.optString("text"), history, (0 until steps.length()).map { i ->
                val o = steps.getJSONObject(i)
                ToolStepRecord(o.getString("id"), o.getString("name"), o.optString("args"), o.optString("output"), o.optBoolean("running"), o.optBoolean("error"), o.optLong("duration"))
            }, json.getString("state"), json.optJSONObject("result")?.let { ChatUiMessage.fromJson(it) }, json.optBoolean("needsReview", false))
    }
}
