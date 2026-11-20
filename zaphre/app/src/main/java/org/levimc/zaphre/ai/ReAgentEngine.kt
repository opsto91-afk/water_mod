package org.levimc.zaphre.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.levimc.zaphre.mc.BgController
import org.levimc.zaphre.mc.ZaphkielConfig

/**
 * Runs the autonomous multi-turn Ghidra-MCP style RE loop:
 *   User Prompt -> LLM -> Tool Calls (Zaphkiel / BG MC / ELF / CMD / Files) -> LLM -> ... -> Final Report
 */
object ReAgentEngine {

    interface StepListener {
        fun onAssistantDraft(text: String, steps: List<ToolStepRecord>, stepIndex: Int)
    }

    suspend fun runTurn(
        context: Context,
        priorMessages: List<ChatUiMessage>,
        userPrompt: String,
        listener: StepListener
    ): ChatUiMessage {
        val appCtx = context.applicationContext
        val settings = AiSettingsStore.load(appCtx)
        val wsPath = ZaphkielConfig.getWorkspacePathSlash(appCtx)
        val sysPrompt = if (settings.preset.isLocal) {
            ReSystemPrompt.buildLocal(wsPath, settings.customPrompt)
        } else {
            ReSystemPrompt.build(wsPath, settings.customPrompt)
        }
        val tools = ReTools.schemas()

        // Rebuild conversation history cleanly (keep last 18 messages to preserve context budget)
        val history = mutableListOf<AgentTurnItem>()
        for (m in priorMessages.takeLast(18)) {
            when (m.role) {
                ChatRole.USER -> history.add(AgentTurnItem.User(m.text))
                ChatRole.ASSISTANT -> {
                    val summary = buildString {
                        if (m.toolSteps.isNotEmpty()) {
                            appendLine("[Prior RE tools run: ${m.toolSteps.joinToString { it.toolName }}]")
                        }
                        append(m.text)
                    }.trim()
                    if (summary.isNotEmpty()) history.add(AgentTurnItem.Assistant(summary))
                }
                ChatRole.SYSTEM_NOTE -> Unit
            }
        }
        history.add(AgentTurnItem.User(userPrompt))

        val recordedSteps = mutableListOf<ToolStepRecord>()
        var accumulatedText = ""
        val modelBadge = "${settings.preset.name.substringBefore(" (")} · ${settings.model}"

        try {
            for (step in 1..settings.maxAgentSteps) {
                listener.onAssistantDraft(
                    text = accumulatedText.ifBlank { "Thinking (step $step/${settings.maxAgentSteps})..." },
                    steps = recordedSteps.toList(),
                    stepIndex = step
                )

                val response = AiApiClient.chat(
                    settings = settings,
                    systemPrompt = sysPrompt,
                    history = history,
                    tools = tools
                )

                history.add(
                    AgentTurnItem.Assistant(
                        text = response.text,
                        toolCalls = response.toolCalls,
                        rawGeminiParts = response.rawGeminiParts
                    )
                )

                if (response.toolCalls.isEmpty()) {
                    val finalText = response.text.ifBlank {
                        accumulatedText.ifBlank { "Done. (Check tool step results above.)" }
                    }
                    return ChatUiMessage(
                        role = ChatRole.ASSISTANT,
                        text = finalText,
                        toolSteps = recordedSteps.toList(),
                        modelTag = modelBadge
                    )
                }

                if (response.text.isNotBlank()) {
                    accumulatedText = response.text
                }

                // Execute every requested tool sequentially and stream progress to Chat UI
                for (tc in response.toolCalls) {
                    val runningRec = ToolStepRecord(
                        id = tc.id,
                        toolName = tc.name,
                        argsSummary = ReTools.summarizeArgs(tc.name, tc.args),
                        running = true
                    )
                    recordedSteps.add(runningRec)
                    val recIdx = recordedSteps.lastIndex
                    listener.onAssistantDraft(accumulatedText, recordedSteps.toList(), step)

                    val t0 = System.currentTimeMillis()
                    val out = ReTools.execute(appCtx, tc)
                    val dt = System.currentTimeMillis() - t0
                    val isErr = out.startsWith("ERROR")

                    recordedSteps[recIdx] = runningRec.copy(
                        output = out,
                        running = false,
                        isError = isErr,
                        durationMs = dt
                    )
                    listener.onAssistantDraft(accumulatedText, recordedSteps.toList(), step)

                    history.add(
                        AgentTurnItem.ToolResult(
                            callId = tc.id,
                            toolName = tc.name,
                            output = out.take(26_000)
                        )
                    )
                }
            }

            return ChatUiMessage(
                role = ChatRole.ASSISTANT,
                text = (accumulatedText + "\n\n*(Reached max ${settings.maxAgentSteps} autonomous steps — reply 'continue' to keep going or raise Max Steps in AI settings.)*").trim(),
                toolSteps = recordedSteps.toList(),
                modelTag = modelBadge
            )
        } catch (ce: CancellationException) {
            if (settings.autoStopMcAfterLoop) {
                runCatching { BgController.stopNow(appCtx) }
            }
            throw ce
        } catch (t: Throwable) {
            return ChatUiMessage(
                role = ChatRole.ASSISTANT,
                text = "**Agent Error:** ${t.message ?: t.javaClass.simpleName}\n\n" +
                    "Tip: Check your API key / model / local server URL in the **AI** tab.",
                toolSteps = recordedSteps.toList(),
                modelTag = modelBadge
            )
        } finally {
            if (settings.autoStopMcAfterLoop) {
                val snap = BgController.readVerifiedSnapshot(appCtx)
                if (snap.status.isActive) {
                    runCatching { BgController.stopNow(appCtx) }
                }
            }
        }
    }
}
