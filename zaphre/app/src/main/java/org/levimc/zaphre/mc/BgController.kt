package org.levimc.zaphre.mc

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Main-process controller that launches, monitors, hot-retriggers, and force-stops
 * the isolated `:mcre` background Minecraft + Zaphkiel worker (plus optional LeviLauncher v1.7.1 companion).
 */
object BgController {

    fun startRun(
        context: Context,
        timeoutSecs: Int = McTargetManager.getDefaultTimerSecs(context),
        autoStopOnComplete: Boolean = McTargetManager.isAutoStopOnComplete(context),
        keepAliveHot: Boolean = McTargetManager.isKeepAliveHot(context),
        forceReExtract: Boolean = false,
        mode: McTargetManager.LaunchMode = McTargetManager.getLaunchMode(context)
    ): Long {
        val appCtx = context.applicationContext
        val runId = System.currentTimeMillis()
        val clampedTimer = timeoutSecs.coerceIn(5, 1800)

        if (mode == McTargetManager.LaunchMode.LEVI_COMPANION) {
            return startLeviCompanionMonitor(appCtx, runId, clampedTimer)
        }

        // If a previous non-hot :mcre process is lingering, terminate it first for a clean slate
        killRemoteProcessIfAlive(appCtx)

        BgStateStore.clearLog(appCtx)
        BgStateStore.write(
            appCtx,
            BgRunnerSnapshot(
                runId = runId,
                status = RunnerStatus.PREPARING,
                stage = "Spawning background :mcre process...",
                startedAtMs = runId,
                timeoutSecs = clampedTimer,
                remainingSecs = clampedTimer,
                autoStopOnComplete = autoStopOnComplete,
                keepAliveHot = keepAliveHot,
                launchMode = mode.name
            )
        )

        val intent = Intent(appCtx, McBgRunnerService::class.java).apply {
            action = McBgRunnerService.ACTION_START
            putExtra(McBgRunnerService.EXTRA_RUN_ID, runId)
            putExtra(McBgRunnerService.EXTRA_TIMEOUT_SECS, clampedTimer)
            putExtra(McBgRunnerService.EXTRA_AUTO_STOP, autoStopOnComplete)
            putExtra(McBgRunnerService.EXTRA_KEEP_ALIVE, keepAliveHot)
            putExtra(McBgRunnerService.EXTRA_FORCE_EXTRACT, forceReExtract)
            putExtra(McBgRunnerService.EXTRA_LAUNCH_MODE, mode.name)
        }
        ContextCompat.startForegroundService(appCtx, intent)
        return runId
    }

    fun hotRerun(
        context: Context,
        timeoutSecs: Int = 30,
        includeSigGen: Boolean = false,
        stopAfter: Boolean = false
    ): Long {
        val appCtx = context.applicationContext
        val snap = readVerifiedSnapshot(appCtx)
        if (!snap.status.isActive || snap.pid <= 0) {
            return startRun(
                context = appCtx,
                timeoutSecs = timeoutSecs,
                autoStopOnComplete = stopAfter,
                keepAliveHot = !stopAfter
            )
        }
        val runId = System.currentTimeMillis()
        val intent = Intent(appCtx, McBgRunnerService::class.java).apply {
            action = McBgRunnerService.ACTION_HOT_RERUN
            putExtra(McBgRunnerService.EXTRA_RUN_ID, runId)
            putExtra(McBgRunnerService.EXTRA_TIMEOUT_SECS, timeoutSecs.coerceIn(5, 1800))
            putExtra(McBgRunnerService.EXTRA_INCLUDE_SIG, includeSigGen)
            putExtra(McBgRunnerService.EXTRA_AUTO_STOP, stopAfter)
        }
        ContextCompat.startForegroundService(appCtx, intent)
        return runId
    }

    fun stopNow(context: Context) {
        val appCtx = context.applicationContext
        val prev = BgStateStore.read(appCtx)
        runCatching { appCtx.stopService(Intent(appCtx, McBgRunnerService::class.java)) }
        val killed = killRemoteProcessIfAlive(appCtx)
        BgStateStore.appendLog(appCtx, if (killed) "Force-stopped :mcre process (RAM freed)." else "Runner stopped.")
        BgStateStore.write(
            appCtx,
            prev.copy(
                status = RunnerStatus.STOPPED,
                pid = 0,
                rssMb = 0,
                remainingSecs = 0,
                stage = "Stopped (BG MC process terminated)"
            )
        )
    }

    /**
     * Reads snapshot AND cross-checks whether `:mcre` is genuinely still alive via ActivityManager.
     * If `:mcre` died due to a native signal (SIGSEGV/SIGABRT), attaches the exact crash trace immediately.
     */
    fun readVerifiedSnapshot(context: Context): BgRunnerSnapshot {
        val appCtx = context.applicationContext
        val snap = BgStateStore.read(appCtx)
        if (!snap.status.isActive) return snap

        // Grace window of 4s while Android is forking :mcre
        val ageMs = System.currentTimeMillis() - snap.updatedAtMs
        if (snap.pid == 0 && ageMs < 5000L) return snap

        if (snap.launchMode == McTargetManager.LaunchMode.LEVI_COMPANION.name) {
            return snap
        }

        val alivePid = findMcrePid(appCtx)
        if (alivePid == null && ageMs > 2500L) {
            val crash = BgStateStore.readCrashLog(appCtx)
            val updated = if (crash.isNotEmpty()) {
                BgStateStore.appendLog(appCtx, crash)
                snap.copy(
                    status = RunnerStatus.CRASHED,
                    pid = 0,
                    rssMb = 0,
                    remainingSecs = 0,
                    stage = "Native crash in :mcre (see console trace)",
                    error = crash
                )
            } else {
                snap.copy(
                    status = RunnerStatus.STOPPED,
                    pid = 0,
                    rssMb = 0,
                    remainingSecs = 0,
                    stage = "Background :mcre process exited"
                )
            }
            BgStateStore.write(appCtx, updated)
            return updated
        }
        return snap
    }

    /**
     * Suspends until the given `runId` completes, enters HOT_IDLE, crashes, errors, or times out.
     * Used by the autonomous AI RE agent loop!
     */
    suspend fun awaitRun(
        context: Context,
        runId: Long,
        maxWaitSecs: Int,
        stopWhenDone: Boolean = false
    ): BgRunnerSnapshot {
        val appCtx = context.applicationContext
        val deadline = System.currentTimeMillis() + (maxWaitSecs + 12) * 1000L
        delay(600)
        while (System.currentTimeMillis() < deadline) {
            val snap = readVerifiedSnapshot(appCtx)
            if (snap.runId == runId) {
                when (snap.status) {
                    RunnerStatus.COMPLETED,
                    RunnerStatus.STOPPED,
                    RunnerStatus.ERROR,
                    RunnerStatus.CRASHED -> return snap

                    RunnerStatus.HOT_IDLE -> {
                        if (stopWhenDone) stopNow(appCtx)
                        return snap
                    }

                    else -> {
                        if (snap.zaphkielDone && stopWhenDone) {
                            delay(400)
                            stopNow(appCtx)
                            return snap.copy(status = RunnerStatus.COMPLETED)
                        }
                    }
                }
            }
            delay(500)
        }
        stopNow(appCtx)
        return readVerifiedSnapshot(appCtx)
    }

    private fun startLeviCompanionMonitor(context: Context, runId: Long, timeoutSecs: Int): Long {
        if (ZaphkielConfig.getWorkspaceMode(context) != ZaphkielConfig.WorkspaceMode.SHARED_LEVI) {
            ZaphkielConfig.setWorkspaceMode(context, ZaphkielConfig.WorkspaceMode.SHARED_LEVI)
        }
        BgStateStore.clearLog(context)
        val levi = McTargetManager.detectInstalledLeviPackage(context)
        val zLog = ZaphkielConfig.logFile(context)
        runCatching { zLog.delete() }

        if (levi != null) {
            BgStateStore.appendLog(context, "Launching installed ${levi.first} (${levi.second}) — watching kurumi/zaphkiel_log.txt...")
            val launchIntent = context.packageManager.getLaunchIntentForPackage(levi.first)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchIntent != null) {
                runCatching { context.startActivity(launchIntent) }
            }
        } else {
            BgStateStore.appendLog(context, "Watching shared kurumi/zaphkiel_log.txt for ${timeoutSecs}s (launch LeviLauncher v1.7.1)...")
        }

        val startMs = System.currentTimeMillis()
        BgStateStore.write(
            context,
            BgRunnerSnapshot(
                runId = runId,
                status = RunnerStatus.RUNNING_ZAPHKIEL,
                targetLabel = levi?.let { "LeviLauncher ${it.second}" } ?: "LeviLauncher Companion",
                launchMode = McTargetManager.LaunchMode.LEVI_COMPANION.name,
                stage = "Waiting for LeviLauncher Zaphkiel output...",
                startedAtMs = startMs,
                timeoutSecs = timeoutSecs,
                remainingSecs = timeoutSecs
            )
        )

        Thread({
            var lastLen = 0L
            while (true) {
                val cur = BgStateStore.read(context)
                if (cur.runId != runId || cur.status == RunnerStatus.STOPPED) break
                val elapsed = ((System.currentTimeMillis() - startMs) / 1000L).toInt()
                val rem = (timeoutSecs - elapsed).coerceAtLeast(0)
                var done = false
                if (zLog.isFile && zLog.length() != lastLen) {
                    val txt = runCatching { zLog.readText() }.getOrDefault("")
                    if (txt.length > lastLen.toInt()) {
                        txt.substring(lastLen.toInt()).trim().lines().forEach {
                            BgStateStore.appendLog(context, "[Levi/Zaphkiel] $it")
                        }
                    }
                    lastLen = txt.length.toLong()
                    if (txt.contains("run() complete")) done = true
                }
                if (done) {
                    BgStateStore.appendLog(context, "LeviLauncher Zaphkiel run() complete!")
                    BgStateStore.write(
                        context,
                        cur.copy(
                            status = RunnerStatus.COMPLETED,
                            stage = "Completed via LeviLauncher in ${elapsed}s",
                            elapsedSecs = elapsed,
                            remainingSecs = 0,
                            zaphkielDone = true
                        )
                    )
                    // Bring ZaphRE back to front smoothly
                    runCatching {
                        val back = context.packageManager.getLaunchIntentForPackage(context.packageName)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        if (back != null) context.startActivity(back)
                    }
                    break
                }
                if (rem <= 0) {
                    BgStateStore.write(
                        context,
                        cur.copy(
                            status = RunnerStatus.STOPPED,
                            stage = "Companion watch timed out (${timeoutSecs}s)",
                            elapsedSecs = elapsed,
                            remainingSecs = 0
                        )
                    )
                    break
                }
                BgStateStore.write(context, cur.copy(elapsedSecs = elapsed, remainingSecs = rem))
                Thread.sleep(700)
            }
        }, "zaphre-levi-watch").start()

        return runId
    }

    private fun findMcrePid(context: Context): Int? {
        val targetProc = "${context.packageName}:mcre"
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        return am.runningAppProcesses?.firstOrNull { it.processName == targetProc }?.pid
    }

    private fun killRemoteProcessIfAlive(context: Context): Boolean {
        val pid = findMcrePid(context) ?: return false
        if (pid == Process.myPid()) return false
        return runCatching {
            Process.killProcess(pid)
            true
        }.getOrDefault(false)
    }
}
