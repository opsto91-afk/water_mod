package org.levimc.zaphre.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ApiProtocol {
    GEMINI_NATIVE,
    ANTHROPIC_NATIVE,
    OPENAI_COMPAT
}

enum class ToolCallMode(val label: String) {
    NATIVE_AUTO("Native Function Calling (with auto XML fallback)"),
    PROMPT_XML("Prompt <tool_call> Mode (best for small local GGUF models)")
}

data class AiProviderPreset(
    val id: String,
    val name: String,
    val badge: String,
    val protocol: ApiProtocol,
    val baseUrl: String,
    val defaultModel: String,
    val suggestedModels: List<String>,
    val isLocal: Boolean = false,
    val keyHint: String = "API Key"
)

val BUILTIN_PROVIDERS: List<AiProviderPreset> = listOf(
    AiProviderPreset(
        id = "gemini",
        name = "Google Gemini (AI Studio)",
        badge = "Online",
        protocol = ApiProtocol.GEMINI_NATIVE,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModel = "gemini-2.5-flash",
        suggestedModels = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash"
        ),
        keyHint = "AIza... (Google AI Studio API Key)"
    ),
    AiProviderPreset(
        id = "openrouter",
        name = "OpenRouter (All Models)",
        badge = "Online",
        protocol = ApiProtocol.OPENAI_COMPAT,
        baseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "google/gemini-2.5-flash",
        suggestedModels = listOf(
            "google/gemini-2.5-flash",
            "google/gemini-2.5-pro",
            "anthropic/claude-sonnet-4",
            "deepseek/deepseek-chat-v3-0324",
            "openai/gpt-4.1",
            "qwen/qwen3-coder"
        ),
        keyHint = "sk-or-v1-..."
    ),
    AiProviderPreset(
        id = "openai",
        name = "OpenAI",
        badge = "Online",
        protocol = ApiProtocol.OPENAI_COMPAT,
        baseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4.1",
        suggestedModels = listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4o", "o4-mini"),
        keyHint = "sk-..."
    ),
    AiProviderPreset(
        id = "anthropic",
        name = "Anthropic Claude",
        badge = "Online",
        protocol = ApiProtocol.ANTHROPIC_NATIVE,
        baseUrl = "https://api.anthropic.com/v1",
        defaultModel = "claude-sonnet-4-20250514",
        suggestedModels = listOf(
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-latest",
            "claude-3-5-haiku-latest"
        ),
        keyHint = "sk-ant-..."
    ),
    AiProviderPreset(
        id = "deepseek",
        name = "DeepSeek",
        badge = "Online",
        protocol = ApiProtocol.OPENAI_COMPAT,
        baseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        suggestedModels = listOf("deepseek-chat", "deepseek-reasoner"),
        keyHint = "sk-..."
    ),
    AiProviderPreset(
        id = "groq",
        name = "Groq (Ultra-Fast)",
        badge = "Online",
        protocol = ApiProtocol.OPENAI_COMPAT,
        baseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.3-70b-versatile",
        suggestedModels = listOf(
            "llama-3.3-70b-versatile",
            "moonshotai/kimi-k2-instruct",
            "qwen/qwen3-32b"
        ),
        keyHint = "gsk_..."
    ),
    AiProviderPreset(
        id = "xai",
        name = "xAI (Grok)",
        badge = "Online",
        protocol = ApiProtocol.OPENAI_COMPAT,
        baseUrl = "https://api.x.ai/v1",
        defaultModel = "grok-3-mini",
        suggestedModels = listOf("grok-3", "grok-3-mini"),
        keyHint = "xai-..."
    ),
    AiProviderPreset(
        id = "mistral",
        name = "Mistral / Codestral",
        badge = "Online",
        protocol = ApiProtocol.OPENAI_COMPAT,
        baseUrl = "https://api.mistral.ai/v1",
        defaultModel = "mistral-large-latest",
        suggestedModels = listOf("mistral-large-latest", "codestral-latest", "devstral-small-latest"),
        keyHint = "Mistral API Key"
    ),
    AiProviderPreset(
        id = "local_llamacpp",
        name = "Local AI — llama.cpp / Termux",
        badge = "Local",
        protocol = ApiProtocol.OPENAI_COMPAT,
        baseUrl = "http://127.0.0.1:8080/v1",
        defaultModel = "local-model",
        suggestedModels = listOf("local-model", "qwen2.5-coder-7b", "qwen3-4b", "gemma-3-4b"),
        isLocal = true,
        keyHint = "Optional (leave blank for local)"
    ),
    AiProviderPreset(
        id = "local_ollama",
        name = "Local AI — Ollama (Phone / LAN PC)",
        badge = "Local",
        protocol = ApiProtocol.OPENAI_COMPAT,
        baseUrl = "http://127.0.0.1:11434/v1",
        defaultModel = "qwen2.5-coder:7b",
        suggestedModels = listOf("qwen2.5-coder:7b", "qwen3:8b", "deepseek-r1:8b", "llama3.1:8b"),
        isLocal = true,
        keyHint = "Optional (not needed for Ollama)"
    ),
    AiProviderPreset(
        id = "local_lmstudio",
        name = "Local AI — LM Studio / Custom LAN",
        badge = "Local",
        protocol = ApiProtocol.OPENAI_COMPAT,
        baseUrl = "http://127.0.0.1:1234/v1",
        defaultModel = "local-model",
        suggestedModels = listOf("local-model"),
        isLocal = true,
        keyHint = "Optional"
    ),
    AiProviderPreset(
        id = "custom_openai",
        name = "Custom OpenAI-Compatible API",
        badge = "Custom",
        protocol = ApiProtocol.OPENAI_COMPAT,
        baseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        suggestedModels = emptyList(),
        keyHint = "Bearer API Key (optional)"
    )
)

data class ActiveAiSettings(
    val providerId: String = "gemini",
    val protocol: ApiProtocol = ApiProtocol.GEMINI_NATIVE,
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    val apiKey: String = "",
    val model: String = "gemini-2.5-flash",
    val toolCallMode: ToolCallMode = ToolCallMode.NATIVE_AUTO,
    val maxAgentSteps: Int = 25,
    val temperature: Float = 0.2f,
    val autoStopMcAfterLoop: Boolean = true,
    val customPrompt: String = ""
) {
    val preset: AiProviderPreset
        get() = BUILTIN_PROVIDERS.firstOrNull { it.id == providerId } ?: BUILTIN_PROVIDERS.first()
}

object AiSettingsStore {
    private const val PREFS = "zaphre_ai_settings"

    fun load(context: Context): ActiveAiSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pid = p.getString("active_provider", "gemini") ?: "gemini"
        val preset = BUILTIN_PROVIDERS.firstOrNull { it.id == pid } ?: BUILTIN_PROVIDERS.first()
        val baseUrl = p.getString("url_$pid", preset.baseUrl)?.ifBlank { preset.baseUrl } ?: preset.baseUrl
        val key = p.getString("key_$pid", "") ?: ""
        val model = p.getString("model_$pid", preset.defaultModel)?.ifBlank { preset.defaultModel } ?: preset.defaultModel
        val tcMode = p.getString("tool_mode_$pid", ToolCallMode.NATIVE_AUTO.name)
            .let { n -> ToolCallMode.entries.firstOrNull { it.name == n } ?: ToolCallMode.NATIVE_AUTO }

        return ActiveAiSettings(
            providerId = preset.id,
            protocol = preset.protocol,
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKey = key.trim(),
            model = model.trim(),
            toolCallMode = tcMode,
            maxAgentSteps = p.getInt("max_steps", 25).coerceIn(3, 80),
            temperature = p.getFloat("temperature", 0.2f).coerceIn(0f, 1.5f),
            autoStopMcAfterLoop = p.getBoolean("auto_stop_after_loop", true),
            customPrompt = p.getString("custom_prompt", "") ?: ""
        )
    }

    fun save(context: Context, s: ActiveAiSettings) {
        val pid = s.providerId
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("active_provider", pid)
            .putString("url_$pid", s.baseUrl.trim().trimEnd('/'))
            .putString("key_$pid", s.apiKey.trim())
            .putString("model_$pid", s.model.trim())
            .putString("tool_mode_$pid", s.toolCallMode.name)
            .putInt("max_steps", s.maxAgentSteps.coerceIn(3, 80))
            .putFloat("temperature", s.temperature.coerceIn(0f, 1.5f))
            .putBoolean("auto_stop_after_loop", s.autoStopMcAfterLoop)
            .putString("custom_prompt", s.customPrompt)
            .apply()
    }

    fun selectProvider(context: Context, providerId: String): ActiveAiSettings {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("active_provider", providerId)
            .apply()
        return load(context)
    }

    fun hasKeySaved(context: Context, providerId: String): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !p.getString("key_$providerId", "").isNullOrBlank()
    }
}

data class ToolCallSpec(
    val id: String,
    val name: String,
    val args: JSONObject,
    val thoughtSignature: String? = null
)

data class ToolStepRecord(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val argsSummary: String,
    val output: String = "",
    val running: Boolean = false,
    val isError: Boolean = false,
    val durationMs: Long = 0L
)

enum class ChatRole { USER, ASSISTANT, SYSTEM_NOTE }

data class ChatUiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val toolSteps: List<ToolStepRecord> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
    val modelTag: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("role", role.name)
        put("text", text)
        put("timestampMs", timestampMs)
        put("modelTag", modelTag)
        put("toolSteps", JSONArray().apply {
            for (s in toolSteps) {
                put(JSONObject().apply {
                    put("id", s.id)
                    put("toolName", s.toolName)
                    put("argsSummary", s.argsSummary)
                    put("output", s.output.take(4000))
                    put("isError", s.isError)
                    put("durationMs", s.durationMs)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): ChatUiMessage {
            val arr = o.optJSONArray("toolSteps")
            val steps = if (arr != null) {
                List(arr.length()) { i ->
                    val s = arr.getJSONObject(i)
                    ToolStepRecord(
                        id = s.optString("id", UUID.randomUUID().toString()),
                        toolName = s.optString("toolName"),
                        argsSummary = s.optString("argsSummary"),
                        output = s.optString("output"),
                        running = false,
                        isError = s.optBoolean("isError", false),
                        durationMs = s.optLong("durationMs", 0L)
                    )
                }
            } else emptyList()
            return ChatUiMessage(
                id = o.optString("id", UUID.randomUUID().toString()),
                role = runCatching { ChatRole.valueOf(o.optString("role", "ASSISTANT")) }.getOrDefault(ChatRole.ASSISTANT),
                text = o.optString("text", ""),
                toolSteps = steps,
                timestampMs = o.optLong("timestampMs", 0L),
                modelTag = o.optString("modelTag", "")
            )
        }
    }
}

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New RE Session",
    val updatedAtMs: Long = System.currentTimeMillis(),
    val messages: List<ChatUiMessage> = emptyList()
)

object ChatHistoryStore {
    private fun dir(context: Context) = File(context.filesDir, "chat_sessions").apply { mkdirs() }

    fun listSessions(context: Context): List<ChatSession> {
        return dir(context).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { f -> runCatching { readSessionFile(f) }.getOrNull() }
            .sortedByDescending { it.updatedAtMs }
    }

    fun saveSession(context: Context, session: ChatSession) {
        runCatching {
            val o = JSONObject().apply {
                put("id", session.id)
                put("title", session.title)
                put("updatedAtMs", session.updatedAtMs)
                put("messages", JSONArray(session.messages.map { it.toJson() }))
            }
            File(dir(context), "${session.id}.json").writeText(o.toString())
        }
    }

    fun deleteSession(context: Context, sessionId: String) {
        runCatching { File(dir(context), "$sessionId.json").delete() }
    }

    private fun readSessionFile(file: File): ChatSession {
        val o = JSONObject(file.readText())
        val arr = o.optJSONArray("messages")
        val msgs = if (arr != null) List(arr.length()) { ChatUiMessage.fromJson(arr.getJSONObject(it)) } else emptyList()
        return ChatSession(
            id = o.getString("id"),
            title = o.optString("title", "RE Session"),
            updatedAtMs = o.optLong("updatedAtMs", file.lastModified()),
            messages = msgs
        )
    }
}
