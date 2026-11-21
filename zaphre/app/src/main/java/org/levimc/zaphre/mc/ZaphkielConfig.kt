package org.levimc.zaphre.mc

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Manages Zaphkiel's workspace, all 32 supported zaphkiel_config.txt keys (documented + binary-extracted),
 * presets, zInput.txt targets (25+ commands), sig_targets.txt, headers/, and kNames.json.
 */
object ZaphkielConfig {

    /** Original 61-byte prefix compiled into libZaphkiel.so */
    const val LEVI_KURUMI_PATH = "/storage/emulated/0/Android/media/org.levimc.launcher/kurumi/"

    /** Exact 61-byte isolated ZaphRE workspace inside Android/media/org.levimc.zaphr4/zaphkiel/ */
    const val ISOLATED_ZAPHKIEL_PATH = "/storage/emulated/0/Android/media/org.levimc.zaphr4/zaphkiel/"

    const val PREFS_NAME = "zaphre_workspace_prefs"
    private const val KEY_WORKSPACE_MODE = "workspace_mode"

    enum class WorkspaceMode(val label: String, val subtitle: String) {
        ISOLATED(
            "Isolated ZaphRE Workspace",
            "Android/media/org.levimc.zaphr4/zaphkiel/ (No extra permission needed, gaming Levi untouched)"
        ),
        SHARED_LEVI(
            "Shared LeviLauncher kurumi/",
            "Android/media/org.levimc.launcher/kurumi/ (Requires All Files Access, syncs with LeviLauncher v1.7.1)"
        )
    }

    enum class Preset(
        val id: String,
        val title: String,
        val recommendedTimerSecs: Int,
        val description: String
    ) {
        FAST_QUERY(
            "fast_query",
            "Fast Query (zInput only)",
            15,
            "Heavy dumps OFF, delay 1s — runs zInput.txt / fc<> / vft<> / findclass<> in 2-10s"
        ),
        FIRST_INDEX(
            "first_index",
            "First-Time Index (RTTI + Dynsym + Strings)",
            300,
            "Builds rtti_dump.txt, dynsym_dump.txt & str.txt once for a new MC update"
        ),
        DEEP_DECOMPILE(
            "deep_decompile",
            "Deep Function / VTable Analysis",
            60,
            "High instruction & caller/callee limits (max_fn_insns=400, depth=4) with inline annotations"
        ),
        VTABLE_LIVE(
            "vtable_live",
            "Live VTable Pointer Dump",
            300,
            "Enables incremental live vtable_dump.txt + rtti_dump.txt"
        ),
        SIG_ONLY(
            "sig_only",
            "Signature Generator (sig_targets.txt)",
            15,
            "Minimal run to generate UNIQUE cross-version signatures.json & signatures.txt"
        ),
        FULL_BATCH(
            "full_batch",
            "Full Batch Decompile",
            300,
            "Runs full_decompile=true into zaphkiel_fullout.txt (heavy!)"
        )
    }

    /** Complete list of all 32 valid keys in libZaphkiel.so's config parser */
    val ALL_VALID_KEYS: Set<String> = linkedSetOf(
        "max_fn_insns",
        "max_callers",
        "max_callees",
        "max_sub_callers",
        "max_sub_callees",
        "max_sub_sub_callees",
        "callee_depth",
        "caller_depth",
        "max_string_len",
        "min_string_len",
        "annotate_inline_strings",
        "show_sub_call_addrs",
        "startup_delay_secs",
        "strings_path",
        "input_path",
        "out_dir",
        "out_file",
        "dump_rtti",
        "dump_dynsym",
        "dump_live_strings",
        "dump_vtables",
        "separate_dump_files",
        "full_decompile",
        "full_decompile_max_fns",
        "full_decompile_max_insns",
        "full_decompile_min_insns",
        "full_decompile_out",
        "get_strings",
        "get_strings_min_len",
        "get_strings_demangle",
        "get_strings_deobfuscate",
        "get_strings_out",
        "headers_dir",
        "knames_path"
    )

    data class CommandTemplate(val label: String, val snippet: String, val hint: String)

    val COMMAND_TEMPLATES: List<CommandTemplate> = listOf(
        CommandTemplate("findclass<>", "findclass<Block>", "Fuzzy RTTI class search"),
        CommandTemplate("hier<>", "hier<LiquidBlock>", "Dump C++ inheritance hierarchy"),
        CommandTemplate("findctor<>", "findctor<LiquidBlock>", "Find & decompile constructor(s)"),
        CommandTemplate("vft<>", "vft<LiquidBlock><0>", "Decompile single vtable slot"),
        CommandTemplate("fullvf<>", "fullvf<LiquidBlock><0,15>", "Full vtable decompile (no insn limit)"),
        CommandTemplate("vfc<>", "vfc<LiquidBlock><0,15>", "Pseudo-C for vtable slots -> zaphkiel_out.c"),
        CommandTemplate("gvft<>", "gvft<LiquidBlock><0,1,2>", "Generate C++ vtable hook code"),
        CommandTemplate("fn<>", "fn<0x1000000>", "Disassemble function at VA"),
        CommandTemplate("fullf<>", "fullf<0x1000000>", "Full function decompile (no limit)"),
        CommandTemplate("fc<>", "fc<0x1000000>", "Heuristic pseudo-C -> zaphkiel_out.c"),
        CommandTemplate("xref_tree<>", "xref_tree<0x1000000>", "Recursive caller/callee tree"),
        CommandTemplate("strfn<", "strfn<\"minecraft:water\"", "String xref + trace BL receiver"),
        CommandTemplate("sig<>", "sig<FF 83 00 D1 ?? ?? ?? ??>", "IDA byte pattern scan"),
        CommandTemplate("struct<>", "struct<Level><0x180>", "Find all accesses to struct +offset"),
        CommandTemplate("vcallers<>", "vcallers<0x40>", "Find callers of virtual byte offset"),
        CommandTemplate("entt<>", "entt<StateVectorComponent>", "Resolve MCBE EnTT component storage"),
        CommandTemplate("hg<>", "hg<LiquidBlock>", "Auto-generate starter C++ class header"),
        CommandTemplate("h<>", "h<ScreenView>", "Parse header & dump layout"),
        CommandTemplate("add<>", "add<0x1000000>", "Resolve data VA to containing fn"),
        CommandTemplate("range<>", "range<0x1000000,0x1000100>", "Disassemble raw VA range"),
        CommandTemplate("plvft<>", "plvft<Actor><0,10><0>", "Decompile specific MI vtable candidate"),
        CommandTemplate("rawslots<>", "rawslots<Actor>", "Dump raw vtable slot VAs")
    )

    fun getWorkspaceMode(context: Context): WorkspaceMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WORKSPACE_MODE, WorkspaceMode.ISOLATED.name)
        return WorkspaceMode.entries.firstOrNull { it.name == raw } ?: WorkspaceMode.ISOLATED
    }

    fun setWorkspaceMode(context: Context, mode: WorkspaceMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WORKSPACE_MODE, mode.name)
            .apply()
        ensureWorkspaceLayout(context)
    }

    fun hasAllFilesAccess(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Active workspace directory (either isolated Android/media/org.levimc.zaphr4/zaphkiel/
     * or shared Android/media/org.levimc.launcher/kurumi/).
     */
    fun getWorkspaceDir(context: Context): File {
        val mode = getWorkspaceMode(context)
        if (mode == WorkspaceMode.SHARED_LEVI) {
            val shared = File(LEVI_KURUMI_PATH)
            shared.mkdirs()
            return shared
        }
        // Calling externalMediaDirs grants our UID automatic full POSIX access to Android/media/org.levimc.zaphr4/
        val mediaRoot = context.externalMediaDirs?.firstOrNull()
        val dir = if (mediaRoot != null) {
            File(mediaRoot, "zaphkiel")
        } else {
            File(ISOLATED_ZAPHKIEL_PATH)
        }
        if (!dir.exists() && !dir.mkdirs()) {
            // Fallback to internal filesDir if external media is temporarily unmounted
            return File(context.filesDir, "zaphkiel").apply { mkdirs() }
        }
        return dir
    }

    /** The exact trailing-slash path string to write into zaphkiel_config.txt */
    fun getWorkspacePathSlash(context: Context): String {
        val p = getWorkspaceDir(context).absolutePath
        return if (p.endsWith("/")) p else "$p/"
    }

    fun ensureWorkspaceLayout(context: Context): File {
        val ws = getWorkspaceDir(context)
        ws.mkdirs()
        File(ws, "headers").mkdirs()
        File(ws, "generated_mods").mkdirs()
        File(ws, "notes").mkdirs()

        val cfgFile = File(ws, "zaphkiel_config.txt")
        if (!cfgFile.exists()) {
            writeConfigMap(context, buildPresetMap(context, Preset.FAST_QUERY))
        }
        val zInput = File(ws, "zInput.txt")
        if (!zInput.exists()) {
            zInput.writeText("# Zaphkiel targets (one per line)\nfindclass<LiquidBlock>\n")
        }
        val sigTargets = File(ws, "sig_targets.txt")
        if (!sigTargets.exists()) {
            sigTargets.writeText("# Signature targets: fn<0xADDR> or add<0xADDR>\n")
        }
        return ws
    }

    fun configFile(context: Context): File = File(ensureWorkspaceLayout(context), "zaphkiel_config.txt")
    fun inputFile(context: Context): File = File(ensureWorkspaceLayout(context), "zInput.txt")
    fun sigTargetsFile(context: Context): File = File(ensureWorkspaceLayout(context), "sig_targets.txt")
    fun logFile(context: Context): File = File(ensureWorkspaceLayout(context), "zaphkiel_log.txt")
    fun outFile(context: Context, customName: String? = null): File {
        val name = customName?.takeIf { it.isNotBlank() }
            ?: readConfigMap(context)["out_file"]?.takeIf { it.isNotBlank() }
            ?: "zaphkiel_out.txt"
        return File(ensureWorkspaceLayout(context), name)
    }
    fun pseudoCFile(context: Context): File = File(ensureWorkspaceLayout(context), "zaphkiel_out.c")
    fun signaturesJsonFile(context: Context): File = File(ensureWorkspaceLayout(context), "signatures.json")
    fun signaturesTxtFile(context: Context): File = File(ensureWorkspaceLayout(context), "signatures.txt")
    fun headersDir(context: Context): File = File(ensureWorkspaceLayout(context), "headers").apply { mkdirs() }

    fun defaultConfigMap(context: Context): LinkedHashMap<String, String> {
        val ws = getWorkspacePathSlash(context)
        return linkedMapOf(
            "max_fn_insns" to "160",
            "max_callers" to "8",
            "max_callees" to "8",
            "callee_depth" to "3",
            "caller_depth" to "2",
            "annotate_inline_strings" to "1",
            "show_sub_call_addrs" to "1",
            "startup_delay_secs" to "1",
            "strings_path" to "${ws}zInput.txt",
            "out_dir" to ws,
            "out_file" to "zaphkiel_out.txt",
            "dump_rtti" to "false",
            "dump_dynsym" to "false",
            "dump_live_strings" to "false",
            "dump_vtables" to "false",
            "separate_dump_files" to "true",
            "full_decompile" to "false",
            "full_decompile_max_fns" to "10000",
            "full_decompile_max_insns" to "60",
            "get_strings" to "false",
            "get_strings_min_len" to "4",
            "get_strings_demangle" to "true",
            "get_strings_deobfuscate" to "true",
            "headers_dir" to "${ws}headers/",
            "knames_path" to "${ws}kNames.json"
        )
    }

    fun buildPresetMap(context: Context, preset: Preset): LinkedHashMap<String, String> {
        val m = defaultConfigMap(context)
        when (preset) {
            Preset.FAST_QUERY -> {
                m["startup_delay_secs"] = "1"
                m["max_fn_insns"] = "180"
                m["dump_rtti"] = "false"
                m["dump_dynsym"] = "false"
                m["dump_vtables"] = "false"
                m["get_strings"] = "false"
                m["full_decompile"] = "false"
            }
            Preset.FIRST_INDEX -> {
                m["startup_delay_secs"] = "1"
                m["dump_rtti"] = "true"
                m["dump_dynsym"] = "true"
                m["dump_vtables"] = "false"
                m["separate_dump_files"] = "true"
                m["get_strings"] = "true"
                m["get_strings_min_len"] = "4"
                m["get_strings_demangle"] = "true"
                m["get_strings_deobfuscate"] = "true"
            }
            Preset.DEEP_DECOMPILE -> {
                m["startup_delay_secs"] = "1"
                m["max_fn_insns"] = "450"
                m["max_callers"] = "14"
                m["max_callees"] = "14"
                m["callee_depth"] = "4"
                m["caller_depth"] = "3"
                m["annotate_inline_strings"] = "1"
                m["show_sub_call_addrs"] = "1"
            }
            Preset.VTABLE_LIVE -> {
                m["startup_delay_secs"] = "2"
                m["dump_rtti"] = "true"
                m["dump_vtables"] = "true"
                m["separate_dump_files"] = "true"
            }
            Preset.SIG_ONLY -> {
                m["startup_delay_secs"] = "0"
                m["dump_rtti"] = "false"
                m["dump_dynsym"] = "false"
                m["dump_vtables"] = "false"
                m["get_strings"] = "false"
                m["full_decompile"] = "false"
            }
            Preset.FULL_BATCH -> {
                m["startup_delay_secs"] = "2"
                m["full_decompile"] = "true"
                m["full_decompile_max_fns"] = "15000"
                m["full_decompile_max_insns"] = "80"
            }
        }
        return m
    }

    fun applyPreset(context: Context, preset: Preset): LinkedHashMap<String, String> {
        val map = buildPresetMap(context, preset)
        writeConfigMap(context, map)
        return map
    }

    fun readConfigMap(context: Context): LinkedHashMap<String, String> {
        val map = defaultConfigMap(context)
        val f = File(getWorkspaceDir(context), "zaphkiel_config.txt")
        if (!f.isFile) return map
        runCatching {
            f.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachLine
                val eq = line.indexOf('=')
                if (eq > 0) {
                    val key = line.substring(0, eq).trim()
                    val value = line.substring(eq + 1).trim()
                    if (key.isNotEmpty()) map[key] = value
                }
            }
        }
        // Always keep workspace path keys synced with the currently selected workspace
        val ws = getWorkspacePathSlash(context)
        map["strings_path"] = "${ws}zInput.txt"
        map["out_dir"] = ws
        map["headers_dir"] = "${ws}headers/"
        map["knames_path"] = "${ws}kNames.json"
        return map
    }

    fun writeConfigMap(context: Context, map: Map<String, String>) {
        val ws = getWorkspaceDir(context).apply { mkdirs() }
        val wsSlash = getWorkspacePathSlash(context)
        val merged = LinkedHashMap(map)
        merged["strings_path"] = "${wsSlash}zInput.txt"
        merged["out_dir"] = wsSlash
        merged["headers_dir"] = "${wsSlash}headers/"
        if ("knames_path" !in merged) merged["knames_path"] = "${wsSlash}kNames.json"

        val text = buildString {
            appendLine("# Zaphkiel RE Configuration (managed by ZaphRE)")
            for ((k, v) in merged) {
                append(k).append(" = ").appendLine(v)
            }
        }
        File(ws, "zaphkiel_config.txt").writeText(text)
    }

    fun updateConfig(context: Context, preset: Preset? = null, overrides: Map<String, String> = emptyMap()): LinkedHashMap<String, String> {
        val base = if (preset != null) buildPresetMap(context, preset) else readConfigMap(context)
        for ((k, v) in overrides) {
            val cleanKey = k.trim()
            if (cleanKey in ALL_VALID_KEYS) {
                base[cleanKey] = v.trim()
            }
        }
        writeConfigMap(context, base)
        return base
    }

    fun writeTargets(context: Context, lines: List<String>) {
        val cleaned = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val body = if (cleaned.isEmpty()) {
            "# (no zInput targets for this run)\n"
        } else {
            cleaned.joinToString("\n", postfix = "\n")
        }
        inputFile(context).writeText(body)
    }

    fun writeSigTargets(context: Context, lines: List<String>) {
        val cleaned = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val body = if (cleaned.isEmpty()) {
            ""
        } else {
            cleaned.joinToString("\n", postfix = "\n")
        }
        sigTargetsFile(context).writeText(body)
    }
}
