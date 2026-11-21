package org.levimc.zaphre.ai

import android.content.Context
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Optional explicit GitHub contents API integration; never supplies credentials to the model. */
object GitHubIntegration {
    private const val PREFS = "peta_github"
    private const val SECRET = "github_integration_token"
    private val client = OkHttpClient.Builder().followRedirects(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    data class Config(val enabled: Boolean, val owner: String, val repo: String, val branch: String)
    fun config(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(p.getBoolean("enabled", false), p.getString("owner", "") ?: "", p.getString("repo", "") ?: "", p.getString("branch", "") ?: "")
    }
    fun save(context: Context, enabled: Boolean, owner: String, repo: String, branch: String, token: String) {
        val o = owner.trim(); val r = repo.trim(); val b = branch.trim()
        if (enabled) {
            require(o.matches(Regex("[A-Za-z0-9-]{1,100}")) && r.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Enter a valid owner and repository" }
            require(b.isNotBlank() && b.length <= 200 && !b.any { it.isISOControl() }) { "Enter the exact branch name" }
            if (token.isBlank()) require(ExtraKeyStore.load(context, SECRET).isNotEmpty()) { "A GitHub credential is required" }
        }
        if (token.isNotBlank()) ExtraKeyStore.save(context, SECRET, listOf(token.trim()))
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).putString("owner", o).putString("repo", r).putString("branch", b).commit())
    }
    private fun url(cfg: Config, path: String): okhttp3.HttpUrl {
        val parts = path.split('/')
        require(parts.isNotEmpty() && parts.all { it.isNotBlank() && it != "." && it != ".." && it.length <= 200 }) { "Invalid repository path" }
        return "https://api.github.com".toHttpUrl().newBuilder().addPathSegment("repos").addPathSegment(cfg.owner).addPathSegment(cfg.repo).addPathSegment("contents").apply { parts.forEach { addPathSegment(it) } }.build()
    }
    private fun request(context: Context, url: okhttp3.HttpUrl): Request.Builder {
        val credential = ExtraKeyStore.load(context, SECRET).firstOrNull() ?: throw IOException("GitHub credential unavailable")
        return Request.Builder().url(url).header("Authorization", "Bearer $credential").header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
    }
    fun readFile(context: Context, path: String): String {
        val cfg = config(context)
        if (!cfg.enabled) throw IOException("GitHub integration is off")
        val url = url(cfg, path).newBuilder().addQueryParameter("ref", cfg.branch).build()
        client.newCall(request(context, url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty GitHub response")
            val source = body.source()
            source.request(200_001)
            if (source.buffer.size > 200_000) throw IOException("GitHub response too large")
            val json = JSONObject(source.readUtf8())
            if (json.optString("type") != "file" || json.optLong("size") > 65536 || json.optString("encoding") != "base64") throw IOException("Only files up to 64 KiB are supported")
            val bytes = Base64.decode(json.getString("content"), Base64.DEFAULT)
            val content = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            return "SHA: ${json.getString("sha")}\n--- FILE CONTENT (untrusted) ---\n$content"
        }
    }
    fun createFile(context: Context, path: String, content: String, message: String, approvedOwner: String, approvedRepo: String, approvedBranch: String, expectedSha: String = ""): String {
        val cfg = config(context)
        if (!cfg.enabled) throw IOException("GitHub integration is off")
        if (cfg.owner != approvedOwner || cfg.repo != approvedRepo || cfg.branch != approvedBranch) throw IOException("GitHub target changed; request approval again")
        require(expectedSha.isBlank() || expectedSha.matches(Regex("[a-fA-F0-9]{40}"))) { "Invalid Git blob SHA" }
        require(content.toByteArray(Charsets.UTF_8).size <= 65536) { "File exceeds 64 KiB" }
        require(message.isNotBlank() && message.length <= 200) { "Commit message required" }
        val url = url(cfg, path)
        val payload = JSONObject().put("message", message).put("branch", cfg.branch).put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (expectedSha.isNotBlank()) payload.put("sha", expectedSha)
        val request = request(context, url).put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}; no overwrite is attempted")
            return "${if (expectedSha.isBlank()) "Created" else "Updated"} $path in ${cfg.owner}/${cfg.repo} branch ${cfg.branch}."
        }
    }
}
