package org.levimc.zaphre.mc

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import org.levimc.zaphre.bridge.NativeBridge
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Manages Minecraft Bedrock targets (installed Play Store release/beta, imported APK/APKS/XAPK
 * bundles, or standalone libminecraftpe.so) plus libZaphkiel.so and optional .levipack/.so RE mods.
 *
 * Uses exact LeviLauncher-Android v1.7.1 native library rules:
 *  - libc++_shared.so, libfmod.so, libMediaDecoders_Android.so
 *  - libmaesdk.so (1.21.110+ / 1.21.110.22 beta+)
 *  - libHttpClient.Android.so & libPlayFabMultiplayer.so (1.21.130+ / 1.21.130.20 beta+)
 *  - libMallocRedirect.so (1.26.70.00+ / 26.70+ & latest Beta)
 *  - Automatic ELF64 DT_NEEDED topological fallback for any new .so added in future MC updates
 *  - Strict Android 14-16 read-only (0444) code cache guard
 */
object McTargetManager {

    const val MC_PACKAGE = "com.mojang.minecraftpe"
    val LEVI_PACKAGES = listOf(
        "org.levimc.launcher",
        "org.levimc.launcher.beta",
        "org.levimc.launcher.debug"
    )

    /** Libraries shipped in ZaphRE's own jniLibs from official LeviLauncher v1.7.1 */
    val BUNDLED_LEVI_LIBS = setOf(
        "libPlayFabMultiplayer.so",
        "libmaesdk.so",
        "libHttpClient.Android.so",
        "libpreloader.so"
    )

    /** Standard Android NDK system libraries already present in every app process */
    private val ANDROID_SYSTEM_LIBS = setOf(
        "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
        "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so",
        "libOpenSLES.so", "libaaudio.so", "libz.so", "libjnigraphics.so",
        "libmediandk.so", "libnativewindow.so", "libvulkan.so", "libcamera2ndk.so",
        "libsync.so", "libneuralnetworks.so", "ld-android.so"
    )

    private const val PREFS = "zaphre_mc_targets"
    private const val KEY_SELECTED_TARGET = "selected_target_id"
    private const val KEY_LAUNCH_MODE = "launch_mode"
    private const val KEY_DEFAULT_TIMER = "default_timer_secs"
    private const val KEY_AUTO_STOP_COMPLETE = "auto_stop_on_complete"
    private const val KEY_KEEP_ALIVE_HOT = "keep_alive_hot"

    enum class TargetKind { INSTALLED_MC, IMPORTED_APK, RAW_SO }

    enum class LaunchMode(val title: String, val desc: String) {
        BG_MINIMAL_LINK(
            "BG Headless Link (Recommended)",
            "Loads MC .so graph + Zaphkiel in background :mcre via C++ dlopen (relocates all RTTI & vtables, zero game window, portrait stays untouched)"
        ),
        BG_LEVI_RUNTIME(
            "BG Full Levi v1.7.1 Runtime",
            "Full System.load + JNI_OnLoad (HttpClient, PlayFab, maesdk, MallocRedirect, preloader, MC) + Zaphkiel in background :mcre"
        ),
        LEVI_COMPANION(
            "LeviLauncher v1.7.1 Companion",
            "Writes config/targets to shared kurumi/, opens installed LeviLauncher v1.7.1 & monitors zaphkiel_log.txt"
        )
    }

    data class McTarget(
        val id: String,
        val label: String,
        val versionName: String,
        val kind: TargetKind,
        val dirPath: String,
        val flags: List<String>
    )

    data class InstalledModInfo(
        val name: String,
        val fileName: String,
        val path: String,
        val sizeBytes: Long,
        val isZaphkiel: Boolean,
        val enabled: Boolean
    )

    data class PreparedRuntime(
        val target: McTarget,
        val libDir: File,
        val orderedMcLibs: List<File>,
        val mcSoFile: File,
        val preloaderFile: File?,
        val zaphkielStagedFile: File?,
        val extraModFiles: List<File>,
        val workspaceMode: ZaphkielConfig.WorkspaceMode,
        val redirectCount: Int
    )

    private fun targetsRoot(context: Context) = File(context.filesDir, "mc_targets").apply { mkdirs() }
    private fun cacheLibsRoot(context: Context) = File(context.filesDir, "mc_lib_cache").apply { mkdirs() }
    private fun modsStoreDir(context: Context) = File(context.filesDir, "re_mods").apply { mkdirs() }
    private fun stagedModsDir(context: Context) = File(context.filesDir, "re_mods_staged").apply { mkdirs() }

    fun zaphkielRawFile(context: Context): File = File(modsStoreDir(context), "libZaphkiel.so")
    fun hasZaphkiel(context: Context): Boolean {
        val f = zaphkielRawFile(context)
        return f.isFile && f.length() > 1024
    }

    fun detectInstalledLeviPackage(context: Context): Pair<String, String>? {
        val pm = context.packageManager
        for (pkg in LEVI_PACKAGES) {
            try {
                val pi = pm.getPackageInfo(pkg, 0)
                return pkg to (pi.versionName ?: "installed")
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return null
    }

    fun detectInstalledMinecraft(context: Context): McTarget? {
        return try {
            val pi = context.packageManager.getPackageInfo(MC_PACKAGE, 0)
            val appInfo = pi.applicationInfo ?: return null
            val ver = pi.versionName ?: "unknown"
            McTarget(
                id = "installed_mc",
                label = "Installed Minecraft ($ver)",
                versionName = ver,
                kind = TargetKind.INSTALLED_MC,
                dirPath = appInfo.sourceDir ?: "",
                flags = MinecraftVersion.describeRuntimeFlags(ver)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun listAllTargets(context: Context): List<McTarget> {
        val list = mutableListOf<McTarget>()
        detectInstalledMinecraft(context)?.let { list.add(it) }

        val children = targetsRoot(context).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        for (dir in children) {
            if (!dir.isDirectory) continue
            val metaFile = File(dir, "target.json")
            if (!metaFile.isFile) continue
            runCatching {
                val o = JSONObject(metaFile.readText())
                val ver = o.optString("versionName", "")
                val kind = runCatching { TargetKind.valueOf(o.getString("kind")) }.getOrDefault(TargetKind.IMPORTED_APK)
                list.add(
                    McTarget(
                        id = dir.name,
                        label = o.optString("label", dir.name),
                        versionName = ver,
                        kind = kind,
                        dirPath = dir.absolutePath,
                        flags = MinecraftVersion.describeRuntimeFlags(ver)
                    )
                )
            }
        }
        return list
    }

    fun getSelectedTarget(context: Context): McTarget? {
        val all = listAllTargets(context)
        if (all.isEmpty()) return null
        val savedId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_TARGET, null)
        return all.firstOrNull { it.id == savedId } ?: all.first()
    }

    fun setSelectedTarget(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_TARGET, id).apply()
    }

    fun getLaunchMode(context: Context): LaunchMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAUNCH_MODE, LaunchMode.BG_MINIMAL_LINK.name)
        return LaunchMode.entries.firstOrNull { it.name == raw } ?: LaunchMode.BG_MINIMAL_LINK
    }

    fun setLaunchMode(context: Context, mode: LaunchMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAUNCH_MODE, mode.name).apply()
    }

    fun getDefaultTimerSecs(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_DEFAULT_TIMER, 30).coerceIn(5, 1800)
    }

    fun setDefaultTimerSecs(context: Context, secs: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_DEFAULT_TIMER, secs.coerceIn(5, 1800)).apply()
    }

    fun isAutoStopOnComplete(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_STOP_COMPLETE, true)
    }

    fun setAutoStopOnComplete(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_STOP_COMPLETE, enabled).apply()
    }

    fun isKeepAliveHot(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_ALIVE_HOT, false)
    }

    fun setKeepAliveHot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_KEEP_ALIVE_HOT, enabled).apply()
    }

    /**
     * Import a Minecraft .apk, .apks, .xapk, .apkm bundle, or raw libminecraftpe.so (even with .txt suffix).
     */
    fun importMcTargetFromUri(
        context: Context,
        uri: Uri,
        onLog: (String) -> Unit = {}
    ): Result<McTarget> = runCatching {
        val displayName = resolveDisplayName(context, uri) ?: "imported_mc"
        val cleanName = displayName.removeSuffix(".txt").removeSuffix(".bin")
        val stamp = System.currentTimeMillis()
        val id = "mc_$stamp"
        val targetDir = File(targetsRoot(context), id).apply { mkdirs() }
        val libOutDir = File(targetDir, "arm64-v8a").apply { mkdirs() }

        val tempDownload = File(context.cacheDir, "import_$stamp.tmp")
        try {
            onLog("Copying $displayName...")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempDownload).use { out -> input.copyTo(out, 256 * 1024) }
            } ?: throw IOException("Cannot open selected file")

            // Case 1: Direct ELF64 shared library (e.g. libminecraftpe.so or libminecraftpe.so.txt)
            if (startsWithElfMagic(tempDownload)) {
                val info = JSONObject(NativeBridge.inspectElf(tempDownload.absolutePath))
                if (!info.optBoolean("valid") || !info.optBoolean("isArm64")) {
                    throw IOException("Selected ELF is not a valid arm64-v8a .so")
                }
                val dst = File(libOutDir, "libminecraftpe.so")
                copyToReadOnly(tempDownload, dst)
                val target = McTarget(
                    id = id,
                    label = "Raw SO: $cleanName",
                    versionName = "",
                    kind = TargetKind.RAW_SO,
                    dirPath = targetDir.absolutePath,
                    flags = listOf("Raw ELF64", "arm64-v8a")
                )
                saveTargetMeta(targetDir, target)
                setSelectedTarget(context, id)
                onLog("Imported libminecraftpe.so (${dst.length() / (1024 * 1024)} MB)")
                return@runCatching target
            }

            // Case 2: Single APK or multi-APK archive (.apks / .xapk / .apkm / .zip)
            var detectedVersion = ""
            var extractedCount = 0
            ZipFile(tempDownload).use { zip ->
                val nestedApks = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }
                    .toList()

                if (nestedApks.isNotEmpty()) {
                    val splitsDir = File(targetDir, "apks").apply { mkdirs() }
                    for (entry in nestedApks) {
                        val safeName = File(entry.name).name
                        val apkOut = File(splitsDir, safeName)
                        zip.getInputStream(entry).use { inp ->
                            FileOutputStream(apkOut).use { out -> inp.copyTo(out, 256 * 1024) }
                        }
                        if (detectedVersion.isBlank()) {
                            detectedVersion = readApkVersion(context, apkOut)
                        }
                        extractedCount += extractArm64LibsFromApk(apkOut, libOutDir, onLog)
                    }
                } else {
                    val baseApk = File(targetDir, "base.apk")
                    tempDownload.copyTo(baseApk, overwrite = true)
                    detectedVersion = readApkVersion(context, baseApk)
                    extractedCount += extractArm64LibsFromApk(baseApk, libOutDir, onLog)
                }
            }

            val mcSo = File(libOutDir, "libminecraftpe.so")
            if (!mcSo.isFile || mcSo.length() == 0L) {
                targetDir.deleteRecursively()
                throw IOException(
                    "No arm64-v8a/libminecraftpe.so found in $displayName (extracted $extractedCount libs). " +
                        "If using split APKs, import the .apks/.xapk bundle containing split_config.arm64_v8a.apk."
                )
            }

            val verLabel = detectedVersion.ifBlank { cleanName.removeSuffix(".apk").removeSuffix(".apks") }
            val target = McTarget(
                id = id,
                label = "MC $verLabel (Isolated)",
                versionName = detectedVersion,
                kind = TargetKind.IMPORTED_APK,
                dirPath = targetDir.absolutePath,
                flags = MinecraftVersion.describeRuntimeFlags(detectedVersion)
            )
            saveTargetMeta(targetDir, target)
            setSelectedTarget(context, id)
            onLog("Ready: ${target.label} ($extractedCount arm64-v8a libraries)")
            target
        } finally {
            tempDownload.delete()
        }
    }

    /**
     * Add an additional split APK (e.g. split_config.arm64_v8a.apk) to an existing imported target.
     */
    fun deleteTarget(context: Context, targetId: String): Boolean {
        if (targetId == "installed_mc") return false
        File(cacheLibsRoot(context), targetId).deleteRecursively()
        return File(targetsRoot(context), targetId).deleteRecursively()
    }

    /**
     * Import libZaphkiel.so (accepts .so, .so.txt, or .levipack/.zip containing libZaphkiel.so)
     * or any additional preload-native RE mod.
     */
    fun importModFromUri(
        context: Context,
        uri: Uri,
        forceAsZaphkiel: Boolean = false
    ): Result<InstalledModInfo> = runCatching {
        val rawName = resolveDisplayName(context, uri) ?: "libZaphkiel.so"
        // Auto-strip trailing .txt (user convenience for libZaphkiel.so.txt uploads)
        val stripped = rawName.removeSuffix(".txt").removeSuffix(".bin")
        val tmp = File(context.cacheDir, "mod_import_${System.currentTimeMillis()}.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(tmp).use { out -> inp.copyTo(out, 128 * 1024) }
            } ?: throw IOException("Unable to read selected mod file")

            var soSource = tmp
            var chosenName = stripped
            var extractedTemp: File? = null

            if (!startsWithElfMagic(tmp)) {
                // Try opening as .levipack / .zip archive
                ZipFile(tmp).use { zip ->
                    var entryName: String? = null
                    val manifestEntry = zip.getEntry("manifest.json")
                    if (manifestEntry != null) {
                        val mJson = JSONObject(zip.getInputStream(manifestEntry).bufferedReader().readText())
                        entryName = mJson.optString("entry").takeIf { it.isNotBlank() }
                    }
                    val soEntry = (entryName?.let { en ->
                        zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith(en) }
                    }) ?: zip.entries().asSequence().firstOrNull {
                        !it.isDirectory && it.name.endsWith(".so") && !it.name.contains("armeabi") && !it.name.contains("x86")
                    } ?: throw IOException("File is neither an arm64 ELF .so nor a .levipack containing a .so")

                    chosenName = File(soEntry.name).name
                    extractedTemp = File(context.cacheDir, "mod_unzipped_${System.currentTimeMillis()}.so")
                    zip.getInputStream(soEntry).use { inp ->
                        FileOutputStream(extractedTemp!!).use { out -> inp.copyTo(out, 128 * 1024) }
                    }
                    soSource = extractedTemp!!
                }
            }

            val elfInfo = JSONObject(NativeBridge.inspectElf(soSource.absolutePath))
            if (!elfInfo.optBoolean("valid")) {
                throw IOException("Invalid ELF file: ${elfInfo.optString("error")}")
            }
            if (!elfInfo.optBoolean("isArm64")) {
                throw IOException("Mod must be arm64-v8a (AArch64)")
            }

            val exportsStr = elfInfo.optJSONArray("exports")?.toString().orEmpty()
            val isZaph = forceAsZaphkiel ||
                chosenName.contains("zaphkiel", ignoreCase = true) ||
                exportsStr.contains("zaphkiel_init")

            val finalFileName = if (isZaph) {
                "libZaphkiel.so"
            } else {
                val base = chosenName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val withLib = if (base.startsWith("lib")) base else "lib$base"
                if (withLib.endsWith(".so")) withLib else "$withLib.so"
            }

            val dest = File(modsStoreDir(context), finalFileName)
            if (dest.exists()) {
                dest.setWritable(true, true)
                dest.delete()
            }
            soSource.copyTo(dest, overwrite = true)
            setModEnabled(context, finalFileName, true)
            extractedTemp?.delete()

            InstalledModInfo(
                name = if (isZaph) "Zaphkiel RE Framework" else finalFileName.removePrefix("lib").removeSuffix(".so"),
                fileName = finalFileName,
                path = dest.absolutePath,
                sizeBytes = dest.length(),
                isZaphkiel = isZaph,
                enabled = true
            )
        } finally {
            tmp.delete()
        }
    }

    fun listInstalledMods(context: Context): List<InstalledModInfo> {
        val files = modsStoreDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?.sortedWith(compareByDescending<File> { it.name == "libZaphkiel.so" }.thenBy { it.name })
            .orEmpty()
        return files.map { f ->
            val isZ = f.name == "libZaphkiel.so"
            InstalledModInfo(
                name = if (isZ) "Zaphkiel RE Framework" else f.name.removePrefix("lib").removeSuffix(".so"),
                fileName = f.name,
                path = f.absolutePath,
                sizeBytes = f.length(),
                isZaphkiel = isZ,
                enabled = isModEnabled(context, f.name)
            )
        }
    }

    fun isModEnabled(context: Context, fileName: String): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("mod_enabled_$fileName", true)
    }

    fun setModEnabled(context: Context, fileName: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("mod_enabled_$fileName", enabled).apply()
    }

    fun deleteMod(context: Context, fileName: String) {
        val f = File(modsStoreDir(context), fileName)
        if (f.exists()) {
            f.setWritable(true, true)
            f.delete()
        }
    }

    /** Resolve active libminecraftpe.so path on disk (extracting cache if needed) for offline ELF tools too. */
    fun resolveMcSoPath(context: Context, onLog: (String) -> Unit = {}): File? {
        val target = getSelectedTarget(context) ?: return null
        return runCatching {
            val libDir = ensureTargetLibsExtracted(context, target, forceRefresh = false, onLog = onLog)
            File(libDir, "libminecraftpe.so").takeIf { it.isFile && it.length() > 0 }
        }.getOrNull()
    }

    /**
     * Prepare complete read-only native library directory, ordered load chain, and staged Zaphkiel .so.
     */
    fun prepareRuntime(
        context: Context,
        target: McTarget,
        forceReExtract: Boolean = false,
        onLog: (String) -> Unit = {}
    ): PreparedRuntime {
        val libDir = ensureTargetLibsExtracted(context, target, forceReExtract, onLog)
        val mcSo = File(libDir, "libminecraftpe.so")
        if (!mcSo.isFile || mcSo.length() == 0L) {
            throw IOException("libminecraftpe.so missing in ${libDir.absolutePath}")
        }
        ensureReadOnly(mcSo)

        val bundledDir = File(context.applicationInfo.nativeLibraryDir)
        val available = linkedMapOf<String, File>()

        // Index extracted MC libraries first
        libDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".so") && f.length() > 0) {
                ensureReadOnly(f)
                available[f.name] = f
            }
        }
        // Index LeviLauncher v1.7.1 bundled libraries (prefer bundled PlayFab & maesdk per Levi v1.7.1 d488f2c fix!)
        for (bName in BUNDLED_LEVI_LIBS) {
            val bf = File(bundledDir, bName)
            if (bf.isFile && bf.length() > 0) {
                if (bName == "libPlayFabMultiplayer.so" || bName == "libmaesdk.so" || bName !in available) {
                    available[bName] = bf
                }
            }
        }

        // Build exact Levi v1.7.1 + topological DT_NEEDED load sequence
        val ordered = mutableListOf<File>()
        val visited = mutableSetOf<String>()

        fun visitLib(soName: String) {
            if (soName in visited || soName in ANDROID_SYSTEM_LIBS || soName == "libzaphre_bridge.so") return
            val file = available[soName] ?: return
            visited.add(soName)
            // Recursively resolve its DT_NEEDED first
            val needed = readDtNeeded(file)
            for (dep in needed) {
                if (dep !in visited) visitLib(dep)
            }
            ordered.add(file)
        }

        val ver = target.versionName
        visitLib("libc++_shared.so")
        if (MinecraftVersion.requiresHttpClientAndPlayFab(ver)) {
            visitLib("libHttpClient.Android.so")
            visitLib("libPlayFabMultiplayer.so")
        }
        if (MinecraftVersion.requiresMaesdk(ver)) {
            visitLib("libmaesdk.so")
        }
        visitLib("libfmod.so")
        visitLib("libMediaDecoders_Android.so")
        if (MinecraftVersion.requiresMallocRedirect(ver) || "libMallocRedirect.so" in available) {
            visitLib("libMallocRedirect.so")
        }
        // Visits any remaining DT_NEEDED of libminecraftpe.so automatically, then libminecraftpe.so last
        visitLib("libminecraftpe.so")

        val preloaderFile = available["libpreloader.so"]

        // Stage Zaphkiel with workspace prefix matching active mode (61-byte exact match)
        val wsMode = ZaphkielConfig.getWorkspaceMode(context)
        ZaphkielConfig.ensureWorkspaceLayout(context)
        var stagedZaph: File? = null
        var replaced = 0
        val rawZaph = zaphkielRawFile(context)
        if (rawZaph.isFile && isModEnabled(context, rawZaph.name)) {
            val outName = if (wsMode == ZaphkielConfig.WorkspaceMode.ISOLATED) {
                "libZaphkiel_iso.so"
            } else {
                "libZaphkiel_shared.so"
            }
            val dst = File(stagedModsDir(context), outName)
            val newPrefix = if (wsMode == ZaphkielConfig.WorkspaceMode.ISOLATED) {
                ZaphkielConfig.ISOLATED_ZAPHKIEL_PATH
            } else {
                ""
            }
            replaced = NativeBridge.prepareZaphkielImage(
                srcPath = rawZaph.absolutePath,
                dstPath = dst.absolutePath,
                oldPrefix = ZaphkielConfig.LEVI_KURUMI_PATH,
                newPrefix = newPrefix
            ).getOrThrow()
            ensureReadOnly(dst)
            stagedZaph = dst
        }

        // Stage any additional enabled RE mods as read-only copies
        val extraMods = mutableListOf<File>()
        for (mod in listInstalledMods(context)) {
            if (mod.isZaphkiel || !mod.enabled) continue
            val src = File(mod.path)
            val dst = File(stagedModsDir(context), mod.fileName)
            copyToReadOnly(src, dst)
            extraMods.add(dst)
        }

        return PreparedRuntime(
            target = target,
            libDir = libDir,
            orderedMcLibs = ordered,
            mcSoFile = mcSo,
            preloaderFile = preloaderFile,
            zaphkielStagedFile = stagedZaph,
            extraModFiles = extraMods,
            workspaceMode = wsMode,
            redirectCount = replaced
        )
    }

    @Synchronized
    fun ensureTargetLibsExtracted(
        context: Context,
        target: McTarget,
        forceRefresh: Boolean,
        onLog: (String) -> Unit
    ): File {
        if (target.kind == TargetKind.IMPORTED_APK || target.kind == TargetKind.RAW_SO) {
            val dir = File(target.dirPath, "arm64-v8a")
            dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".so")) ensureReadOnly(it) }
            return dir
        }

        // Installed com.mojang.minecraftpe: extract/copy arm64-v8a .so set into read-only cache
        val pkgCtx = context.createPackageContext(MC_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        val appInfo = pkgCtx.applicationInfo
        val apks = buildList {
            appInfo.sourceDir?.let { add(File(it)) }
            appInfo.splitPublicSourceDirs?.forEach { add(File(it)) }
        }.filter { it.isFile }.sortedBy { it.absolutePath }

        val fingerprint = buildString {
            appendLine("extractor=zaphre_v1")
            appendLine("ver=${target.versionName}")
            for (a in apks) appendLine("${a.absolutePath}|${a.length()}|${a.lastModified()}")
        }

        val outDir = File(cacheLibsRoot(context), "installed_mc_arm64").apply { mkdirs() }
        val marker = File(outDir, ".manifest")
        val mcSo = File(outDir, "libminecraftpe.so")
        if (!forceRefresh && marker.isFile && mcSo.isFile && mcSo.length() > 0 &&
            runCatching { marker.readText() }.getOrNull() == fingerprint
        ) {
            outDir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".so")) ensureReadOnly(it) }
            onLog("MC library cache hit (${target.versionName})")
            return outDir
        }

        // Build a fresh cache whenever the APK fingerprint changes. Never reuse .so files
        // from the previous version: APKs can lack extracted nativeLibraryDir entries.
        val staging = File(cacheLibsRoot(context), "installed_mc_staging-${java.util.UUID.randomUUID()}")
        if (!staging.mkdirs()) throw IOException("Could not create Minecraft extraction staging directory")
        try {
        onLog("Extracting Minecraft ${target.versionName} arm64-v8a libraries...")
        var count = 0
        // Copy from nativeLibraryDir first if system already extracted them
        val sysNative = appInfo.nativeLibraryDir?.let(::File)
        if (sysNative != null && sysNative.isDirectory) {
            sysNative.listFiles()?.forEach { src ->
                if (src.isFile && src.name.endsWith(".so") && src.length() > 0 &&
                    src.name != "libPlayFabMultiplayer.so" && src.name != "libmaesdk.so"
                ) {
                    val dst = File(staging, src.name)
                    copyToReadOnly(src, dst)
                    count++
                    onLog("Copied ${src.name} (${src.length() / 1024} KB)")
                }
            }
        }
        // Extract any remaining arm64-v8a/*.so from base + split APKs
        for (apk in apks) {
            count += extractArm64LibsFromApk(apk, staging, onLog, skipExisting = true)
        }
        val stagedMc = File(staging, "libminecraftpe.so")
        if (!stagedMc.isFile || stagedMc.length() == 0L) {
            throw IOException("Installed Minecraft (${target.versionName}) does not contain arm64-v8a/libminecraftpe.so")
        }
        File(staging, ".manifest").writeText(fingerprint)
        val old = File(cacheLibsRoot(context), "installed_mc_previous-${java.util.UUID.randomUUID()}")
        if (outDir.exists() && !outDir.renameTo(old)) throw IOException("Could not preserve previous Minecraft cache")
        if (!staging.renameTo(outDir)) {
            if (old.exists() && !old.renameTo(outDir)) onLog("Previous cache retained at ${old.name}")
            throw IOException("Could not install refreshed Minecraft cache")
        }
        old.deleteRecursively()
        onLog("Prepared $count native libraries for MC ${target.versionName}")
        return outDir
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun extractArm64LibsFromApk(
        apkFile: File,
        outDir: File,
        onLog: (String) -> Unit,
        skipExisting: Boolean = false
    ): Int {
        var count = 0
        ZipFile(apkFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val n = e.name
                if (!n.startsWith("lib/arm64-v8a/") || !n.endsWith(".so")) continue
                val soName = n.substringAfterLast('/')
                if (soName.isEmpty() || soName.contains("..")) continue
                // Skip PlayFab/maesdk from MC APK when our Levi v1.7.1 bundled copies are used
                val dst = File(outDir, soName)
                if (skipExisting && dst.isFile && dst.length() > 0) continue
                zip.getInputStream(e).use { inp ->
                    streamToReadOnly(inp, dst)
                }
                count++
                onLog("Extracted $soName from ${apkFile.name}")
            }
        }
        return count
    }

    private fun readDtNeeded(soFile: File): List<String> {
        return runCatching {
            val j = JSONObject(NativeBridge.inspectElf(soFile.absolutePath))
            val arr: JSONArray = j.optJSONArray("needed") ?: return emptyList()
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun readApkVersion(context: Context, apkFile: File): String {
        return runCatching {
            val pm = context.packageManager
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
            pi?.versionName.orEmpty()
        }.getOrDefault("")
    }

    private fun saveTargetMeta(dir: File, target: McTarget) {
        val o = JSONObject().apply {
            put("id", target.id)
            put("label", target.label)
            put("versionName", target.versionName)
            put("kind", target.kind.name)
        }
        File(dir, "target.json").writeText(o.toString(2))
    }

    private fun startsWithElfMagic(file: File): Boolean {
        if (!file.isFile || file.length() < 16) return false
        return runCatching {
            val hdr = ByteArray(4)
            file.inputStream().use { if (it.read(hdr) != 4) return false }
            hdr[0] == 0x7f.toByte() && hdr[1] == 'E'.code.toByte() &&
                hdr[2] == 'L'.code.toByte() && hdr[3] == 'F'.code.toByte()
        }.getOrDefault(false)
    }

    private fun copyToReadOnly(src: File, dst: File) {
        src.inputStream().use { streamToReadOnly(it, dst) }
    }

    private fun streamToReadOnly(input: InputStream, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.absolutePath + ".tmp")
        if (tmp.exists()) {
            tmp.setWritable(true, true)
            tmp.delete()
        }
        FileOutputStream(tmp).use { out ->
            input.copyTo(out, 256 * 1024)
            out.fd.sync()
        }
        tmp.setReadable(true, true)
        tmp.setReadOnly()
        if (dst.exists()) {
            dst.setWritable(true, true)
            dst.delete()
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            throw IOException("Failed to move staged library into ${dst.absolutePath}")
        }
        ensureReadOnly(dst)
    }

    fun ensureReadOnly(file: File) {
        if (!file.isFile) return
        file.setReadable(true, true)
        if (file.canWrite()) file.setReadOnly()
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
