package org.levimc.zaphre.ai

/** Export only the selected session; never include provider settings or API keys. */
object ChatExport {
    fun format(session: ChatSession): String = buildString {
        appendLine("ZaphRE chat: ${session.title}")
        appendLine("Session ID: ${session.id}")
        appendLine("Note: saved tool outputs may have been truncated by chat history; check original workspace files for full evidence.")
        appendLine()
        session.messages.forEach { message ->
            appendLine("=== ${message.role.name}${if (message.modelTag.isNotBlank()) " · ${message.modelTag}" else ""} ===")
            message.toolSteps.forEach { step ->
                appendLine("--- Tool: ${step.toolName} (${step.argsSummary}) ${if (step.isError) "ERROR" else ""} ---")
                appendLine(step.output)
            }
            appendLine(message.text)
            appendLine()
        }
    }
}
