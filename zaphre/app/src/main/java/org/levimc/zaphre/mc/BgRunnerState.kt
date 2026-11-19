package org.levimc.zaphre.mc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RunnerStatus {
    IDLE,
    PREPARING,
    LOADING_MC,
    RUNNING_ZAPHKIEL,
    HOT_IDLE,
    COMPLETED,
    STOPPED,
    ERROR,
    CRASHED;

    val isActive: Boolean
        get() = this == PREPARING || this == LOADING_MC || this == RUNNING_ZAPHKIEL || this == HOT_IDLE
}

data class BgRunnerSnapshot(
    val runId: Long = 0L,
    val status: RunnerStatus = RunnerStatus.IDLE,
    val pid: Int = 0,
    val targetLabel: String = "None",
    val mcVersion: String = "",
    val launchMode: String = McTargetManager.LaunchMode.BG_MINIMAL_LINK.name,
    val stage: String = "Idle — ready to run",
    val startedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val timeoutSecs: Int = 30,
    val elapsedSecs: Int = 0,
    val remainingSecs: Int = 0,
    val autoStopOnComplete: Boolean = true,
    val keepAliveHot: Boolean = false,
    val rssMb: Int = 0,
    val mcBaseHex: String = "",
    val mcSpanHex: String = "",
    val zaphkielBaseHex: String = "",
    val loadedLibs: List<String> = emptyList(),
    val zaphkielDone: Boolean = false,
    val sigGenDone: Boolean = false,
    val error: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("runId", runId)
        put("status", status.name)
        put("pid", pid)
        put("targetLabel", targetLabel)
        put("mcVersion", mcVersion)
        put("launchMode", launchMode)
        put("stage", stage)
        put("startedAtMs", startedAtMs)
        put("updatedAtMs", updatedAtMs)
        put("timeoutSecs", timeoutSecs)
        put("elapsedSecs", elapsedSecs)
        put("remainingSecs", remainingSecs)
        put("autoStopOnComplete", autoStopOnComplete)
        put("keepAliveHot", keepAliveHot)
        put("rssMb", rssMb)
        put("mcBaseHex", mcBaseHex)
        put("mcSpanHex", mcSpanHex)
        put("zaphkielBaseHex", zaphkielBaseHex)
        put("loadedLibs", JSONArray(loadedLibs))
        put("zaphkielDone", zaphkielDone)
        put("sigGenDone", sigGenDone)
        put("error", error)
    }

    companion object {
        fun fromJson(o: JSONObject): BgRunnerSnapshot {
            val libsArr = o.optJSONArray("loadedLibs")
            val libs = if (libsArr != null) List(libsArr.length()) { libsArr.optString(it) } else emptyList()
            val st = runCatching { RunnerStatus.valueOf(o.optString("status", "IDLE")) }
                .getOrDefault(RunnerStatus.IDLE)
            return BgRunnerSnapshot(
                runId = o.optLong("runId", 0L),
                status = st,
                pid = o.optInt("pid", 0),
                targetLabel = o.optString("targetLabel", "None"),
                mcVersion = o.optString("mcVersion", ""),
                launchMode = o.optString("launchMode", ""),
                stage = o.optString("stage", ""),
                startedAtMs = o.optLong("startedAtMs", 0L),
                updatedAtMs = o.optLong("updatedAtMs", 0L),
                timeoutSecs = o.optInt("timeoutSecs", 30),
                elapsedSecs = o.optInt("elapsedSecs", 0),
                remainingSecs = o.optInt("remainingSecs", 0),
                autoStopOnComplete = o.optBoolean("autoStopOnComplete", true),
                keepAliveHot = o.optBoolean("keepAliveHot", false),
                rssMb = o.optInt("rssMb", 0),
                mcBaseHex = o.optString("mcBaseHex", ""),
                mcSpanHex = o.optString("mcSpanHex", ""),
                zaphkielBaseHex = o.optString("zaphkielBaseHex", ""),
                loadedLibs = libs,
                zaphkielDone = o.optBoolean("zaphkielDone", false),
                sigGenDone = o.optBoolean("sigGenDone", false),
                error = o.optString("error", "")
            )
        }
    }
}

/**
 * Cross-process atomic file IPC between the portrait UI process and the isolated :mcre runner process.
 */
object BgStateStore {
    private fun dir(context: Context) = File(context.filesDir, "bg_runner").apply { mkdirs() }
    fun stateFile(context: Context) = File(dir(context), "state.json")
    fun runnerLogFile(context: Context) = File(dir(context), "runner.log")
    fun crashLogFile(context: Context) = File(dir(context), "crash.log")

    @Synchronized
    fun write(context: Context, snapshot: BgRunnerSnapshot) {
        runCatching {
            val dst = stateFile(context)
            val tmp = File(dst.parentFile, "state.json.tmp")
            tmp.writeText(snapshot.copy(updatedAtMs = System.currentTimeMillis()).toJson().toString())
            tmp.renameTo(dst)
        }
    }

    fun read(context: Context): BgRunnerSnapshot {
        val f = stateFile(context)
        if (!f.isFile) return BgRunnerSnapshot()
        return runCatching { BgRunnerSnapshot.fromJson(JSONObject(f.readText())) }
            .getOrDefault(BgRunnerSnapshot())
    }

    fun clearLog(context: Context) {
        runCatching { runnerLogFile(context).writeText("") }
        runCatching { crashLogFile(context).delete() }
    }

    fun appendLog(context: Context, line: String) {
        runCatching {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            runnerLogFile(context).appendText("[$ts] $line\n")
        }
    }

    fun readLogTail(context: Context, maxChars: Int = 12_000): String {
        val f = runnerLogFile(context)
        if (!f.isFile) return ""
        return runCatching {
            val text = f.readText()
            if (text.length <= maxChars) text else "...(earlier lines trimmed)...\n" + text.takeLast(maxChars)
        }.getOrDefault("")
    }

    fun readCrashLog(context: Context): String {
        val f = crashLogFile(context)
        if (!f.isFile) return ""
        return runCatching { f.readText().trim() }.getOrDefault("")
    }
}
