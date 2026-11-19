package org.levimc.zaphre.terminal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.levimc.zaphre.mc.McTargetManager
import org.levimc.zaphre.mc.ZaphkielConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Dual-engine command executor shared by BOTH the interactive CMD tab and the autonomous AI agent:
 *
 * 1. BUILTIN_SH (`/system/bin/sh` + Android toybox): works immediately on every Android phone with
 *    zero setup (`grep -rnE`, `awk`, `sed`, `head`, `tail`, `wc`, `sort`, `uniq`, `strings`, `xxd`, `od`,
 *    `find`, `cut`, `tr`, `diff`, `ls`, `stat`, `cat`).
 * 2. TERMUX_BRIDGE (`http://127.0.0.1:8765` authenticated local Termux daemon + RUN_COMMAND helper):
 *    gives both the user and the AI full access to real Termux packages (`python3`, `rg`, `readelf`,
 *    `llvm-objdump`, `clang++`, `jq`, `radare2`, `git`, `llama-server`) with synchronous stdout/stderr!
 */
object ShellExecutor {

    private const val PREFS = "zaphre_terminal_prefs"
    private const val KEY_ENGINE = "preferred_engine"
    private const val KEY_TERMUX_URL = "termux_bridge_url"
    private const val KEY_TERMUX_TOKEN = "termux_bridge_token"

    enum class Engine(val label: String) {
        AUTO("Auto (Termux if online, else Android Shell)"),
        BUILTIN_SH("Built-in Android Shell (toybox)"),
        TERMUX_BRIDGE("Termux Bridge (python3 / objdump / rg)")
    }

    data class CmdResult(
        val command: String,
        val engineUsed: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean = false
    ) {
        fun formattedOutput(maxChars: Int = 20_000): String {
            val combined = buildString {
                if (stdout.isNotBlank()) append(stdout.trimEnd())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("[stderr]\n").append(stderr.trimEnd())
                }
                if (isEmpty()) append("(no output)")
                if (timedOut) append("\n[TIMEOUT]")
                append("\n[exit=$exitCode · ${durationMs}ms · $engineUsed]")
            }
            return if (combined.length <= maxChars) {
                combined
            } else {
                combined.take(maxChars / 2) +
                    "\n\n... [${combined.length - maxChars} chars omitted] ...\n\n" +
                    combined.takeLast(maxChars / 2)
            }
        }
    }

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    fun getPreferredEngine(context: Context): Engine {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, Engine.AUTO.name)
        return Engine.entries.firstOrNull { it.name == raw } ?: Engine.AUTO
    }

    fun setPreferredEngine(context: Context, engine: Engine) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENGINE, engine.name).apply()
    }

    fun getTermuxUrl(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TERMUX_URL, "http://127.0.0.1:8765")
            ?.trimEnd('/')
            .orEmpty()
            .ifBlank { "http://127.0.0.1:8765" }
    }

    fun setTermuxUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TERMUX_URL, url.trim().trimEnd('/')).apply()
    }

    fun getTermuxToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var tok = prefs.getString(KEY_TERMUX_TOKEN, null)
        if (tok.isNullOrBlank()) {
            tok = "zre_" + UUID.randomUUID().toString().replace("-", "").take(20)
            prefs.edit().putString(KEY_TERMUX_TOKEN, tok).apply()
        }
        return tok
    }

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    suspend fun pingTermuxBridge(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${getTermuxUrl(context)}/health")
                .header("X-ZaphRE-Token", getTermuxToken(context))
                .get()
                .build()
            probeClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun execute(
        context: Context,
        command: String,
        engine: Engine = getPreferredEngine(context),
        timeoutSecs: Int = 30
    ): CmdResult = withContext(Dispatchers.IO) {
        val clampedTimeout = timeoutSecs.coerceIn(2, 300)
        val ws = ZaphkielConfig.ensureWorkspaceLayout(context)
        val headers = ZaphkielConfig.headersDir(context)
        val mcLibPath = runCatching {
            val target = McTargetManager.getSelectedTarget(context)
            if (target != null) {
                val d = when (target.kind) {
                    McTargetManager.TargetKind.INSTALLED_MC -> File(context.filesDir, "mc_lib_cache/installed_mc_arm64")
                    else -> File(target.dirPath, "arm64-v8a")
                }
                File(d, "libminecraftpe.so").takeIf { it.isFile }?.absolutePath.orEmpty()
            } else ""
        }.getOrDefault("")

        val envMap = mapOf(
            "WORKSPACE" to ws.absolutePath,
            "KURUMI" to ws.absolutePath,
            "HEADERS" to headers.absolutePath,
            "MC_LIB" to mcLibPath
        )

        val useTermux = when (engine) {
            Engine.TERMUX_BRIDGE -> true
            Engine.BUILTIN_SH -> false
            Engine.AUTO -> pingTermuxBridge(context)
        }

        if (useTermux) {
            val tRes = runViaTermuxBridge(context, command, ws.absolutePath, envMap, clampedTimeout)
            if (tRes != null) return@withContext tRes
            if (engine == Engine.TERMUX_BRIDGE) {
                return@withContext CmdResult(
                    command = command,
                    engineUsed = "Termux Bridge (offline)",
                    exitCode = 127,
                    stdout = "",
                    stderr = "Termux bridge is not running on ${getTermuxUrl(context)}. " +
                        "Tap 'Termux Setup' in CMD tab to start it in 1 command, or switch engine to Auto/Android Shell.",
                    durationMs = 0
                )
            }
        }

        runViaBuiltinSh(command, ws, envMap, clampedTimeout)
    }

    private fun runViaBuiltinSh(
        command: String,
        cwd: File,
        extraEnv: Map<String, String>,
        timeoutSecs: Int
    ): CmdResult {
        val start = System.currentTimeMillis()
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            pb.directory(cwd)
            val env = pb.environment()
            val curPath = env["PATH"].orEmpty()
            if ("/system/bin" !in curPath) {
                env["PATH"] = "/system/bin:/system/xbin:$curPath"
            }
            env.putAll(extraEnv)

            val proc = pb.start()
            proc.outputStream.close()

            val outBuf = StringBuilder()
            val errBuf = StringBuilder()
            val tOut = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            if (outBuf.length < 64_000) outBuf.append(buf, 0, n)
                        }
                    }
                }
            }.apply { start() }
            val tErr = Thread {
                runCatching {
                    proc.errorStream.bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            if (errBuf.length < 32_000) errBuf.append(buf, 0, n)
                        }
                    }
                }
            }.apply { start() }

            val finished = proc.waitFor(timeoutSecs.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
            }
            tOut.join(1000)
            tErr.join(1000)
            val code = if (finished) proc.exitValue() else 124
            CmdResult(
                command = command,
                engineUsed = "android-sh",
                exitCode = code,
                stdout = outBuf.toString(),
                stderr = errBuf.toString(),
                durationMs = System.currentTimeMillis() - start,
                timedOut = !finished
            )
        } catch (t: Throwable) {
            CmdResult(
                command = command,
                engineUsed = "android-sh",
                exitCode = -1,
                stdout = "",
                stderr = t.message ?: t.javaClass.simpleName,
                durationMs = System.currentTimeMillis() - start
            )
        }
    }

    private fun runViaTermuxBridge(
        context: Context,
        command: String,
        cwd: String,
        envMap: Map<String, String>,
        timeoutSecs: Int
    ): CmdResult? {
        val start = System.currentTimeMillis()
        return runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout((timeoutSecs + 5).toLong(), TimeUnit.SECONDS)
                .build()
            val bodyJson = JSONObject().apply {
                put("cmd", command)
                put("cwd", cwd)
                put("timeout", timeoutSecs)
                put("env", JSONObject(envMap))
            }
            val req = Request.Builder()
                .url("${getTermuxUrl(context)}/exec")
                .header("X-ZaphRE-Token", getTermuxToken(context))
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val j = JSONObject(resp.body?.string().orEmpty())
                CmdResult(
                    command = command,
                    engineUsed = "termux",
                    exitCode = j.optInt("exit_code", 0),
                    stdout = j.optString("stdout", ""),
                    stderr = j.optString("stderr", ""),
                    durationMs = System.currentTimeMillis() - start,
                    timedOut = j.optBoolean("timed_out", false)
                )
            }
        }.getOrNull()
    }

    /**
     * Generates a clean, localhost-only (127.0.0.1), token-authenticated Termux bridge script
     * so ZaphRE + AI can execute real Termux tools (python3, rg, readelf, llvm-objdump, clang++).
     */
    fun generateTermuxBridgeCommand(context: Context): String {
        val token = getTermuxToken(context)
        val ws = ZaphkielConfig.getWorkspaceDir(context).absolutePath
        val port = getTermuxUrl(context).substringAfterLast(':').toIntOrNull() ?: 8765
        return """
cat > ~/zaphre_bridge.py << 'PY'
import http.server, json, os, subprocess
TOKEN = "$token"
DEFAULT_CWD = "$ws"

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _auth(self): return self.headers.get("X-ZaphRE-Token") == TOKEN
    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)
    def do_GET(self):
        if not self._auth(): return self._send(403, {"error": "forbidden"})
        self._send(200, {"ok": True, "service": "zaphre-termux"})
    def do_POST(self):
        if not self._auth(): return self._send(403, {"error": "forbidden"})
        n = int(self.headers.get("Content-Length", "0"))
        req = json.loads(self.rfile.read(n).decode())
        cwd = req.get("cwd") or DEFAULT_CWD
        if not os.path.isdir(cwd): cwd = os.path.expanduser("~")
        env = os.environ.copy()
        env.update({k: str(v) for k, v in (req.get("env") or {}).items()})
        try:
            r = subprocess.run(["bash", "-lc", req["cmd"]], cwd=cwd, env=env,
                               capture_output=True, text=True, timeout=int(req.get("timeout", 30)))
            self._send(200, {"exit_code": r.returncode, "stdout": r.stdout[-64000:], "stderr": r.stderr[-32000:], "timed_out": False})
        except subprocess.TimeoutExpired as e:
            self._send(200, {"exit_code": 124, "stdout": (e.stdout or "")[-32000:], "stderr": (e.stderr or "")[-16000:], "timed_out": True})

print("ZaphRE Termux Bridge listening on 127.0.0.1:$port (workspace: $ws)")
http.server.ThreadingHTTPServer(("127.0.0.1", $port), H).serve_forever()
PY
termux-setup-storage 2>/dev/null; python3 ~/zaphre_bridge.py
        """.trimIndent()
    }

    /** Try auto-starting the bridge inside Termux via com.termux.RUN_COMMAND if allow-external-apps=true */
    fun launchBridgeViaTermuxIntent(context: Context): Boolean {
        return runCatching {
            val scriptFile = File(ZaphkielConfig.ensureWorkspaceLayout(context), ".start_termux_bridge.sh")
            scriptFile.writeText("#!/data/data/com.termux/files/usr/bin/bash\n" + generateTermuxBridgeCommand(context) + "\n")
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(scriptFile.absolutePath))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            context.startService(intent)
            true
        }.getOrDefault(false)
    }

    /** Ready-to-paste Termux command to spin up a local GGUF model via llama.cpp server */
    fun generateLocalAiTermuxCommand(ggufPath: String = "~/models/model.gguf", port: Int = 8080): String {
        return "pkg install -y llama-cpp && llama-server -m $ggufPath --host 127.0.0.1 --port $port -c 8192 --jinja"
    }
}
