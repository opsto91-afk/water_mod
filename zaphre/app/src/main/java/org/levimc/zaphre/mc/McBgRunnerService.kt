package org.levimc.zaphre.mc

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import org.fmod.FMOD
import org.levimc.zaphre.MainActivity
import org.levimc.zaphre.ZaphReApp
import org.levimc.zaphre.bridge.NativeBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs inside isolated Android process `:mcre` (completely separate VM & address space from Chat UI).
 *
 * - Never opens a landscape game Activity — keeps the user's phone in normal portrait chat view!
 * - Loads LeviLauncher v1.7.1 compatible Minecraft arm64-v8a native dependency graph + libZaphkiel.so
 * - Streams live PID, RSS memory, libminecraftpe.so base VA, countdown timer & zaphkiel_log.txt status
 * - Supports hot re-runs (`zaphkiel_init` + siggen in ~1s without reloading 350MB libminecraftpe.so)
 * - Strictly enforces auto-stop timer (10s / 15s / 30s / 1m / 5m / custom) and kills its own PID cleanly
 *   so 100% of Minecraft's RAM is reclaimed immediately.
 */
class McBgRunnerService : Service() {

    private var workerThread: Thread? = null
    private val stopFlag = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var mcAlreadyLoaded = false
    @Volatile private var zaphkielAlreadyLoaded = false
    @Volatile private var currentSnapshot = BgRunnerSnapshot()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                BgStateStore.appendLog(this, "Stop requested — terminating :mcre (PID ${Process.myPid()})")
                publish(
                    currentSnapshot.copy(
                        status = RunnerStatus.STOPPED,
                        stage = "Stopped by user/agent",
                        remainingSecs = 0
                    )
                )
                shutdownAndKillProcess()
                return START_NOT_STICKY
            }

            ACTION_HOT_RERUN -> {
                val runId = intent?.getLongExtra(EXTRA_RUN_ID, System.currentTimeMillis()) ?: System.currentTimeMillis()
                val timeoutSecs = (intent?.getIntExtra(EXTRA_TIMEOUT_SECS, 30) ?: 30).coerceIn(5, 1800)
                val includeSig = intent?.getBooleanExtra(EXTRA_INCLUDE_SIG, false) ?: false
                val stopAfter = intent?.getBooleanExtra(EXTRA_AUTO_STOP, false) ?: false
                if (mcAlreadyLoaded && zaphkielAlreadyLoaded) {
                    startHotRerun(runId, timeoutSecs, includeSig, stopAfter)
                    return START_NOT_STICKY
                }
                // Fall through to full start if not loaded yet
            }
        }

        val runId = intent?.getLongExtra(EXTRA_RUN_ID, System.currentTimeMillis()) ?: System.currentTimeMillis()
        val timeoutSecs = (intent?.getIntExtra(EXTRA_TIMEOUT_SECS, 30) ?: 30).coerceIn(5, 1800)
        val autoStop = intent?.getBooleanExtra(EXTRA_AUTO_STOP, true) ?: true
        val keepAlive = intent?.getBooleanExtra(EXTRA_KEEP_ALIVE, false) ?: false
        val forceReExtract = intent?.getBooleanExtra(EXTRA_FORCE_EXTRACT, false) ?: false
        val modeName = intent?.getStringExtra(EXTRA_LAUNCH_MODE)
            ?: McTargetManager.getLaunchMode(this).name
        val mode = McTargetManager.LaunchMode.entries.firstOrNull { it.name == modeName }
            ?: McTargetManager.LaunchMode.BG_MINIMAL_LINK

        promoteForeground("Starting MC RE session (${timeoutSecs}s timer)...")
        acquireWakeLock(timeoutSecs)
        startFullSession(runId, timeoutSecs, autoStop, keepAlive, forceReExtract, mode)
        return START_NOT_STICKY
    }

    private fun startFullSession(
        runId: Long,
        timeoutSecs: Int,
        autoStop: Boolean,
        keepAlive: Boolean,
        forceReExtract: Boolean,
        mode: McTargetManager.LaunchMode
    ) {
        stopFlag.set(false)
        val startMs = System.currentTimeMillis()
        BgStateStore.clearLog(this)
        NativeBridge.installCrashHandler(BgStateStore.crashLogFile(this).absolutePath)
        NativeBridge.setStage("initializing_service")

        workerThread = Thread({
            val loadedNames = mutableListOf<String>()
            try {
                val target = McTargetManager.getSelectedTarget(this)
                    ?: throw IllegalStateException(
                        "No Minecraft found. Install Minecraft (1.21.80+ / 26.x) or import an APK/libminecraftpe.so in BG Run tab."
                    )

                log("Session #$runId started in :mcre (PID=${Process.myPid()}, mode=${mode.name}, timer=${timeoutSecs}s)")
                publish(
                    BgRunnerSnapshot(
                        runId = runId,
                        status = RunnerStatus.PREPARING,
                        pid = Process.myPid(),
                        targetLabel = target.label,
                        mcVersion = target.versionName,
                        launchMode = mode.name,
                        stage = "Preparing Minecraft native libraries...",
                        startedAtMs = startMs,
                        timeoutSecs = timeoutSecs,
                        elapsedSecs = 0,
                        remainingSecs = timeoutSecs,
                        autoStopOnComplete = autoStop,
                        keepAliveHot = keepAlive,
                        rssMb = NativeBridge.getRssMb()
                    )
                )

                NativeBridge.setStage("preparing_runtime_libs")
                val prepared = McTargetManager.prepareRuntime(this, target, forceReExtract) { msg ->
                    log(msg)
                }

                // Clear stale zaphkiel_log.txt before triggering so completion detection is exact
                val zLog = ZaphkielConfig.logFile(this)
                runCatching { zLog.delete() }
                val sigTargetsText = runCatching { ZaphkielConfig.sigTargetsFile(this).readText() }.getOrDefault("")
                val expectSigGen = sigTargetsText.lineSequence()
                    .map { it.trim() }
                    .any { it.startsWith("fn<") || it.startsWith("add<") }

                NativeBridge.setStage("loading_fmod_shim")
                runCatching { FMOD.init(applicationContext) }

                publish(
                    currentSnapshot.copy(
                        status = RunnerStatus.LOADING_MC,
                        stage = "Loading ${prepared.orderedMcLibs.size} Minecraft libraries...",
                        rssMb = NativeBridge.getRssMb()
                    )
                )
                updateNotification("Loading MC ${target.versionName.ifBlank { "ELF" }} libraries...")

                if (!mcAlreadyLoaded) {
                    // Optionally preload LeviLauncher v1.7.1 preloader if running full Levi runtime
                    if (mode == McTargetManager.LaunchMode.BG_LEVI_RUNTIME && prepared.preloaderFile != null) {
                        NativeBridge.setStage("load:${prepared.preloaderFile.name}")
                        val err = NativeBridge.dlopen(prepared.preloaderFile.absolutePath, global = true, lazy = false)
                        if (err.isEmpty()) {
                            loadedNames.add(prepared.preloaderFile.name)
                            log("Loaded ${prepared.preloaderFile.name} (Levi v1.7.1 preloader)")
                        } else {
                            log("Note: preloader skipped ($err)")
                        }
                    }

                    for ((idx, lib) in prepared.orderedMcLibs.withIndex()) {
                        if (stopFlag.get()) return@Thread
                        val stepStart = System.currentTimeMillis()
                        NativeBridge.setStage("load:${lib.name}")
                        publish(
                            currentSnapshot.copy(
                                stage = "Loading [${idx + 1}/${prepared.orderedMcLibs.size}] ${lib.name}...",
                                loadedLibs = loadedNames.toList(),
                                rssMb = NativeBridge.getRssMb()
                            )
                        )

                        val isMc = lib.name == "libminecraftpe.so"
                        val loadErr = if (mode == McTargetManager.LaunchMode.BG_LEVI_RUNTIME && !isMc) {
                            runCatching {
                                System.load(lib.absolutePath)
                                ""
                            }.getOrElse { t ->
                                // Fallback to pure C++ dlopen if JNI_OnLoad expects Activity context
                                log("System.load(${lib.name}) note: ${t.message} -> falling back to dlopen")
                                NativeBridge.dlopen(lib.absolutePath, global = true, lazy = false)
                            }
                        } else if (mode == McTargetManager.LaunchMode.BG_LEVI_RUNTIME && isMc) {
                            runCatching {
                                System.load(lib.absolutePath)
                                ""
                            }.getOrElse { t ->
                                log("System.load(libminecraftpe.so) note: ${t.message} -> trying dlopen")
                                NativeBridge.dlopen(lib.absolutePath, global = true, lazy = false)
                            }
                        } else {
                            // BG_MINIMAL_LINK: clean RTLD_GLOBAL | RTLD_NOW (relocates all RTTI & vtables without JNI_OnLoad)
                            val e1 = NativeBridge.dlopen(lib.absolutePath, global = true, lazy = false)
                            if (e1.isNotEmpty()) {
                                log("RTLD_NOW warning on ${lib.name}: $e1 -> retrying RTLD_LAZY")
                                NativeBridge.dlopen(lib.absolutePath, global = true, lazy = true)
                            } else {
                                ""
                            }
                        }

                        val dt = System.currentTimeMillis() - stepStart
                        if (loadErr.isNotEmpty()) {
                            if (isMc) {
                                throw IllegalStateException("Failed to load libminecraftpe.so: $loadErr")
                            } else {
                                log("WARN: ${lib.name} failed (${dt}ms): $loadErr")
                            }
                        } else {
                            loadedNames.add(lib.name)
                            log("Loaded ${lib.name} in ${dt}ms")
                        }
                    }
                    mcAlreadyLoaded = true
                }

                val mcMod = NativeBridge.getModuleInfo("libminecraftpe.so")
                val mcBase = mcMod?.first.orEmpty()
                val mcSpan = mcMod?.second.orEmpty()
                log("libminecraftpe.so mapped @ base=$mcBase span=$mcSpan (RSS=${NativeBridge.getRssMb()} MB)")

                // Load extra native RE mods if any
                for (extra in prepared.extraModFiles) {
                    NativeBridge.setStage("load_mod:${extra.name}")
                    val err = NativeBridge.dlopen(extra.absolutePath, global = false, lazy = false)
                    if (err.isEmpty()) {
                        loadedNames.add(extra.name)
                        log("Loaded extra RE mod: ${extra.name}")
                    } else {
                        log("Extra mod ${extra.name} failed: $err")
                    }
                }

                // Load libZaphkiel.so
                var zaphBase = ""
                val zaphFile = prepared.zaphkielStagedFile
                if (zaphFile != null) {
                    log(
                        "Workspace: ${prepared.workspaceMode.label} -> ${ZaphkielConfig.getWorkspacePathSlash(this)}" +
                            (if (prepared.redirectCount > 0) " (redirected ${prepared.redirectCount} paths)" else "")
                    )
                    NativeBridge.setStage("load_zaphkiel")
                    val zRes = NativeBridge.loadZaphkiel(zaphFile.absolutePath)
                    if (zRes.startsWith("ERROR:")) {
                        throw IllegalStateException("Zaphkiel load failed: $zRes")
                    }
                    zaphkielAlreadyLoaded = true
                    loadedNames.add("libZaphkiel.so")
                    zaphBase = NativeBridge.getModuleInfo("Zaphkiel")?.first.orEmpty()
                    log("Zaphkiel active ($zRes)")
                } else {
                    log("NOTE: libZaphkiel.so not imported yet — holding MC in memory until timer ends.")
                }

                NativeBridge.setStage("monitoring_zaphkiel")
                publish(
                    currentSnapshot.copy(
                        status = RunnerStatus.RUNNING_ZAPHKIEL,
                        stage = if (zaphFile != null) "Zaphkiel analyzing libminecraftpe.so..." else "MC loaded in BG (no Zaphkiel)",
                        mcBaseHex = mcBase,
                        mcSpanHex = mcSpan,
                        zaphkielBaseHex = zaphBase,
                        loadedLibs = loadedNames.toList(),
                        rssMb = NativeBridge.getRssMb()
                    )
                )

                monitorUntilDoneOrTimeout(
                    startMs = startMs,
                    timeoutSecs = timeoutSecs,
                    hasZaphkiel = zaphFile != null,
                    expectSigGen = expectSigGen,
                    autoStop = autoStop,
                    keepAlive = keepAlive
                )
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                log("ERROR: $msg")
                publish(
                    currentSnapshot.copy(
                        status = RunnerStatus.ERROR,
                        stage = "Failed: $msg",
                        loadedLibs = loadedNames.toList(),
                        error = msg,
                        remainingSecs = 0
                    )
                )
                shutdownAndKillProcess()
            }
        }, "zaphre-mcre-worker").also { it.start() }
    }

    private fun startHotRerun(runId: Long, timeoutSecs: Int, includeSig: Boolean, stopAfter: Boolean) {
        stopFlag.set(true)
        workerThread?.join(800)
        stopFlag.set(false)

        val startMs = System.currentTimeMillis()
        runCatching { ZaphkielConfig.logFile(this).delete() }
        log("Hot re-run #$runId triggered (includeSig=$includeSig, timer=${timeoutSecs}s)")

        workerThread = Thread({
            NativeBridge.setStage("hot_retrigger_zaphkiel")
            val ok = NativeBridge.retriggerZaphkiel(includeSig)
            if (!ok) {
                log("ERROR: hot retrigger symbol unavailable")
                publish(
                    currentSnapshot.copy(
                        runId = runId,
                        status = RunnerStatus.ERROR,
                        stage = "Hot retrigger failed",
                        error = "zaphkiel_init unavailable"
                    )
                )
                return@Thread
            }
            publish(
                currentSnapshot.copy(
                    runId = runId,
                    status = RunnerStatus.RUNNING_ZAPHKIEL,
                    stage = "Hot re-run: Zaphkiel analyzing...",
                    startedAtMs = startMs,
                    timeoutSecs = timeoutSecs,
                    elapsedSecs = 0,
                    remainingSecs = timeoutSecs,
                    zaphkielDone = false,
                    sigGenDone = false,
                    autoStopOnComplete = stopAfter,
                    keepAliveHot = !stopAfter,
                    error = ""
                )
            )
            monitorUntilDoneOrTimeout(
                startMs = startMs,
                timeoutSecs = timeoutSecs,
                hasZaphkiel = true,
                expectSigGen = includeSig,
                autoStop = stopAfter,
                keepAlive = !stopAfter
            )
        }, "zaphre-hot-worker").also { it.start() }
    }

    private fun monitorUntilDoneOrTimeout(
        startMs: Long,
        timeoutSecs: Int,
        hasZaphkiel: Boolean,
        expectSigGen: Boolean,
        autoStop: Boolean,
        keepAlive: Boolean
    ) {
        val zLog = ZaphkielConfig.logFile(this)
        val sigJson = ZaphkielConfig.signaturesJsonFile(this)
        var lastZLogLen = 0L
        var zDone = false
        var sDone = !expectSigGen
        var doneDetectedMs = 0L

        while (!stopFlag.get()) {
            val now = System.currentTimeMillis()
            val elapsedSecs = ((now - startMs) / 1000L).toInt().coerceAtLeast(0)
            val remainingSecs = (timeoutSecs - elapsedSecs).coerceAtLeast(0)

            // Stream any newly written Zaphkiel log lines into runner log
            if (hasZaphkiel && zLog.isFile && zLog.length() != lastZLogLen) {
                runCatching {
                    val content = zLog.readText()
                    if (content.length > lastZLogLen.toInt()) {
                        val delta = content.substring(lastZLogLen.toInt()).trim()
                        if (delta.isNotEmpty()) {
                            delta.lines().forEach { line -> log("[Zaphkiel] $line") }
                        }
                    }
                    lastZLogLen = content.length.toLong()
                    if (!zDone && content.contains("run() complete")) {
                        zDone = true
                        doneDetectedMs = now
                        log("Zaphkiel reported: run() complete!")
                    }
                    if (content.contains("ERROR: libminecraftpe.so not in /proc/self/maps")) {
                        log("Zaphkiel ERROR: libminecraftpe.so missing in /proc/self/maps")
                    }
                }
            }

            if (!sDone && sigJson.isFile && sigJson.lastModified() >= startMs - 1000L && sigJson.length() > 2) {
                sDone = true
                if (doneDetectedMs == 0L) doneDetectedMs = now
                log("Signature generator wrote signatures.json (${sigJson.length()} bytes)")
            }

            val rss = NativeBridge.getRssMb()
            val bothDone = hasZaphkiel && zDone && (sDone || (doneDetectedMs > 0 && now - doneDetectedMs > 2500L))

            if (bothDone && autoStop && !keepAlive) {
                // Give file buffers 400ms to flush completely, then stop cleanly
                Thread.sleep(400)
                log("Analysis finished in ${elapsedSecs}s — auto-stopping BG MC process & freeing ${rss}MB RAM.")
                publish(
                    currentSnapshot.copy(
                        status = RunnerStatus.COMPLETED,
                        stage = "Completed in ${elapsedSecs}s (MC stopped, outputs ready)",
                        elapsedSecs = elapsedSecs,
                        remainingSecs = 0,
                        rssMb = 0,
                        zaphkielDone = true,
                        sigGenDone = sDone
                    )
                )
                shutdownAndKillProcess()
                return
            }

            if (bothDone && keepAlive) {
                publish(
                    currentSnapshot.copy(
                        status = RunnerStatus.HOT_IDLE,
                        stage = "Ready for instant hot re-run (${remainingSecs}s left, ${rss}MB)",
                        elapsedSecs = elapsedSecs,
                        remainingSecs = remainingSecs,
                        rssMb = rss,
                        zaphkielDone = true,
                        sigGenDone = sDone
                    )
                )
                updateNotification("Hot-ready (${remainingSecs}s left · ${rss}MB)")
            } else {
                publish(
                    currentSnapshot.copy(
                        elapsedSecs = elapsedSecs,
                        remainingSecs = remainingSecs,
                        rssMb = rss,
                        zaphkielDone = zDone,
                        sigGenDone = sDone,
                        stage = when {
                            zDone -> "Zaphkiel done — holding until timer (${remainingSecs}s left)"
                            hasZaphkiel -> "Zaphkiel running (${elapsedSecs}s / ${timeoutSecs}s · ${rss}MB)"
                            else -> "MC active in BG (${elapsedSecs}s / ${timeoutSecs}s · ${rss}MB)"
                        }
                    )
                )
                updateNotification("BG RE ${elapsedSecs}s/${timeoutSecs}s · ${rss}MB")
            }

            if (remainingSecs <= 0) {
                val finalStatus = if (zDone || !hasZaphkiel) RunnerStatus.COMPLETED else RunnerStatus.STOPPED
                log("Timer reached ${timeoutSecs}s — shutting down :mcre and freeing ${rss}MB RAM.")
                publish(
                    currentSnapshot.copy(
                        status = finalStatus,
                        stage = if (zDone) {
                            "Completed (${timeoutSecs}s timer finished, MC stopped)"
                        } else {
                            "Timer (${timeoutSecs}s) expired — MC stopped (increase timer for heavy dumps)"
                        },
                        elapsedSecs = elapsedSecs,
                        remainingSecs = 0,
                        rssMb = 0,
                        zaphkielDone = zDone,
                        sigGenDone = sDone
                    )
                )
                shutdownAndKillProcess()
                return
            }

            Thread.sleep(500)
        }
    }

    private fun log(msg: String) {
        BgStateStore.appendLog(this, msg)
    }

    private fun publish(snap: BgRunnerSnapshot) {
        currentSnapshot = snap
        BgStateStore.write(this, snap)
    }

    private fun promoteForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, McBgRunnerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ZaphReApp.BG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ZaphRE Background MC Host")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Stop MC", stopPi)
            .build()
    }

    private fun acquireWakeLock(timeoutSecs: Int) {
        runCatching {
            releaseWakeLock()
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZaphRE::BgMcWakeLock")?.apply {
                acquire((timeoutSecs + 15) * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun shutdownAndKillProcess() {
        stopFlag.set(true)
        NativeBridge.setStage("shutting_down")
        releaseWakeLock()
        runCatching { FMOD.close() }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
        // Hard-kill :mcre process so dlopen'd libminecraftpe.so + libZaphkiel.so release 100% RAM cleanly.
        Process.killProcess(Process.myPid())
    }

    override fun onDestroy() {
        stopFlag.set(true)
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 4101
        const val ACTION_START = "org.levimc.zaphre.action.START"
        const val ACTION_HOT_RERUN = "org.levimc.zaphre.action.HOT_RERUN"
        const val ACTION_STOP = "org.levimc.zaphre.action.STOP"

        const val EXTRA_RUN_ID = "extra_run_id"
        const val EXTRA_TIMEOUT_SECS = "extra_timeout_secs"
        const val EXTRA_AUTO_STOP = "extra_auto_stop"
        const val EXTRA_KEEP_ALIVE = "extra_keep_alive"
        const val EXTRA_FORCE_EXTRACT = "extra_force_extract"
        const val EXTRA_INCLUDE_SIG = "extra_include_sig"
        const val EXTRA_LAUNCH_MODE = "extra_launch_mode"
    }
}
