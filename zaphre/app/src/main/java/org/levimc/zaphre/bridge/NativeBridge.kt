package org.levimc.zaphre.bridge

import android.util.Log

object NativeBridge {
    private const val TAG = "NativeBridge"

    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return synchronized(this) {
            if (loaded) return@synchronized true
            try {
                System.loadLibrary("zaphre_bridge")
                loaded = true
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load libzaphre_bridge.so", t)
                false
            }
        }
    }

    fun installCrashHandler(crashLogPath: String) {
        if (ensureLoaded()) nativeInstallCrashHandler(crashLogPath)
    }

    fun setStage(stage: String) {
        if (ensureLoaded()) nativeSetStage(stage)
    }

    fun dlopen(path: String, global: Boolean = true, lazy: Boolean = false): String {
        if (!ensureLoaded()) return "libzaphre_bridge.so unavailable"
        return nativeDlopen(path, global, lazy)
    }

    fun loadZaphkiel(path: String): String {
        if (!ensureLoaded()) return "ERROR: libzaphre_bridge.so unavailable"
        return nativeLoadZaphkiel(path)
    }

    fun retriggerZaphkiel(includeSigGen: Boolean = false): Boolean {
        if (!ensureLoaded()) return false
        return nativeRetriggerZaphkiel(includeSigGen)
    }

    fun getModuleInfo(libSubstr: String): Triple<String, String, String>? {
        if (!ensureLoaded()) return null
        val raw = nativeGetModuleInfo(libSubstr)
        if (raw.isBlank()) return null
        val parts = raw.split("|", limit = 3)
        if (parts.size < 3) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    fun getRssMb(): Int {
        if (!ensureLoaded()) return 0
        return nativeGetRssMb()
    }

    fun prepareZaphkielImage(
        srcPath: String,
        dstPath: String,
        oldPrefix: String,
        newPrefix: String
    ): Result<Int> {
        if (!ensureLoaded()) {
            return Result.failure(IllegalStateException("libzaphre_bridge.so unavailable"))
        }
        val res = nativePrepareZaphkielImage(srcPath, dstPath, oldPrefix, newPrefix)
        return if (res.startsWith("OK:")) {
            Result.success(res.removePrefix("OK:").toIntOrNull() ?: 0)
        } else {
            Result.failure(IllegalStateException(res.removePrefix("ERROR: ")))
        }
    }

    fun inspectElf(path: String): String {
        if (!ensureLoaded()) return """{"valid":false,"error":"bridge unavailable"}"""
        return nativeInspectElf(path)
    }

    fun searchStrings(path: String, query: String, caseSensitive: Boolean = false, maxHits: Int = 80): String {
        if (!ensureLoaded()) return "ERROR: bridge unavailable"
        return nativeSearchStrings(path, query, caseSensitive, maxHits)
    }

    fun searchRtti(path: String, query: String, maxHits: Int = 80): String {
        if (!ensureLoaded()) return "ERROR: bridge unavailable"
        return nativeSearchRtti(path, query, maxHits)
    }

    fun searchDynsym(path: String, query: String, maxHits: Int = 80): String {
        if (!ensureLoaded()) return "ERROR: bridge unavailable"
        return nativeSearchDynsym(path, query, maxHits)
    }

    fun scanSignature(path: String, pattern: String, maxHits: Int = 32): String {
        if (!ensureLoaded()) return "ERROR: bridge unavailable"
        return nativeScanSignature(path, pattern, maxHits)
    }

    fun readVa(path: String, va: Long, byteCount: Int = 96): String {
        if (!ensureLoaded()) return "ERROR: bridge unavailable"
        return nativeReadVa(path, va, byteCount)
    }

    @JvmStatic private external fun nativeInstallCrashHandler(crashLogPath: String)
    @JvmStatic private external fun nativeSetStage(stage: String)
    @JvmStatic private external fun nativeDlopen(path: String, global: Boolean, lazy: Boolean): String
    @JvmStatic private external fun nativeLoadZaphkiel(path: String): String
    @JvmStatic private external fun nativeRetriggerZaphkiel(includeSigGen: Boolean): Boolean
    @JvmStatic private external fun nativeGetModuleInfo(libSubstr: String): String
    @JvmStatic private external fun nativeGetRssMb(): Int
    @JvmStatic private external fun nativePrepareZaphkielImage(
        srcPath: String,
        dstPath: String,
        oldPrefix: String,
        newPrefix: String
    ): String
    @JvmStatic private external fun nativeInspectElf(path: String): String
    @JvmStatic private external fun nativeSearchStrings(
        path: String,
        query: String,
        caseSensitive: Boolean,
        maxHits: Int
    ): String
    @JvmStatic private external fun nativeSearchRtti(path: String, query: String, maxHits: Int): String
    @JvmStatic private external fun nativeSearchDynsym(path: String, query: String, maxHits: Int): String
    @JvmStatic private external fun nativeScanSignature(path: String, pattern: String, maxHits: Int): String
    @JvmStatic private external fun nativeReadVa(path: String, va: Long, byteCount: Int): String
}
