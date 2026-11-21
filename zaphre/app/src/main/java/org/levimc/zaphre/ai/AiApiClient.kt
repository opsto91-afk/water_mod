package org.levimc.zaphre.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ToolSchema(
    val name: String,
    val description: String,
    val parametersJson: JSONObject
)

sealed class AgentTurnItem {
    data class User(val text: String) : AgentTurnItem()
    data class Assistant(
        val text: String,
        val toolCalls: List<ToolCallSpec> = emptyList(),
        val rawGeminiParts: JSONArray? = null
    ) : AgentTurnItem()
    data class ToolResult(
        val callId: String,
        val toolName: String,
        val output: String
    ) : AgentTurnItem()
}

data class ModelResponse(
    val text: String,
    val toolCalls: List<ToolCallSpec>,
    val rawGeminiParts: JSONArray? = null
)

/**
 * Multi-protocol LLM client supporting:
 *  - Google Gemini v1beta (native generateContent + functionDeclarations + thoughtSignature preservation)
 *  - Anthropic Claude v1/messages (native tool_use / tool_result)
 *  - Universal OpenAI-Compatible chat/completions (OpenRouter, OpenAI, DeepSeek, Groq, xAI, Mistral,
 *    Termux llama.cpp server, Ollama, LM Studio, PocketPal, or any custom endpoint)
 *  - Automatic `<tool_call>{...}</tool_call>` XML parser fallback for local GGUF / non-tool models.
 */
object AiApiClient {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private class RateLimitException(val seconds: Long) : IOException("Provider rate limit reached. Retry after ${seconds}s.")
    private val keyCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun cooldownKey(settings: ActiveAiSettings, key: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest((settings.providerId + "|" + settings.baseUrl + "|" + key).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
    private fun rateLimit(response: Response): RateLimitException {
        val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60L
        return RateLimitException(seconds)
    }

    /** Rotate only on explicit HTTP 429; no tool calls are executed or replayed here. */
    suspend fun chat(
        settings: ActiveAiSettings,
        systemPrompt: String,
        history: List<AgentTurnItem>,
        tools: List<ToolSchema>,
        onPartial: ((String) -> Unit)? = null
    ): ModelResponse {
        val keys = (listOf(settings.apiKey) + settings.additionalKeys).filter { it.isNotBlank() }.distinct()
        if (!settings.autoSwitchKeys || settings.preset.isLocal || keys.size < 2) {
            return chatOnce(settings, systemPrompt, history, tools, onPartial)
        }
        val now = System.currentTimeMillis()
        val available = keys.filter { (keyCooldowns[cooldownKey(settings, it)] ?: 0L) <= now }
        if (available.isEmpty()) throw IOException("All configured keys are cooling down. Please wait before retrying.")
        for (key in available) {
            currentCoroutineContext().ensureActive()
            try {
                return chatOnce(settings.copy(apiKey = key), systemPrompt, history, tools, onPartial)
            } catch (e: RateLimitException) {
                keyCooldowns[cooldownKey(settings, key)] = System.currentTimeMillis() + e.seconds * 1000
            }
        }
        throw IOException("All available keys hit HTTP 429. Wait for their cooldown and retry; quotas may be shared.")
    }

    private suspend fun chatOnce(
        settings: ActiveAiSettings,
        systemPrompt: String,
        history: List<AgentTurnItem>,
        tools: List<ToolSchema>,
        onPartial: ((String) -> Unit)? = null
    ): ModelResponse = withContext(Dispatchers.IO) {
        if (!settings.preset.isLocal && settings.providerId != "custom_openai" && settings.apiKey.isBlank()) {
            throw IOException("API key is empty for ${settings.preset.name}. Open the AI tab and paste your API key.")
        }

        val job = currentCoroutineContext()[Job]
        val usePromptXml = settings.toolCallMode == ToolCallMode.PROMPT_XML
        val effectiveSystem = if (usePromptXml && tools.isNotEmpty()) {
            systemPrompt + "\n\n" + buildXmlToolInstructions(tools)
        } else {
            systemPrompt
        }

        val nativeTools = if (usePromptXml) emptyList() else tools

        val resp = if (settings.providerId == "local_builtin") {
            val localSystem = systemPrompt + if (tools.isNotEmpty()) "\n\n" + buildCompactLocalToolInstructions(tools) else ""
            val localHistory = buildString {
                history.takeLast(8).forEach { turn ->
                    when (turn) {
                        is AgentTurnItem.User -> appendLine("User: ${turn.text.take(4000)}")
                        is AgentTurnItem.Assistant -> {
                            if (turn.text.isNotBlank()) appendLine("Assistant: ${turn.text.take(2000)}")
                            turn.toolCalls.forEach { appendLine("<tool_call>${JSONObject().put("name", it.name).put("arguments", it.args)}</tool_call>") }
                        }
                        is AgentTurnItem.ToolResult -> appendLine("<tool_result name=\"${turn.toolName}\">${turn.output.take(4000)}</tool_result>")
                    }
                }
                append("Assistant:")
            }
            ModelResponse(LocalLlama.generate(localSystem, localHistory, onPartial), emptyList())
        } else when (settings.protocol) {
            ApiProtocol.GEMINI_NATIVE -> callGemini(settings, effectiveSystem, history, nativeTools, job)
            ApiProtocol.ANTHROPIC_NATIVE -> callAnthropic(settings, effectiveSystem, history, nativeTools, job)
            ApiProtocol.OPENAI_COMPAT -> {
                try {
                    callOpenAiCompat(settings, effectiveSystem, history, nativeTools, job)
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (e is RateLimitException) throw e
                    // Auto-fallback to XML prompt tool mode if a local/reasoning model rejects `tools`
                    val msg = e.message.orEmpty().lowercase()
                    if (nativeTools.isNotEmpty() &&
                        ("tool" in msg || "function" in msg || "400" in msg || "not support" in msg)
                    ) {
                        val fallbackSys = systemPrompt + "\n\n" + buildXmlToolInstructions(tools)
                        callOpenAiCompat(settings, fallbackSys, history, emptyList(), job)
                    } else {
                        throw e
                    }
                }
            }
        }

        // If no structured tool call was returned, check for `<tool_call>{...}</tool_call>` blocks
        if (resp.toolCalls.isEmpty() && resp.text.contains("<tool_call>")) {
            val (cleanText, parsedCalls) = extractXmlToolCalls(resp.text)
            if (parsedCalls.isNotEmpty()) {
                return@withContext ModelResponse(cleanText, parsedCalls, resp.rawGeminiParts)
            }
        }
        resp
    }

    suspend fun fetchAvailableModels(settings: ActiveAiSettings): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            when (settings.protocol) {
                ApiProtocol.GEMINI_NATIVE -> {
                    require(settings.apiKey.isNotBlank()) { "Enter your Google Gemini API key first" }
                    val url = "${settings.baseUrl}/models?key=${settings.apiKey}&pageSize=100"
                    val req = Request.Builder().url(url).get().build()
                    httpClient.newCall(req).execute().use { r ->
                        val body = r.body?.string().orEmpty()
                        if (!r.isSuccessful) throw IOException("Gemini HTTP ${r.code}: ${extractApiError(body)}")
                        val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
                        buildList {
                            for (i in 0 until arr.length()) {
                                val m = arr.getJSONObject(i)
                                val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                                if ("generateContent" in methods) {
                                    val name = m.optString("name").removePrefix("models/")
                                    if (name.isNotBlank() && "embedding" !in name) add(name)
                                }
                            }
                        }.sortedDescending()
                    }
                }

                ApiProtocol.ANTHROPIC_NATIVE -> {
                    if (settings.apiKey.isBlank()) return@runCatching settings.preset.suggestedModels
                    val req = Request.Builder()
                        .url("${settings.baseUrl}/models")
                        .header("x-api-key", settings.apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .get()
                        .build()
                    httpClient.newCall(req).execute().use { r ->
                        val body = r.body?.string().orEmpty()
                        if (!r.isSuccessful) throw IOException("Anthropic HTTP ${r.code}: ${extractApiError(body)}")
                        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
                        List(arr.length()) { arr.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
                    }
                }

                ApiProtocol.OPENAI_COMPAT -> {
                    val rb = Request.Builder().url("${settings.baseUrl}/models").get()
                    if (settings.apiKey.isNotBlank()) {
                        rb.header("Authorization", "Bearer ${settings.apiKey}")
                    }
                    httpClient.newCall(rb.build()).execute().use { r ->
                        val body = r.body?.string().orEmpty()
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${extractApiError(body)}")
                        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
                        List(arr.length()) { arr.getJSONObject(it).optString("id") }
                            .filter { it.isNotBlank() }
                            .sorted()
                    }
                }
            }
        }
    }

    // Cancelling the agent must interrupt the in-flight socket, not merely the coroutine
    // waiting on OkHttp's blocking execute/body read. The callback is scoped to this call.
    @OptIn(InternalCoroutinesApi::class)
    private inline fun <T> executeChatRequest(request: Request, job: Job?, block: (Response) -> T): T {
        val call = httpClient.newCall(request)
        val handle = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) call.cancel()
        }
        try {
            job?.ensureActive()
            return call.execute().use(block)
        } catch (e: IOException) {
            // Socket cancellation surfaces as IOException; preserve coroutine cancellation.
            job?.ensureActive()
            throw e
        } finally {
            handle?.dispose()
        }
    }

    private fun callGemini(
        settings: ActiveAiSettings,
        systemPrompt: String,
        history: List<AgentTurnItem>,
        tools: List<ToolSchema>,
        job: Job?
    ): ModelResponse {
        val contents = JSONArray()
        var i = 0
        while (i < history.size) {
            when (val item = history[i]) {
                is AgentTurnItem.User -> {
                    contents.put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", item.text)))
                    })
                    i++
                }
                is AgentTurnItem.Assistant -> {
                    val parts = item.rawGeminiParts ?: JSONArray().apply {
                        if (item.text.isNotBlank()) put(JSONObject().put("text", item.text))
                        for (tc in item.toolCalls) {
                            put(JSONObject().apply {
                                put("functionCall", JSONObject().apply {
                                    put("name", tc.name)
                                    put("args", tc.args)
                                })
                                if (!tc.thoughtSignature.isNullOrEmpty()) {
                                    put("thoughtSignature", tc.thoughtSignature)
                                }
                            })
                        }
                    }
                    if (parts.length() > 0) {
                        contents.put(JSONObject().apply {
                            put("role", "model")
                            put("parts", parts)
                        })
                    }
                    i++
                }
                is AgentTurnItem.ToolResult -> {
                    // Group consecutive ToolResults into a single user functionResponse turn
                    val respParts = JSONArray()
                    while (i < history.size && history[i] is AgentTurnItem.ToolResult) {
                        val tr = history[i] as AgentTurnItem.ToolResult
                        if (tools.isEmpty()) {
                            respParts.put(JSONObject().put("text", "<tool_result name=\"${tr.toolName}\">\n${tr.output}\n</tool_result>"))
                        } else {
                            respParts.put(JSONObject().apply {
                                put("functionResponse", JSONObject().apply {
                                    put("name", tr.toolName)
                                    put("response", JSONObject().put("result", tr.output))
                                })
                            })
                        }
                        i++
                    }
                    contents.put(JSONObject().apply {
                        put("role", "user")
                        put("parts", respParts)
                    })
                }
            }
        }

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", settings.temperature.toDouble())
                put("maxOutputTokens", 8192)
            })
            if (tools.isNotEmpty()) {
                val decls = JSONArray()
                for (t in tools) {
                    decls.put(JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parametersJson)
                    })
                }
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
            }
        }

        val cleanModel = settings.model.removePrefix("models/")
        val url = "${settings.baseUrl}/models/$cleanModel:generateContent?key=${settings.apiKey}"
        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        executeChatRequest(req, job) { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 429) throw rateLimit(resp)
                throw IOException("Gemini API ${resp.code}: ${extractApiError(raw)}")
            }
            val root = JSONObject(raw)
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                throw IOException("Gemini returned no candidates${if (block.isNotEmpty()) " ($block)" else ""}")
            }
            val contentObj = candidates.getJSONObject(0).optJSONObject("content")
            val parts = contentObj?.optJSONArray("parts") ?: JSONArray()

            val textSb = StringBuilder()
            val calls = mutableListOf<ToolCallSpec>()
            for (idx in 0 until parts.length()) {
                val p = parts.getJSONObject(idx)
                val isThought = p.optBoolean("thought", false)
                if (!isThought && p.has("text")) {
                    val t = p.optString("text")
                    if (t.isNotEmpty()) {
                        if (textSb.isNotEmpty()) textSb.append("\n")
                        textSb.append(t)
                    }
                }
                val fc = p.optJSONObject("functionCall")
                if (fc != null) {
                    calls.add(
                        ToolCallSpec(
                            id = "call_${UUID.randomUUID().toString().take(8)}",
                            name = fc.getString("name"),
                            args = fc.optJSONObject("args") ?: JSONObject(),
                            thoughtSignature = p.optString("thoughtSignature").takeIf { it.isNotEmpty() }
                        )
                    )
                }
            }
            return ModelResponse(textSb.toString().trim(), calls, parts)
        }
    }

    private fun callOpenAiCompat(
        settings: ActiveAiSettings,
        systemPrompt: String,
        history: List<AgentTurnItem>,
        tools: List<ToolSchema>,
        job: Job?
    ): ModelResponse {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))

        for (item in history) {
            when (item) {
                is AgentTurnItem.User -> {
                    messages.put(JSONObject().put("role", "user").put("content", item.text))
                }
                is AgentTurnItem.Assistant -> {
                    if (tools.isEmpty()) {
                        val combined = buildString {
                            if (item.text.isNotBlank()) appendLine(item.text)
                            for (tc in item.toolCalls) {
                                append("<tool_call>")
                                append(JSONObject().put("name", tc.name).put("arguments", tc.args).toString())
                                appendLine("</tool_call>")
                            }
                        }.trim()
                        if (combined.isNotEmpty()) {
                            messages.put(JSONObject().put("role", "assistant").put("content", combined))
                        }
                    } else {
                        val m = JSONObject().put("role", "assistant")
                        if (item.text.isNotEmpty()) m.put("content", item.text) else m.put("content", JSONObject.NULL)
                        if (item.toolCalls.isNotEmpty()) {
                            val tcArr = JSONArray()
                            for (tc in item.toolCalls) {
                                tcArr.put(JSONObject().apply {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tc.name)
                                        put("arguments", tc.args.toString())
                                    })
                                })
                            }
                            m.put("tool_calls", tcArr)
                        }
                        messages.put(m)
                    }
                }
                is AgentTurnItem.ToolResult -> {
                    if (tools.isEmpty()) {
                        messages.put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", "<tool_result name=\"${item.toolName}\">\n${item.output}\n</tool_result>")
                        )
                    } else {
                        messages.put(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", item.callId)
                                .put("name", item.toolName)
                                .put("content", item.output)
                        )
                    }
                }
            }
        }

        val payload = JSONObject().apply {
            put("model", settings.model)
            put("messages", messages)
            put("temperature", settings.temperature.toDouble())
            if (tools.isNotEmpty()) {
                val tArr = JSONArray()
                for (t in tools) {
                    tArr.put(JSONObject().apply {
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parametersJson)
                        })
                    })
                }
                put("tools", tArr)
                put("tool_choice", "auto")
            }
        }

        val rb = Request.Builder()
            .url("${settings.baseUrl}/chat/completions")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
        if (settings.apiKey.isNotBlank()) {
            rb.header("Authorization", "Bearer ${settings.apiKey}")
        }
        if ("openrouter.ai" in settings.baseUrl) {
            rb.header("HTTP-Referer", "https://github.com/LiteLDev/LeviLauncher-Android")
            rb.header("X-Title", "ZaphRE")
        }

        executeChatRequest(rb.build(), job) { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 429) throw rateLimit(resp)
                throw IOException("${settings.preset.name} HTTP ${resp.code}: ${extractApiError(raw)}")
            }
            val root = JSONObject(raw)
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                throw IOException("API returned empty choices: ${raw.take(240)}")
            }
            val msg = choices.getJSONObject(0).getJSONObject("message")
            val content = if (msg.isNull("content")) "" else msg.optString("content", "")
            val tcArr = msg.optJSONArray("tool_calls")
            val calls = mutableListOf<ToolCallSpec>()
            if (tcArr != null) {
                for (k in 0 until tcArr.length()) {
                    val tc = tcArr.getJSONObject(k)
                    val fn = tc.optJSONObject("function") ?: continue
                    val argStr = fn.optString("arguments", "{}")
                    val argObj = runCatching { JSONObject(argStr) }.getOrDefault(JSONObject())
                    calls.add(
                        ToolCallSpec(
                            id = tc.optString("id").ifBlank { "call_${UUID.randomUUID().toString().take(8)}" },
                            name = fn.getString("name"),
                            args = argObj
                        )
                    )
                }
            }
            return ModelResponse(content.trim(), calls)
        }
    }

    private fun callAnthropic(
        settings: ActiveAiSettings,
        systemPrompt: String,
        history: List<AgentTurnItem>,
        tools: List<ToolSchema>,
        job: Job?
    ): ModelResponse {
        val messages = JSONArray()
        var i = 0
        while (i < history.size) {
            when (val item = history[i]) {
                is AgentTurnItem.User -> {
                    messages.put(JSONObject().put("role", "user").put("content", item.text))
                    i++
                }
                is AgentTurnItem.Assistant -> {
                    val blocks = JSONArray()
                    if (item.text.isNotBlank()) {
                        blocks.put(JSONObject().put("type", "text").put("text", item.text))
                    }
                    for (tc in item.toolCalls) {
                        blocks.put(JSONObject().apply {
                            put("type", "tool_use")
                            put("id", tc.id)
                            put("name", tc.name)
                            put("input", tc.args)
                        })
                    }
                    if (blocks.length() > 0) {
                        messages.put(JSONObject().put("role", "assistant").put("content", blocks))
                    }
                    i++
                }
                is AgentTurnItem.ToolResult -> {
                    val blocks = JSONArray()
                    while (i < history.size && history[i] is AgentTurnItem.ToolResult) {
                        val tr = history[i] as AgentTurnItem.ToolResult
                        blocks.put(JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", tr.callId)
                            put("content", tr.output)
                        })
                        i++
                    }
                    messages.put(JSONObject().put("role", "user").put("content", blocks))
                }
            }
        }

        val payload = JSONObject().apply {
            put("model", settings.model)
            put("max_tokens", 8192)
            put("system", systemPrompt)
            put("temperature", settings.temperature.toDouble().coerceAtMost(1.0))
            put("messages", messages)
            if (tools.isNotEmpty()) {
                val tArr = JSONArray()
                for (t in tools) {
                    tArr.put(JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", t.parametersJson)
                    })
                }
                put("tools", tArr)
            }
        }

        val req = Request.Builder()
            .url("${settings.baseUrl}/messages")
            .header("x-api-key", settings.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        executeChatRequest(req, job) { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 429) throw rateLimit(resp)
                throw IOException("Claude HTTP ${resp.code}: ${extractApiError(raw)}")
            }
            val root = JSONObject(raw)
            val content = root.optJSONArray("content") ?: JSONArray()
            val textSb = StringBuilder()
            val calls = mutableListOf<ToolCallSpec>()
            for (idx in 0 until content.length()) {
                val b = content.getJSONObject(idx)
                when (b.optString("type")) {
                    "text" -> {
                        val t = b.optString("text")
                        if (t.isNotEmpty()) {
                            if (textSb.isNotEmpty()) textSb.append("\n")
                            textSb.append(t)
                        }
                    }
                    "tool_use" -> {
                        calls.add(
                            ToolCallSpec(
                                id = b.optString("id").ifBlank { "toolu_${UUID.randomUUID().toString().take(8)}" },
                                name = b.getString("name"),
                                args = b.optJSONObject("input") ?: JSONObject()
                            )
                        )
                    }
                }
            }
            return ModelResponse(textSb.toString().trim(), calls)
        }
    }

    // Keep the complete argument names and types, but not the verbose online tool manual.
    // Every token here consumes the local model's limited context on every agent step.
    private fun buildCompactLocalToolInstructions(tools: List<ToolSchema>): String = buildString {
        appendLine("Call a tool using <tool_call>{\"name\":\"tool_name\",\"arguments\":{...}}</tool_call>. Wait for the tool result. Tools (argument:type; required marked *):")
        for (tool in tools) {
            val props = tool.parametersJson.optJSONObject("properties") ?: JSONObject()
            val required = tool.parametersJson.optJSONArray("required")
            val names = props.keys().asSequence().toList()
            append(tool.name).append('(')
            append(names.joinToString(", ") { name ->
                val prop = props.optJSONObject(name)
                val type = prop?.optString("type", "value") ?: "value"
                val itemType = if (type == "array") "<" + (prop?.optJSONObject("items")?.optString("type", "value") ?: "value") + ">" else ""
                val mandatory = required != null && (0 until required.length()).any { required.optString(it) == name }
                name + if (mandatory) "*:" + type + itemType else ":" + type + itemType
            })
            append(") ").appendLine(tool.description.take(140))
        }
        appendLine("Use get_env_status before RE. Do not invent results. For ordinary chat answer directly.")
    }

    private fun buildXmlToolInstructions(tools: List<ToolSchema>): String = buildString {
        appendLine("## Tool Calling Format")
        appendLine("To invoke a tool, emit one or more blocks using EXACT JSON inside `<tool_call>...</tool_call>`:")
        appendLine("<tool_call>{\"name\": \"tool_name\", \"arguments\": {\"arg\": \"value\"}}</tool_call>")
        appendLine("Wait for `<tool_result>` before giving your final answer. Available tools:")
        for (t in tools) {
            append("- ").append(t.name).append(": ").append(t.description)
                .append(" | schema=").appendLine(t.parametersJson.toString())
        }
    }

    private val XML_TOOL_REGEX = Regex("<tool_call>\\s*(\\{[\\s\\S]*?\\})\\s*</tool_call>")

    private fun extractXmlToolCalls(text: String): Pair<String, List<ToolCallSpec>> {
        val calls = mutableListOf<ToolCallSpec>()
        for (m in XML_TOOL_REGEX.findAll(text)) {
            runCatching {
                val obj = JSONObject(m.groupValues[1])
                val name = obj.optString("name")
                val args = obj.optJSONObject("arguments") ?: obj.optJSONObject("args") ?: JSONObject()
                if (name.isNotBlank()) {
                    calls.add(
                        ToolCallSpec(
                            id = "xml_${UUID.randomUUID().toString().take(8)}",
                            name = name,
                            args = args
                        )
                    )
                }
            }
        }
        val clean = text.replace(XML_TOOL_REGEX, "").trim()
        return clean to calls
    }

    private fun extractApiError(body: String): String {
        if (body.isBlank()) return "empty error response"
        return runCatching {
            val j = JSONObject(body)
            j.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: j.optString("message").takeIf { it.isNotBlank() }
                ?: body.take(300)
        }.getOrDefault(body.take(300))
    }
}
