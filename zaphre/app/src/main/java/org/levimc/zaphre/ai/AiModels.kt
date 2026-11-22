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
        id = "local_builtin", name = "Local AI — Built-in GGUF", badge = "On-device",
        protocol = ApiProtocol.OPENAI_COMPAT, baseUrl = "", defaultModel = "local-model",
        suggestedModels = emptyList(), isLocal = true, keyHint = "Not required for built-in models"
    ),
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
        baseUrl = "",
        defaultModel = "",
        suggestedModels = emptyList(),
        keyHint = "Bearer API Key (optional)"
    )
)

/** Normalize a pasted OpenAI-compatible chat endpoint to its base URL. */
fun normalizeBaseUrl(raw: String): String {
    var result = raw.trim().trimEnd('/')
    for (suffix in listOf("/chat/completions", "/completions")) {
        if (result.endsWith(suffix, ignoreCase = true)) {
            result = result.dropLast(suffix.length).trimEnd('/')
            break
        }
    }
    return result
}

data class ActiveAiSettings(
    val providerId: String = "gemini",
    val profileName: String = "",
    val protocol: ApiProtocol = ApiProtocol.GEMINI_NATIVE,
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    val apiKey: String = "",
    val additionalKeys: List<String> = emptyList(),
    val additionalKeysUnavailable: Boolean = false,
    val autoSwitchKeys: Boolean = false,
    val askSwitchKeys: Boolean = false,
    val model: String = "gemini-2.5-flash",
    val toolCallMode: ToolCallMode = ToolCallMode.NATIVE_AUTO,
    val maxAgentSteps: Int = 25,
    val temperature: Float = 0.2f,
    val autoStopMcAfterLoop: Boolean = true,
    val customPrompt: String = ""
) {
    val preset: AiProviderPreset
        get() = BUILTIN_PROVIDERS.firstOrNull { it.id == providerId }
            ?: if (providerId.startsWith("profile_")) AiProviderPreset(providerId, profileName.ifBlank { "Custom profile" }, "Online", ApiProtocol.OPENAI_COMPAT, "", "", emptyList())
            else BUILTIN_PROVIDERS.first()
}

object AiSettingsStore {
    private const val PREFS = "zaphre_ai_settings"

    fun load(context: Context): ActiveAiSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pid = p.getString("active_provider", "gemini") ?: "gemini"
        val profile = profiles(context).firstOrNull { it.id == pid }
        val preset = BUILTIN_PROVIDERS.firstOrNull { it.id == pid }
            ?: if (profile != null) AiProviderPreset(profile.id, profile.name, "Online", ApiProtocol.OPENAI_COMPAT, "", "", emptyList()) else BUILTIN_PROVIDERS.first()
        val baseUrl = p.getString("url_$pid", preset.baseUrl)?.ifBlank { preset.baseUrl } ?: preset.baseUrl
        val key = p.getString("key_$pid", "") ?: ""
        val keyLoad = runCatching { ExtraKeyStore.load(context, pid) }
        val model = p.getString("model_$pid", preset.defaultModel)?.ifBlank { preset.defaultModel } ?: preset.defaultModel
        val tcMode = p.getString("tool_mode_$pid", ToolCallMode.NATIVE_AUTO.name)
            .let { n -> ToolCallMode.entries.firstOrNull { it.name == n } ?: ToolCallMode.NATIVE_AUTO }

        return ActiveAiSettings(
            providerId = preset.id,
            profileName = profile?.name ?: "",
            protocol = preset.protocol,
            baseUrl = normalizeBaseUrl(baseUrl),
            apiKey = key.trim(),
            additionalKeys = keyLoad.getOrDefault(emptyList()),
            additionalKeysUnavailable = keyLoad.isFailure,
            autoSwitchKeys = p.getBoolean("auto_switch_$pid", false),
            askSwitchKeys = p.getBoolean("ask_switch_$pid", false),
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
        check(!s.additionalKeysUnavailable) { "Additional keys are unavailable on this device; encrypted data was not overwritten" }
        ExtraKeyStore.save(context, pid, s.additionalKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("active_provider", pid)
            .putString("url_$pid", normalizeBaseUrl(s.baseUrl))
            .putString("key_$pid", s.apiKey.trim())
            .putBoolean("auto_switch_$pid", s.autoSwitchKeys)
            .putBoolean("ask_switch_$pid", s.askSwitchKeys)
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

    data class Profile(val id: String, val name: String)

    fun profiles(context: Context): List<Profile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("custom_profiles", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getJSONObject(it) }.map { Profile(it.getString("id"), it.getString("name")) }
                .filter { it.id.startsWith("profile_") && it.name.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun saveProfiles(context: Context, profiles: List<Profile>) {
        val array = JSONArray()
        profiles.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("custom_profiles", array.toString()).commit())
    }
    fun createProfile(context: Context, name: String): Profile {
        val clean = name.trim().take(80)
        require(clean.isNotBlank()) { "Enter a profile name" }
        val all = profiles(context)
        require(all.size < 40) { "Profile limit reached" }
        val profile = Profile("profile_" + UUID.randomUUID().toString(), clean)
        saveProfiles(context, all + profile)
        return profile
    }
    fun renameProfile(context: Context, id: String, name: String) {
        val clean = name.trim().take(80)
        require(clean.isNotBlank()) { "Enter a profile name" }
        val all = profiles(context)
        require(all.any { it.id == id }) { "Profile not found" }
        saveProfiles(context, all.map { if (it.id == id) it.copy(name = clean) else it })
    }

    private fun labelId(providerId: String, key: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((providerId + "|" + key).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun keyLabel(context: Context, providerId: String, key: String, fallback: String): String =
        context.getSharedPreferences("peta_key_labels", Context.MODE_PRIVATE).getString(labelId(providerId, key), fallback) ?: fallback
    fun renameKey(context: Context, providerId: String, key: String, name: String) {
        require(key.isNotBlank() && name.isNotBlank()) { "Save the key and enter a name" }
        check(context.getSharedPreferences("peta_key_labels", Context.MODE_PRIVATE).edit().putString(labelId(providerId, key), name.trim().take(80)).commit())
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
                    put("running", s.running)
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
                        running = s.optBoolean("running", false),
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
        val directory = dir(context)
        return directory.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
            .map { File(directory, it.name.removeSuffix(".bak")) }
            .distinctBy { it.name }
            .mapNotNull { f -> runCatching { readSessionFile(f) }.getOrNull() }
            .sortedByDescending { it.updatedAtMs }
    }

    @Synchronized
    fun saveSession(context: Context, session: ChatSession) {
        val o = JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("updatedAtMs", session.updatedAtMs)
            put("messages", JSONArray(session.messages.map { it.toJson() }))
        }
        val atomic = android.util.AtomicFile(File(dir(context), "${session.id}.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(o.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            throw t
        }
    }

    fun deleteSession(context: Context, sessionId: String) {
        runCatching { File(dir(context), "$sessionId.json").delete() }
    }

    private fun readSessionFile(file: File): ChatSession {
        val o = JSONObject(android.util.AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
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
