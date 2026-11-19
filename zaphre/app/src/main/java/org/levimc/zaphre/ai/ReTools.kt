package org.levimc.zaphre.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.levimc.zaphre.bridge.NativeBridge
import org.levimc.zaphre.files.WorkspaceFiles
import org.levimc.zaphre.mc.BgController
import org.levimc.zaphre.mc.BgStateStore
import org.levimc.zaphre.mc.McTargetManager
import org.levimc.zaphre.mc.RunnerStatus
import org.levimc.zaphre.mc.ZaphkielConfig
import org.levimc.zaphre.terminal.ShellExecutor

/**
 * All Ghidra-MCP style tools available to ZaphRE's AI agent loop:
 *  - Environment inspection
 *  - Zaphkiel config management, automated BG MC run + wait + auto-stop, hot re-run, signature gen
 *  - Explicit BG MC start / stop / status with custom timers (10s, 15s, 30s, 1m, 5m, etc.)
 *  - Built-in C++ zero-launch static ELF64 analyzer (strings, RTTI, dynsym, IDA sig scan, ARM64 VA dump)
 *  - Workspace file manager (list, chunked read, write, streaming grep)
 *  - Terminal execution (Android toybox shell + real Termux bridge)
 */
object ReTools {

    fun schemas(): List<ToolSchema> = listOf(
        ToolSchema(
            name = "get_env_status",
            description = "Get current RE environment status: selected Minecraft target & version, libZaphkiel.so presence, BG runner status/PID/base VA, Termux bridge status, and workspace files with sizes.",
            parametersJson = objSchema()
        ),
        ToolSchema(
            name = "zaphkiel_run",
            description = "Configure Zaphkiel targets/options, run Minecraft + libZaphkiel.so in the background (or hot-retrigger if already running), wait for 'run() complete' or timeout_secs, optionally stop BG MC, and return log + output preview.",
            parametersJson = objSchema(
                props = mapOf(
                    "targets" to arrayStrProp("Lines to write into zInput.txt (e.g. ['findclass<LiquidBlock>', 'vft<LiquidBlock><0>', 'fc<0x1088d268>'])"),
                    "sig_targets" to arrayStrProp("Optional signature targets for sig_targets.txt (e.g. ['fn<0x1088d268>'])"),
                    "timeout_secs" to intProp("Max seconds to keep BG MC alive (e.g. 15, 30, 60, 300). Default 45."),
                    "stop_after" to boolProp("Stop BG MC immediately after 'run() complete' to free RAM (default true). Set false to keep hot for multiple rapid queries."),
                    "dump_rtti" to boolProp("Dump all RTTI classes to rtti_dump.txt (default false; enable only for initial indexing)."),
                    "dump_dynsym" to boolProp("Dump dynamic symbols to dynsym_dump.txt (default false)."),
                    "dump_vtables" to boolProp("Dump live vtable pointers to vtable_dump.txt (default false; heavy)."),
                    "get_strings" to boolProp("Extract binary strings to str.txt (default false; heavy)."),
                    "max_fn_insns" to intProp("Max instructions per disassembled function (default 180)."),
                    "caller_depth" to intProp("Caller xref depth (default 2)."),
                    "callee_depth" to intProp("Callee xref depth (default 3)."),
                    "out_file" to strProp("Output filename inside workspace (default 'zaphkiel_out.txt').")
                ),
                required = listOf("targets")
            )
        ),
        ToolSchema(
            name = "zaphkiel_sig",
            description = "Generate cross-version wildcarded ARM64 signatures for one or more function/address VAs via Zaphkiel's sig_targets.txt and return signatures.json + signatures.txt.",
            parametersJson = objSchema(
                props = mapOf(
                    "targets" to arrayStrProp("List of 'fn<0xADDR>' or 'add<0xADDR>' targets"),
                    "timeout_secs" to intProp("Max seconds to run BG MC (default 30)")
                ),
                required = listOf("targets")
            )
        ),
        ToolSchema(
            name = "zaphkiel_configure",
            description = "Apply a Zaphkiel config preset or override specific keys in zaphkiel_config.txt without launching MC.",
            parametersJson = objSchema(
                props = mapOf(
                    "preset" to strProp("Optional preset: fast_query | first_index | deep_decompile | vtable_live | sig_only | full_batch"),
                    "overrides" to JSONObject().put("type", "object").put("description", "Key-value map of zaphkiel_config.txt overrides")
                )
            )
        ),
        ToolSchema(
            name = "mc_bg_control",
            description = "Manually start, stop, or inspect the background Minecraft RE process (:mcre).",
            parametersJson = objSchema(
                props = mapOf(
                    "action" to strProp("start | stop | status"),
                    "timeout_secs" to intProp("Auto-stop timer in seconds when action=start (e.g. 10, 15, 30, 60, 300)"),
                    "keep_alive" to boolProp("If true on start, keep MC alive in memory until timer for instant hot re-runs")
                ),
                required = listOf("action")
            )
        ),
        ToolSchema(
            name = "elf_static_scan",
            description = "Instant zero-launch native C++ ELF64 analysis directly on libminecraftpe.so (works even without launching MC or Zaphkiel!). Actions: info, strings, rtti, dynsym, sig_scan, read_va.",
            parametersJson = objSchema(
                props = mapOf(
                    "action" to strProp("One of: info | strings | rtti | dynsym | sig_scan | read_va"),
                    "query" to strProp("Substring for strings/rtti/dynsym, IDA pattern 'AA ?? BB CC' for sig_scan, or hex VA '0x1088d268' for read_va"),
                    "case_sensitive" to boolProp("Case-sensitive match for strings (default false)"),
                    "max_hits" to intProp("Maximum results or byte count for read_va (default 80)")
                ),
                required = listOf("action")
            )
        ),
        ToolSchema(
            name = "fs_list",
            description = "List files and folders in the RE workspace (or a subfolder like 'headers' or 'generated_mods') with sizes and timestamps.",
            parametersJson = objSchema(
                props = mapOf("path" to strProp("Relative folder path inside workspace (default root '')"))
            )
        ),
        ToolSchema(
            name = "fs_read",
            description = "Read a line slice of a workspace file (e.g. zaphkiel_out.txt, zaphkiel_out.c, signatures.json, headers/Foo.h).",
            parametersJson = objSchema(
                props = mapOf(
                    "path" to strProp("Relative file path inside workspace"),
                    "start_line" to intProp("1-based first line to read (default 1)"),
                    "max_lines" to intProp("Max lines to return (default 180, max 800)")
                ),
                required = listOf("path")
            )
        ),
        ToolSchema(
            name = "fs_search",
            description = "Fast streaming search (grep) inside a workspace file — safely handles huge rtti_dump.txt, dynsym_dump.txt, str.txt, zaphkiel_out.txt, or zaphkiel_out.c.",
            parametersJson = objSchema(
                props = mapOf(
                    "path" to strProp("Relative file path to search (e.g. 'rtti_dump.txt' or 'zaphkiel_out.txt')"),
                    "pattern" to strProp("Substring or regex pattern"),
                    "is_regex" to boolProp("Treat pattern as regex (default false)"),
                    "case_sensitive" to boolProp("Case-sensitive search (default false)"),
                    "context_lines" to intProp("Surrounding context lines per match (default 2)"),
                    "max_matches" to intProp("Max matches to return (default 50)")
                ),
                required = listOf("path", "pattern")
            )
        ),
        ToolSchema(
            name = "fs_write",
            description = "Create or update a file inside the RE workspace (e.g. headers/LiquidBlock.h, kNames.json, generated_mods/hook.cpp, notes/findings.md).",
            parametersJson = objSchema(
                props = mapOf(
                    "path" to strProp("Relative file path inside workspace"),
                    "content" to strProp("Text content to write"),
                    "append" to boolProp("Append instead of overwrite (default false)")
                ),
                required = listOf("path", "content")
            )
        ),
        ToolSchema(
            name = "run_cmd",
            description = "Execute a shell command in the RE workspace (\$WORKSPACE, \$HEADERS, \$MC_LIB set). Uses Termux bridge automatically if online (python3, rg, readelf, llvm-objdump, jq), otherwise Android toybox shell (grep, awk, sed, head, tail, wc, sort, strings, xxd).",
            parametersJson = objSchema(
                props = mapOf(
                    "command" to strProp("Shell command to run"),
                    "timeout_secs" to intProp("Command timeout in seconds (default 30)")
                ),
                required = listOf("command")
            )
        )
    )

    fun summarizeArgs(name: String, args: JSONObject): String {
        return when (name) {
            "zaphkiel_run" -> {
                val arr = args.optJSONArray("targets")
                val preview = if (arr != null && arr.length() > 0) {
                    List(minOf(arr.length(), 3)) { arr.optString(it) }.joinToString(", ") +
                        if (arr.length() > 3) " +${arr.length() - 3}" else ""
                } else "(dump/config run)"
                "$preview · ${args.optInt("timeout_secs", 45)}s"
            }
            "zaphkiel_sig" -> args.optJSONArray("targets")?.toString()?.take(90) ?: "sig_targets.txt"
            "elf_static_scan" -> "${args.optString("action")} ${args.optString("query")}".trim()
            "fs_read" -> "${args.optString("path")} (L${args.optInt("start_line", 1)})"
            "fs_search" -> "'${args.optString("pattern")}' in ${args.optString("path")}"
            "fs_write" -> args.optString("path")
            "fs_list" -> args.optString("path").ifBlank { "/" }
            "run_cmd" -> "$ ${args.optString("command").take(80)}"
            "mc_bg_control" -> "${args.optString("action")} ${args.optInt("timeout_secs", 0).takeIf { it > 0 }?.let { "${it}s" } ?: ""}".trim()
            else -> args.toString().take(80)
        }
    }

    suspend fun execute(context: Context, call: ToolCallSpec): String = withContext(Dispatchers.IO) {
        try {
            when (call.name) {
                "get_env_status" -> toolEnvStatus(context)
                "zaphkiel_run" -> toolZaphkielRun(context, call.args)
                "zaphkiel_sig" -> toolZaphkielSig(context, call.args)
                "zaphkiel_configure" -> toolZaphkielConfigure(context, call.args)
                "mc_bg_control" -> toolMcBgControl(context, call.args)
                "elf_static_scan" -> toolElfStaticScan(context, call.args)
                "fs_list" -> toolFsList(context, call.args)
                "fs_read" -> toolFsRead(context, call.args)
                "fs_search" -> toolFsSearch(context, call.args)
                "fs_write" -> toolFsWrite(context, call.args)
                "run_cmd" -> toolRunCmd(context, call.args)
                else -> "ERROR: Unknown tool '${call.name}'"
            }
        } catch (t: Throwable) {
            "ERROR in ${call.name}: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private suspend fun toolEnvStatus(context: Context): String {
        val target = McTargetManager.getSelectedTarget(context)
        val allTargets = McTargetManager.listAllTargets(context)
        val hasZaph = McTargetManager.hasZaphkiel(context)
        val snap = BgController.readVerifiedSnapshot(context)
        val ws = ZaphkielConfig.ensureWorkspaceLayout(context)
        val wsMode = ZaphkielConfig.getWorkspaceMode(context)
        val termuxOnline = ShellExecutor.pingTermuxBridge(context)
        val termuxInstalled = ShellExecutor.isTermuxInstalled(context)
        val levi = McTargetManager.detectInstalledLeviPackage(context)
        val files = WorkspaceFiles.list(context)

        return buildString {
            appendLine("=== ZaphRE Environment ===")
            appendLine("Selected MC Target: ${target?.let { "${it.label} [version=${it.versionName.ifBlank { "raw" }}, flags=${it.flags.joinToString()}]" } ?: "NONE (ask user to install MC or import APK/.so in BG Run tab)"}")
            appendLine("Available MC Targets: ${allTargets.size}")
            appendLine("libZaphkiel.so Imported: ${if (hasZaph) "YES (${WorkspaceFiles.formatBytes(McTargetManager.zaphkielRawFile(context).length())})" else "NO (import libZaphkiel.so in BG Run tab; elf_static_scan still works offline!)"}")
            appendLine("Launch Mode: ${McTargetManager.getLaunchMode(context).name}")
            appendLine("LeviLauncher Installed: ${levi?.let { "${it.first} v${it.second}" } ?: "no"}")
            appendLine("Workspace (${wsMode.name}): ${ws.absolutePath}")
            appendLine("BG Runner: status=${snap.status}, pid=${snap.pid}, mcBase=${snap.mcBaseHex.ifBlank { "-" }}, rss=${snap.rssMb}MB, stage=${snap.stage}")
            appendLine("Terminal: android-sh=ready, termux_installed=$termuxInstalled, termux_bridge_online=$termuxOnline")
            appendLine("\nWorkspace Files (${files.size}):")
            for (f in files.take(30)) {
                appendLine("  - ${f.relativePath} (${f.formattedSize()}, ${f.formattedTime()})")
            }
        }
    }

    private suspend fun toolZaphkielRun(context: Context, args: JSONObject): String {
        if (!McTargetManager.hasZaphkiel(context)) {
            return "ERROR: libZaphkiel.so is not imported yet. Ask the user to tap 'Import libZaphkiel.so' in the BG Run tab — OR use `elf_static_scan` right now to analyze libminecraftpe.so offline!"
        }
        if (McTargetManager.getSelectedTarget(context) == null &&
            McTargetManager.getLaunchMode(context) != McTargetManager.LaunchMode.LEVI_COMPANION
        ) {
            return "ERROR: No Minecraft target found. Ask user to install Minecraft Bedrock (1.21.80+ / 26.x) or import an APK / libminecraftpe.so in BG Run tab."
        }

        val targets = jsonArrayToStrings(args.optJSONArray("targets"))
        val sigTargets = jsonArrayToStrings(args.optJSONArray("sig_targets"))
        val timeoutSecs = args.optInt("timeout_secs", 45).coerceIn(8, 900)
        val stopAfter = args.optBoolean("stop_after", true)
        val outFileName = args.optString("out_file", "zaphkiel_out.txt").ifBlank { "zaphkiel_out.txt" }

        val overrides = linkedMapOf(
            "startup_delay_secs" to "1",
            "out_file" to outFileName,
            "dump_rtti" to args.optBoolean("dump_rtti", false).toString(),
            "dump_dynsym" to args.optBoolean("dump_dynsym", false).toString(),
            "dump_vtables" to args.optBoolean("dump_vtables", false).toString(),
            "get_strings" to args.optBoolean("get_strings", false).toString(),
            "full_decompile" to "false",
            "separate_dump_files" to "true",
            "max_fn_insns" to args.optInt("max_fn_insns", 180).coerceIn(20, 4000).toString(),
            "caller_depth" to args.optInt("caller_depth", 2).coerceIn(0, 8).toString(),
            "callee_depth" to args.optInt("callee_depth", 3).coerceIn(0, 8).toString(),
            "annotate_inline_strings" to "1",
            "show_sub_call_addrs" to "1"
        )
        ZaphkielConfig.updateConfig(context, overrides = overrides)
        ZaphkielConfig.writeTargets(context, targets)
        ZaphkielConfig.writeSigTargets(context, sigTargets)

        val wantsPseudoC = targets.any { it.startsWith("fc<") || it.startsWith("vfc<") }
        val beforeSnap = BgController.readVerifiedSnapshot(context)
        val runId = if (beforeSnap.status == RunnerStatus.HOT_IDLE && beforeSnap.pid > 0) {
            overrides["startup_delay_secs"] = "0"
            ZaphkielConfig.updateConfig(context, overrides = overrides)
            BgController.hotRerun(
                context = context,
                timeoutSecs = timeoutSecs,
                includeSigGen = sigTargets.isNotEmpty(),
                stopAfter = stopAfter
            )
        } else {
            BgController.startRun(
                context = context,
                timeoutSecs = timeoutSecs,
                autoStopOnComplete = stopAfter,
                keepAliveHot = !stopAfter
            )
        }

        val finalSnap = BgController.awaitRun(context, runId, timeoutSecs, stopWhenDone = stopAfter)
        val zLog = runCatching { ZaphkielConfig.logFile(context).readText().takeLast(2500) }.getOrDefault("")
        val outF = ZaphkielConfig.outFile(context, outFileName)
        val outPreview = if (outF.isFile) {
            WorkspaceFiles.readSlice(context, outFileName, 1, 140, maxChars = 14_000).getOrNull()?.content.orEmpty()
        } else "(not generated)"

        val cFile = ZaphkielConfig.pseudoCFile(context)
        val cPreview = if (wantsPseudoC && cFile.isFile) {
            "\n=== zaphkiel_out.c (${WorkspaceFiles.formatBytes(cFile.length())}) ===\n" +
                WorkspaceFiles.readSlice(context, "zaphkiel_out.c", 1, 120, maxChars = 10_000).getOrNull()?.content.orEmpty()
        } else ""

        val sigPreview = if (sigTargets.isNotEmpty() && ZaphkielConfig.signaturesJsonFile(context).isFile) {
            "\n=== signatures.json ===\n" + ZaphkielConfig.signaturesJsonFile(context).readText().take(6000)
        } else ""

        return buildString {
            appendLine("=== Zaphkiel Run Summary ===")
            appendLine("Status: ${finalSnap.status} | Elapsed: ${finalSnap.elapsedSecs}s | MC Base: ${finalSnap.mcBaseHex.ifBlank { "-" }} | Completed: ${finalSnap.zaphkielDone}")
            if (finalSnap.error.isNotBlank()) appendLine("Error/Crash:\n${finalSnap.error}")
            appendLine("\n--- zaphkiel_log.txt ---")
            appendLine(zLog.ifBlank { BgStateStore.readLogTail(context, 3000) })
            appendLine("\n=== $outFileName (${if (outF.isFile) WorkspaceFiles.formatBytes(outF.length()) else "0 B"}) — use fs_read/fs_search for more ===")
            appendLine(outPreview)
            append(cPreview)
            append(sigPreview)
        }
    }

    private suspend fun toolZaphkielSig(context: Context, args: JSONObject): String {
        val sigTargets = jsonArrayToStrings(args.optJSONArray("targets"))
        if (sigTargets.isEmpty()) return "ERROR: targets must contain at least one 'fn<0xADDR>' or 'add<0xADDR>'"
        if (!McTargetManager.hasZaphkiel(context)) {
            return "ERROR: libZaphkiel.so not imported yet."
        }
        val timeoutSecs = args.optInt("timeout_secs", 30).coerceIn(8, 300)
        ZaphkielConfig.applyPreset(context, ZaphkielConfig.Preset.SIG_ONLY)
        ZaphkielConfig.writeTargets(context, emptyList())
        ZaphkielConfig.writeSigTargets(context, sigTargets)

        val snap = BgController.readVerifiedSnapshot(context)
        val runId = if (snap.status == RunnerStatus.HOT_IDLE && snap.pid > 0) {
            BgController.hotRerun(context, timeoutSecs, includeSigGen = true, stopAfter = true)
        } else {
            BgController.startRun(context, timeoutSecs = timeoutSecs, autoStopOnComplete = true, keepAliveHot = false)
        }
        val done = BgController.awaitRun(context, runId, timeoutSecs, stopWhenDone = true)
        val sigTxt = runCatching { ZaphkielConfig.signaturesTxtFile(context).readText() }.getOrDefault("")
        val sigJson = runCatching { ZaphkielConfig.signaturesJsonFile(context).readText() }.getOrDefault("")

        return buildString {
            appendLine("Signature generation finished: status=${done.status}, elapsed=${done.elapsedSecs}s")
            if (done.error.isNotBlank()) appendLine("Error: ${done.error}")
            appendLine("\n=== signatures.txt ===\n${sigTxt.take(10_000)}")
            appendLine("\n=== signatures.json ===\n${sigJson.take(12_000)}")
        }
    }

    private fun toolZaphkielConfigure(context: Context, args: JSONObject): String {
        val presetId = args.optString("preset", "").trim()
        val preset = ZaphkielConfig.Preset.entries.firstOrNull { it.id.equals(presetId, ignoreCase = true) }
        val ovObj = args.optJSONObject("overrides")
        val overrides = linkedMapOf<String, String>()
        if (ovObj != null) {
            val keys = ovObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                overrides[k] = ovObj.opt(k)?.toString().orEmpty()
            }
        }
        val updated = ZaphkielConfig.updateConfig(context, preset, overrides)
        return "Updated zaphkiel_config.txt (${updated.size} keys):\n" +
            updated.entries.joinToString("\n") { "  ${it.key} = ${it.value}" }
    }

    private fun toolMcBgControl(context: Context, args: JSONObject): String {
        return when (args.optString("action").lowercase()) {
            "start" -> {
                val timer = args.optInt("timeout_secs", McTargetManager.getDefaultTimerSecs(context))
                val keepAlive = args.optBoolean("keep_alive", true)
                val id = BgController.startRun(
                    context = context,
                    timeoutSecs = timer,
                    autoStopOnComplete = !keepAlive,
                    keepAliveHot = keepAlive
                )
                "Started BG MC session #$id (timer=${timer}s, keepAlive=$keepAlive)"
            }
            "stop" -> {
                BgController.stopNow(context)
                "Stopped BG MC process (:mcre) and released RAM."
            }
            else -> {
                val s = BgController.readVerifiedSnapshot(context)
                "BG Status: ${s.toJson().toString(2)}\n\nRecent Log:\n${BgStateStore.readLogTail(context, 4000)}"
            }
        }
    }

    private fun toolElfStaticScan(context: Context, args: JSONObject): String {
        val mcSo = McTargetManager.resolveMcSoPath(context)
            ?: return "ERROR: No libminecraftpe.so found. Install Minecraft or import an APK / libminecraftpe.so first."
        val action = args.optString("action").lowercase()
        val query = args.optString("query", "")
        val maxHits = args.optInt("max_hits", 80)
        return when (action) {
            "info" -> NativeBridge.inspectElf(mcSo.absolutePath)
            "strings" -> NativeBridge.searchStrings(mcSo.absolutePath, query, args.optBoolean("case_sensitive", false), maxHits)
            "rtti" -> NativeBridge.searchRtti(mcSo.absolutePath, query, maxHits)
            "dynsym" -> NativeBridge.searchDynsym(mcSo.absolutePath, query, maxHits)
            "sig_scan" -> NativeBridge.scanSignature(mcSo.absolutePath, query, maxHits)
            "read_va" -> {
                val cleanHex = query.trim().removePrefix("0x").removePrefix("0X")
                val va = cleanHex.toULongOrNull(16)?.toLong()
                    ?: return "ERROR: query must be a hex VA such as '0x1088d268'"
                NativeBridge.readVa(mcSo.absolutePath, va, maxHits.coerceIn(16, 1024))
            }
            else -> "ERROR: unknown action '$action'. Use info | strings | rtti | dynsym | sig_scan | read_va"
        }
    }

    private fun toolFsList(context: Context, args: JSONObject): String {
        val sub = args.optString("path", "")
        val items = WorkspaceFiles.list(context, sub)
        if (items.isEmpty()) return "(empty folder: ${sub.ifBlank { "/" }})"
        return buildString {
            appendLine("Listing '${sub.ifBlank { "/" }}' (${items.size} entries):")
            for (e in items) {
                appendLine("  [${e.category}] ${e.relativePath}  (${e.formattedSize()} · ${e.formattedTime()})")
            }
        }
    }

    private fun toolFsRead(context: Context, args: JSONObject): String {
        val path = args.optString("path")
        val start = args.optInt("start_line", 1)
        val max = args.optInt("max_lines", 180).coerceIn(1, 800)
        return WorkspaceFiles.readSlice(context, path, start, max).fold(
            onSuccess = { s ->
                "File: ${s.relativePath} (${WorkspaceFiles.formatBytes(s.sizeBytes)}) | Lines ${s.startLine}..${s.endLine}" +
                    (if (s.truncated) " [more lines available — pass start_line=${s.endLine + 1}]" else "") +
                    "\n" + s.content
            },
            onFailure = { "ERROR: ${it.message}" }
        )
    }

    private fun toolFsSearch(context: Context, args: JSONObject): String {
        return WorkspaceFiles.searchInFile(
            context = context,
            path = args.optString("path"),
            pattern = args.optString("pattern"),
            isRegex = args.optBoolean("is_regex", false),
            caseSensitive = args.optBoolean("case_sensitive", false),
            maxMatches = args.optInt("max_matches", 50),
            contextLines = args.optInt("context_lines", 2)
        ).getOrElse { "ERROR: ${it.message}" }
    }

    private fun toolFsWrite(context: Context, args: JSONObject): String {
        val path = args.optString("path")
        val content = args.optString("content")
        val append = args.optBoolean("append", false)
        return WorkspaceFiles.writeText(context, path, content, append).fold(
            onSuccess = { f -> "Saved ${f.absolutePath} (${WorkspaceFiles.formatBytes(f.length())})" },
            onFailure = { "ERROR: ${it.message}" }
        )
    }

    private suspend fun toolRunCmd(context: Context, args: JSONObject): String {
        val cmd = args.optString("command")
        if (cmd.isBlank()) return "ERROR: command cannot be empty"
        val timeout = args.optInt("timeout_secs", 30)
        val res = ShellExecutor.execute(context, cmd, timeoutSecs = timeout)
        return res.formattedOutput()
    }

    private fun jsonArrayToStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return List(arr.length()) { arr.optString(it).trim() }.filter { it.isNotEmpty() }
    }

    private fun objSchema(
        props: Map<String, JSONObject> = emptyMap(),
        required: List<String> = emptyList()
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            for ((k, v) in props) put(k, v)
        })
        if (required.isNotEmpty()) put("required", JSONArray(required))
    }

    private fun strProp(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun intProp(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun boolProp(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    private fun arrayStrProp(desc: String) = JSONObject()
        .put("type", "array")
        .put("description", desc)
        .put("items", JSONObject().put("type", "string"))
}
