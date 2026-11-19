package org.levimc.zaphre.mc

/**
 * Exact Minecraft Bedrock version comparison rules aligned with LeviLauncher-Android v1.7.1:
 *  - Supports 1.21.80+ through 1.21.130+ and Mojang's 26.x / 1.26.70.00+ versioning
 *  - Handles Release, Preview, and Beta strings (e.g. "1.21.130.20 beta", "26.70.0.1", "1.26.70.02")
 */
object MinecraftVersion {
    private val NUMBER_REGEX = Regex("\\d+(?:\\.\\d+)*")

    fun isAtLeast(current: String?, minimum: String): Boolean {
        val left = components(current)
        val right = components(minimum)
        if (left.isEmpty() || right.isEmpty()) return false
        val maxLen = maxOf(left.size, right.size)
        for (i in 0 until maxLen) {
            val a = if (i < left.size) left[i] else 0
            val b = if (i < right.size) right[i] else 0
            if (a != b) return a > b
        }
        return true
    }

    fun isBeta(versionName: String?): Boolean {
        val v = versionName?.lowercase() ?: return false
        return "beta" in v || "preview" in v
    }

    /** LeviLauncher v1.7.1: minimum supported MC version */
    fun isSupported(versionName: String?): Boolean {
        if (versionName.isNullOrBlank()) return true // unknown raw .so still allowed
        return isAtLeast(versionName, "1.21.80")
    }

    /** LeviLauncher v1.7.1: 1.21.110+ / 1.21.110.22 beta+ uses libmaesdk.so bundle layout */
    fun requiresMaesdk(versionName: String?): Boolean {
        if (versionName.isNullOrBlank()) return true
        val min = if (isBeta(versionName)) "1.21.110.22" else "1.21.110"
        return isAtLeast(versionName, min)
    }

    /** LeviLauncher v1.7.1: 1.21.130+ / 1.21.130.20 beta+ requires libHttpClient.Android.so & libPlayFabMultiplayer.so */
    fun requiresHttpClientAndPlayFab(versionName: String?): Boolean {
        if (versionName.isNullOrBlank()) return true
        val min = if (isBeta(versionName)) "1.21.130.20" else "1.21.130"
        return isAtLeast(versionName, min)
    }

    /** LeviLauncher v1.7.1: 1.26.70.00+ / 26.70+ links libMallocRedirect.so before libminecraftpe.so */
    fun requiresMallocRedirect(versionName: String?): Boolean {
        if (versionName.isNullOrBlank()) return false
        return isAtLeast(versionName, "1.26.70.00")
    }

    fun describeRuntimeFlags(versionName: String?): List<String> = buildList {
        if (versionName.isNullOrBlank()) {
            add("Auto ELF DT_NEEDED")
            return@buildList
        }
        if (isBeta(versionName)) add("Beta/Preview")
        if (requiresMaesdk(versionName)) add("maesdk")
        if (requiresHttpClientAndPlayFab(versionName)) {
            add("HttpClient")
            add("PlayFab")
        }
        if (requiresMallocRedirect(versionName)) add("MallocRedirect (26.70+)")
        add("Levi v1.7.1")
    }

    fun components(value: String?): IntArray {
        if (value.isNullOrBlank()) return IntArray(0)
        val match = NUMBER_REGEX.find(value) ?: return IntArray(0)
        val parts = match.value.split(".")
        return try {
            // Match LeviLauncher v1.7.1: "26.x.y" normalizes to [1, 26, x, y]
            val prefix = if (parts[0].toInt() >= 26) 1 else 0
            IntArray(parts.size + prefix) { idx ->
                if (prefix == 1 && idx == 0) 1 else parts[idx - prefix].toInt()
            }
        } catch (_: NumberFormatException) {
            IntArray(0)
        }
    }
}
