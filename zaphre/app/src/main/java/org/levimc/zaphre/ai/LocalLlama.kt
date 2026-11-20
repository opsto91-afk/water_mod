package org.levimc.zaphre.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One CPU inference context, shared by chat and the RE agent. No Termux or network is required. */
object LocalLlama {
    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    private external fun prepare(contextSize: Int, threads: Int): Int
    private external fun abortGeneration()
    private external fun clearAbort()
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun unload()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val inference = Dispatchers.IO.limitedParallelism(1)
    val status = MutableStateFlow("No model loaded")
    val models = MutableStateFlow<List<String>>(emptyList())
    private val transfers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transferJob: Job? = null
    @Volatile private var transferCall: Call? = null
    @Volatile private var initialized = false
    val transferring = MutableStateFlow(false)
    val loaded = MutableStateFlow<String?>(null)
    data class Options(val context: Int = 8192, val threads: Int = 4, val maxTokens: Int = 512)
    val options = MutableStateFlow(Options())
    fun restoreOptions(context: Context) {
        val p = context.getSharedPreferences("local_llama_options", Context.MODE_PRIVATE)
        options.value = Options(p.getInt("context", 8192).coerceIn(2048, 16384), p.getInt("threads", 4).coerceIn(1, 8), p.getInt("tokens", 512).coerceIn(128, 2048))
    }
    fun saveOptions(context: Context, value: Options) {
        val v = Options(value.context.coerceIn(2048, 16384), value.threads.coerceIn(1, 8), value.maxTokens.coerceIn(128, 2048))
        context.getSharedPreferences("local_llama_options", Context.MODE_PRIVATE).edit().putInt("context", v.context).putInt("threads", v.threads).putInt("tokens", v.maxTokens).apply()
        options.value = v
        status.value = "Settings saved; reload model to apply context and threads"
    }
    fun cancelInference() { if (initialized) abortGeneration() }
    @Synchronized fun startDownload(context: Context, item: CatalogueItem) {
        if (transferJob?.isActive == true) { status.value = "Another model transfer is running"; return }
        transferring.value = true
        transferJob = transfers.launch {
            try { download(context.applicationContext, item) }
            catch (e: CancellationException) { status.value = "Download cancelled"; throw e }
            catch (e: Exception) {
                if (!currentCoroutineContext().isActive) status.value = "Download cancelled" else status.value = "Download failed: ${e.message}"
            }
            finally { transferring.value = false }
        }
    }
    @Synchronized fun startImport(context: Context, uri: Uri) {
        if (transferJob?.isActive == true) { status.value = "Another model transfer is running"; return }
        transferring.value = true
        transferJob = transfers.launch {
            try { importModel(context.applicationContext, uri) }
            catch (e: CancellationException) { status.value = "Import cancelled"; throw e }
            catch (e: Exception) { status.value = "Import failed: ${e.message}" }
            finally { transferring.value = false }
        }
    }
    fun cancelTransfer() { transferJob?.cancel(); transferCall?.cancel() }

    private var loadedPath: String? = null
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    data class CatalogueItem(val name: String, val url: String)
    val catalogue = listOf(
        CatalogueItem("Qwen2.5 Coder 3B Q4 · coding (~1.93 GB)", "https://huggingface.co/bartowski/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf"),
        CatalogueItem("Qwen2.5 Coder 7B Q4 · coding (~4.68 GB)", "https://huggingface.co/bartowski/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf"),
        CatalogueItem("DeepSeek R1 Distill 1.5B Q4 · reasoning (~1.1 GB)", "https://huggingface.co/tensorblock/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"),
        CatalogueItem("Qwen3 1.7B Q4 · reasoning (~1.1 GB)", "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"),
        CatalogueItem("Qwen3 4B Q4 (~2.5 GB)", "https://huggingface.co/jc-builds/Qwen3-4B-Q4_K_M-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"),
        CatalogueItem("Qwen2.5 Coder 0.5B Q4 (~400 MB)", "https://huggingface.co/bartowski/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-0.5B-Instruct-Q4_K_M.gguf")
    )

    private fun folder(context: Context): File = File(context.filesDir, "local-models").apply { mkdirs() }
    fun refresh(context: Context) { models.value = folder(context).listFiles()?.filter { it.isFile && it.extension.equals("gguf", true) }?.map { it.name }?.sorted() ?: emptyList() }
    private fun checkGguf(file: File) {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 || !magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) throw IOException("Not a valid GGUF file")
        }
    }
    private fun safeName(name: String): String = name.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120).let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
    suspend fun importModel(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val file = File(folder(context), "imported-${System.currentTimeMillis()}.gguf")
        val tmp = File(file.path + ".part")
        try {
            status.value = "Importing model…"
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { out ->
                val buf = ByteArray(128 * 1024); var count = 0L; var last = 0L
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); count += n
                    if (count - last >= 4 * 1024 * 1024) { status.value = "Importing: ${count / 1048576} MB"; last = count } }
            } } ?: throw IOException("Cannot open selected file")
            checkGguf(tmp)
            if (!tmp.renameTo(file)) throw IOException("Could not save model")
            refresh(context); status.value = "Imported ${file.name}"
        } finally { tmp.delete() }
    }
    suspend fun download(context: Context, item: CatalogueItem) = withContext(Dispatchers.IO) {
        require(item.url.startsWith("https://")) { "Only HTTPS URLs are supported" }
        val target = File(folder(context), safeName(item.url.substringBefore('?').substringAfterLast('/')))
        if (target.exists()) { status.value = "Model already downloaded"; return@withContext }
        val part = File(target.path + ".part")
        try {
            status.value = "Downloading ${item.name}…"
            val call = client.newCall(Request.Builder().url(item.url).build())
            transferCall = call
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty download")
                val total = body.contentLength()
                body.byteStream().use { input -> part.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024); var count = 0L; var last = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); count += n
                        if (count - last >= 4 * 1024 * 1024) { status.value = "Downloading: ${count / 1048576} MB" + if (total > 0) " / ${total / 1048576} MB" else ""; last = count }
                    }
                } }
                if (total > 0 && part.length() != total) throw IOException("Incomplete download")
            }
            checkGguf(part)
            if (!part.renameTo(target)) throw IOException("Could not save model")
            refresh(context); status.value = "Downloaded ${target.name}"
        } finally { transferCall = null; part.delete() }
    }
    suspend fun select(context: Context, name: String) = withContext(inference) {
        val file = File(folder(context), name)
        require(file.parentFile?.canonicalFile == folder(context).canonicalFile && file.isFile) { "Model not found" }
        checkGguf(file)
        if (!initialized) { System.loadLibrary("zaphre_llama"); init(context.applicationInfo.nativeLibraryDir); initialized = true }
        if (loadedPath != null) { unload(); loadedPath = null; loaded.value = null }
        status.value = "Loading $name…"
        if (load(file.absolutePath) != 0) throw IOException("Could not load this model architecture")
        if (prepare(options.value.context, options.value.threads) != 0) throw IOException("Could not create model context; try a smaller model")
        loadedPath = file.absolutePath
        loaded.value = name
        status.value = "Ready: $name (CPU)"
    }
    suspend fun unloadModel() = withContext(inference) {
        if (loadedPath != null) { unload(); loadedPath = null; loaded.value = null }
        status.value = "Model unloaded"
    }
    suspend fun deleteModel(context: Context, name: String) = withContext(inference) {
        val file = File(folder(context), name)
        require(file.parentFile?.canonicalFile == folder(context).canonicalFile && file.isFile) { "Model not found" }
        if (loadedPath == file.absolutePath) { unload(); loadedPath = null; loaded.value = null }
        if (!file.delete()) throw IOException("Could not delete model")
        refresh(context); status.value = "Deleted $name"
    }
    suspend fun generate(system: String, user: String, onPartial: ((String) -> Unit)? = null): String = withContext(inference) {
        if (loadedPath == null) throw IOException("Load a GGUF model in AI settings first")
        currentCoroutineContext().ensureActive()
        clearAbort()
        currentCoroutineContext().ensureActive()
        when (processSystemPrompt(system)) {
            0 -> Unit
            1 -> throw IOException("System instructions and tool definitions exceed the loaded model context. Increase context in Models and reload, or shorten custom instructions.")
            else -> { currentCoroutineContext().ensureActive(); throw IOException("Local model failed while processing system instructions. Try reloading the model or reducing context to save RAM.") }
        }
        currentCoroutineContext().ensureActive()
        when (processUserPrompt(user, options.value.maxTokens)) {
            0 -> Unit
            1 -> throw IOException("Chat and tool results do not fit in the loaded context. Start a new chat, reduce output tokens, or increase context and reload.")
            else -> { currentCoroutineContext().ensureActive(); throw IOException("Local model failed while processing the chat. Try reloading the model or reducing context to save RAM.") }
        }
        val result = StringBuilder()
        var lastUpdate = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = generateNextToken() ?: break
            result.append(next)
            val now = System.currentTimeMillis()
            if (onPartial != null && now - lastUpdate >= 250) { onPartial(result.toString()); lastUpdate = now }
        }
        currentCoroutineContext().ensureActive()
        result.toString()
    }
}
