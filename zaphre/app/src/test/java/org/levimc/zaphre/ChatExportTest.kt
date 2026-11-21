package org.levimc.zaphre

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.levimc.zaphre.ai.*

class ChatExportTest {
    @Test fun exportIncludesEvidenceAndMessages() {
        val session = ChatSession(id = "session", title = "Water", messages = listOf(
            ChatUiMessage(role = ChatRole.USER, text = "Find water"),
            ChatUiMessage(role = ChatRole.ASSISTANT, text = "Check evidence", modelTag = "Gemini", toolSteps = listOf(
                ToolStepRecord(toolName = "elf_static_scan", argsSummary = "strings water", output = "hit at 0x10")
            ))
        ))
        val output = ChatExport.format(session)
        assertTrue(output.contains("Find water"))
        assertTrue(output.contains("Check evidence"))
        assertTrue(output.contains("hit at 0x10"))
        assertTrue(output.contains("truncated"))
        assertFalse(output.contains("API key:"))
    }
}
