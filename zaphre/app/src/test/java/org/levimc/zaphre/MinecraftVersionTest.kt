package org.levimc.zaphre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.levimc.zaphre.ai.BUILTIN_PROVIDERS
import org.levimc.zaphre.ai.ReTools
import org.levimc.zaphre.mc.MinecraftVersion
import org.levimc.zaphre.mc.ZaphkielConfig

class MinecraftVersionTest {

    @Test
    fun `matches LeviLauncher v1_7_1 version rules including 26_x and beta`() {
        assertFalse(MinecraftVersion.isSupported("1.21.70"))
        assertTrue(MinecraftVersion.isSupported("1.21.80"))
        assertTrue(MinecraftVersion.isSupported("1.21.130"))
        assertTrue(MinecraftVersion.isSupported("26.0.2"))
        assertTrue(MinecraftVersion.isSupported("26.70.0.1 beta"))

        assertFalse(MinecraftVersion.requiresMaesdk("1.21.100"))
        assertTrue(MinecraftVersion.requiresMaesdk("1.21.110"))
        assertTrue(MinecraftVersion.requiresMaesdk("1.21.110.22 beta"))

        assertFalse(MinecraftVersion.requiresHttpClientAndPlayFab("1.21.124"))
        assertTrue(MinecraftVersion.requiresHttpClientAndPlayFab("1.21.130"))
        assertTrue(MinecraftVersion.requiresHttpClientAndPlayFab("1.21.130.20 beta"))
        assertTrue(MinecraftVersion.requiresHttpClientAndPlayFab("26.10.0"))

        assertFalse(MinecraftVersion.requiresMallocRedirect("1.21.130"))
        assertFalse(MinecraftVersion.requiresMallocRedirect("26.60.0"))
        assertTrue(MinecraftVersion.requiresMallocRedirect("1.26.70.00"))
        assertTrue(MinecraftVersion.requiresMallocRedirect("26.70.0"))
        assertTrue(MinecraftVersion.requiresMallocRedirect("26.80.20 beta"))
    }

    @Test
    fun `zaphkiel workspace redirect prefixes are exact 61 bytes`() {
        assertEquals(61, ZaphkielConfig.LEVI_KURUMI_PATH.toByteArray(Charsets.UTF_8).size)
        assertEquals(61, ZaphkielConfig.ISOLATED_ZAPHKIEL_PATH.toByteArray(Charsets.UTF_8).size)
        assertTrue(ZaphkielConfig.LEVI_KURUMI_PATH.endsWith("/"))
        assertTrue(ZaphkielConfig.ISOLATED_ZAPHKIEL_PATH.endsWith("/"))
    }

    @Test
    fun `tool schemas and AI providers are valid`() {
        val schemas = ReTools.schemas()
        assertTrue(schemas.size >= 10)
        val names = schemas.map { it.name }.toSet()
        assertTrue("zaphkiel_run" in names)
        assertTrue("zaphkiel_sig" in names)
        assertTrue("elf_static_scan" in names)
        assertTrue("run_cmd" in names)
        assertTrue("fs_search" in names)

        assertTrue(BUILTIN_PROVIDERS.any { it.id == "gemini" && !it.isLocal })
        assertTrue(BUILTIN_PROVIDERS.any { it.id == "openrouter" && !it.isLocal })
        assertTrue(BUILTIN_PROVIDERS.any { it.id == "anthropic" && !it.isLocal })
        assertTrue(BUILTIN_PROVIDERS.any { it.id == "local_llamacpp" && it.isLocal })
        assertTrue(BUILTIN_PROVIDERS.any { it.id == "local_ollama" && it.isLocal })
    }
}
