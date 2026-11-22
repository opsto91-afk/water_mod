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
        suspend fun awaitPermission() {}
        suspend fun confirmWorkspaceWrite(path: String, append: Boolean, content: String): Boolean = false
        suspend fun confirmAfterInterruption(toolName: String, arguments: String): Boolean = false
        suspend fun confirmGitHubCreate(path: String, content: String, message: String, target: String, update: Boolean): Boolean = false
        suspend fun confirmSelectedModify(path: String, deleting: Boolean, content: String, tree: String): Boolean = false
        suspend fun confirmSelectedCreate(path: String, directory: Boolean, content: String, tree: String): Boolean = false
        suspend fun confirmKeySwitch(keyNumber: Int): Boolean = false
    }

    suspend fun runTurn(
        context: Context,
        priorMessages: List<ChatUiMessage>,
        userPrompt: String,
        listener: StepListener,
        media: MediaAttachment.Item? = null,
        sessionId: String? = null,
        recovery: AgentCheckpoint.Snapshot? = null
    ): ChatUiMessage {
        val appCtx = context.applicationContext
        val settings = AiSettingsStore.load(appCtx)
        val wsPath = ZaphkielConfig.getWorkspacePathSlash(appCtx)
        val sysPrompt = if (settings.preset.isLocal) {
            ReSystemPrompt.buildLocal(wsPath, settings.customPrompt)
        } else {
            ReSystemPrompt.build(wsPath, settings.customPrompt)
        }
        val tools = ReTools.schemas().filter { (it.name != "web_search" || WebSearch.enabled(appCtx)) && (!it.name.startsWith("github_") || GitHubIntegration.config(appCtx).enabled) }

        // Rebuild conversation history cleanly (keep last 18 messages to preserve context budget)
        val history = mutableListOf<AgentTurnItem>()
        if (recovery == null) for (m in priorMessages.takeLast(18)) {
            when (m.role) {
                ChatRole.USER -> history.add(AgentTurnItem.User(m.text))
                ChatRole.ASSISTANT -> {
                    val summary = buildString {
                        if (m.toolSteps.isNotEmpty()) {
                            appendLine("[Prior RE tool records; outputs may be truncated]")
                            for (record in m.toolSteps.takeLast(8)) {
                                appendLine("Tool: ${record.toolName} (${record.argsSummary.take(200)})")
                                if (record.running) appendLine("Outcome unknown: this tool was interrupted. Do not repeat side-effecting work without user review.")
                                else appendLine(record.output.take(2500))
                            }
                        }
                        append(m.text)
                    }.trim()
                    if (summary.isNotEmpty()) history.add(AgentTurnItem.Assistant(summary))
                }
                ChatRole.SYSTEM_NOTE -> Unit
            }
        }
        if (recovery == null) history.add(AgentTurnItem.User(userPrompt, media)) else history.addAll(recovery.history)

        // A killed process may have interrupted a tool after side effects. Require review on
        // subsequent tool calls; never infer that the old operation did or did not finish.
        val hasUncertainTool = recovery?.needsReview ?: priorMessages.any { message -> message.toolSteps.any { it.running } }
        val repeatedLocalCalls = mutableMapOf<String, Int>()
        if (settings.preset.isLocal && recovery != null) {
            recovery.history.filterIsInstance<AgentTurnItem.Assistant>().flatMap { it.toolCalls }.forEach { call ->
                val signature = call.name + "\u0000" + call.args.toString()
                repeatedLocalCalls[signature] = (repeatedLocalCalls[signature] ?: 0) + 1
            }
        }
        val recordedSteps = recovery?.steps?.toMutableList() ?: mutableListOf()
        var accumulatedText = recovery?.text ?: ""
        val canCheckpoint = sessionId != null && media == null
        if (recovery != null) require(recovery.state == "safe" && recovery.sessionId == sessionId && recovery.providerId == settings.providerId && recovery.baseUrl == settings.baseUrl && recovery.model == settings.model) { "Saved job settings changed; continue manually after reviewing its checkpoint" }
        val modelBadge = "${settings.preset.name.substringBefore(" (")} · ${settings.model}"

        fun checkpoint(state: String, step: Int, result: ChatUiMessage? = null) {
            if (canCheckpoint) AgentCheckpoint.save(appCtx, AgentCheckpoint.Snapshot(sessionId!!, settings.providerId, settings.baseUrl, settings.model,
                step, accumulatedText, history.toList(), recordedSteps.toList(), state, result, hasUncertainTool))
        }
        fun finish(result: ChatUiMessage, step: Int): ChatUiMessage {
            checkpoint("completed", step, result)
            return result
        }

        try {
            val startStep = recovery?.nextStep ?: 1
            checkpoint("safe", startStep)
            for (step in startStep..settings.maxAgentSteps) {
                listener.awaitPermission()
                listener.onAssistantDraft(
                    text = accumulatedText.ifBlank { "Thinking (step $step/${settings.maxAgentSteps})..." },
                    steps = recordedSteps.toList(),
                    stepIndex = step
                )

                val response = AiApiClient.chat(
                    settings = settings,
                    systemPrompt = sysPrompt,
                    history = history,
                    tools = tools,
                    onPartial = { partial -> listener.onAssistantDraft(partial, recordedSteps.toList(), step) },
                    confirmKeySwitch = { keyNumber -> listener.confirmKeySwitch(keyNumber) },
                    awaitPermission = { listener.awaitPermission() }
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
                    return finish(ChatUiMessage(
                        role = ChatRole.ASSISTANT,
                        text = finalText,
                        toolSteps = recordedSteps.toList(),
                        modelTag = modelBadge
                    ), step)
                }

                if (response.text.isNotBlank()) {
                    accumulatedText = response.text
                }

                // Mark the entire tool batch unsafe before executing anything. If killed mid-batch,
                // recovery requires human review rather than replaying unknown side effects.
                checkpoint("unsafe", step)
                // Execute every requested tool sequentially and stream progress to Chat UI
                for (tc in response.toolCalls) {
                    listener.awaitPermission()
                    if (settings.preset.isLocal) {
                        val signature = tc.name + "\u0000" + tc.args.toString()
                        val count = (repeatedLocalCalls[signature] ?: 0) + 1
                        repeatedLocalCalls[signature] = count
                        if (count > 2) return finish(ChatUiMessage(role = ChatRole.ASSISTANT,
                            text = (accumulatedText + "\n\n*(Stopped: local model repeated ${tc.name}. Try a narrower question.)*").trim(),
                            toolSteps = recordedSteps.toList(), modelTag = modelBadge), step)
                    }
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
                    val executionCall = tc.copy(args = org.json.JSONObject(tc.args.toString()))
                    if (tc.name == "github_create_file" || tc.name == "github_update_file") {
                        val cfg = GitHubIntegration.config(appCtx)
                        executionCall.args.put("_approved_owner", cfg.owner).put("_approved_repo", cfg.repo).put("_approved_branch", cfg.branch)
                    }
                    if (tc.name == "selected_create") executionCall.args.put("_approved_tree", org.levimc.zaphre.files.BackupFolder.selected(appCtx)?.toString() ?: "")
                    if (tc.name == "selected_modify") executionCall.args.put("_approved_tree", org.levimc.zaphre.files.BackupFolder.selected(appCtx)?.toString() ?: "")
                    val denied = when {
                        hasUncertainTool && !listener.confirmAfterInterruption(tc.name, executionCall.args.toString().take(2000)) -> "ERROR: Tool denied pending review of interrupted work."
                        tc.name == "fs_write" && !listener.confirmWorkspaceWrite(executionCall.args.optString("path"), executionCall.args.optBoolean("append"), executionCall.args.optString("content")) -> "ERROR: User declined workspace write."
                        tc.name == "selected_modify" && !listener.confirmSelectedModify(executionCall.args.optString("path"), executionCall.args.optBoolean("delete"), executionCall.args.optString("content"), executionCall.args.optString("_approved_tree")) -> "ERROR: User declined file modification."
                        (tc.name == "github_create_file" || tc.name == "github_update_file") && (!GitHubIntegration.config(appCtx).enabled || !listener.confirmGitHubCreate(executionCall.args.optString("path"), executionCall.args.optString("content"), executionCall.args.optString("message"), "${executionCall.args.optString("_approved_owner")}/${executionCall.args.optString("_approved_repo")} @ ${executionCall.args.optString("_approved_branch")}", tc.name == "github_update_file")) -> "ERROR: GitHub disabled or user declined."
                        tc.name == "selected_create" && !listener.confirmSelectedCreate(executionCall.args.optString("path"), executionCall.args.optBoolean("directory"), executionCall.args.optString("content"), executionCall.args.optString("_approved_tree")) -> "ERROR: User declined creation in selected folder."
                        else -> null
                    }
                    val out = if (denied != null) denied else {
                        listener.awaitPermission()
                        ReTools.execute(appCtx, executionCall)
                    }
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
                checkpoint("safe", step + 1)
            }

            return finish(ChatUiMessage(
                role = ChatRole.ASSISTANT,
                text = (accumulatedText + "\n\n*(Reached max ${settings.maxAgentSteps} autonomous steps — reply 'continue' to keep going or raise Max Steps in AI settings.)*").trim(),
                toolSteps = recordedSteps.toList(),
                modelTag = modelBadge
            ), settings.maxAgentSteps + 1)
        } catch (ce: CancellationException) {
            if (settings.autoStopMcAfterLoop) {
                runCatching { BgController.stopNow(appCtx) }
            }
            throw ce
        } catch (t: Throwable) {
            return ChatUiMessage(
                role = ChatRole.ASSISTANT,
                text = "**Agent Error:** ${t.message ?: t.javaClass.simpleName}\n\n" +
                    (if (settings.providerId == "local_builtin") "Tip: Check the model and CPU settings in **Models**. Built-in offline inference does not use an API key."
                    else "Tip: Check the provider and model settings in the **AI** tab."),
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
