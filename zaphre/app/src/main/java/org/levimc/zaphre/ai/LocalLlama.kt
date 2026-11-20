package org.levimc.zaphre.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One CPU inference context, shared by chat and the RE agent. No Termux or network is required. */
object LocalLlama {
    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    private external fun prepare(): Int
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun unload()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val inference = Dispatchers.IO.limitedParallelism(1)
    val status = MutableStateFlow("No model loaded")
    val models = MutableStateFlow<List<String>>(emptyList())
    private var initialized = false
    private var loadedPath: String? = null
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    data class CatalogueItem(val name: String, val url: String)
    val catalogue = listOf(
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
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: throw IOException("Cannot open selected file")
            checkGguf(tmp)
            if (!tmp.renameTo(file)) throw IOException("Could not save model")
            refresh(context); status.value = "Imported ${file.name}"
        } finally { tmp.delete() }
    }
    suspend fun download(context: Context, item: CatalogueItem) = withContext(Dispatchers.IO) {
        val target = File(folder(context), safeName(item.url.substringAfterLast('/')))
        if (target.exists()) { status.value = "Model already downloaded"; return@withContext }
        val part = File(target.path + ".part")
        try {
            status.value = "Downloading ${item.name}…"
            client.newCall(Request.Builder().url(item.url).build()).execute().use { response ->
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
        } finally { part.delete() }
    }
    suspend fun select(context: Context, name: String) = withContext(inference) {
        val file = File(folder(context), name)
        require(file.parentFile?.canonicalFile == folder(context).canonicalFile && file.isFile) { "Model not found" }
        checkGguf(file)
        if (!initialized) { System.loadLibrary("zaphre_llama"); init(context.applicationInfo.nativeLibraryDir); initialized = true }
        if (loadedPath != null) { unload(); loadedPath = null }
        status.value = "Loading $name…"
        if (load(file.absolutePath) != 0) throw IOException("Could not load this model architecture")
        if (prepare() != 0) throw IOException("Could not create model context; try a smaller model")
        loadedPath = file.absolutePath
        status.value = "Ready: $name (CPU)"
    }
    suspend fun generate(system: String, user: String): String = withContext(inference) {
        if (loadedPath == null) throw IOException("Load a GGUF model in AI settings first")
        currentCoroutineContext().ensureActive()
        if (processSystemPrompt(system) != 0) throw IOException("System prompt exceeds local model context")
        currentCoroutineContext().ensureActive()
        if (processUserPrompt(user, 512) != 0) throw IOException("Prompt too long or local inference failed")
        val result = StringBuilder()
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = generateNextToken() ?: break
            result.append(next)
        }
        result.toString()
    }
}
