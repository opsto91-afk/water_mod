package org.levimc.zaphre.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.levimc.zaphre.ai.ActiveAiSettings
import org.levimc.zaphre.ai.AiApiClient
import org.levimc.zaphre.ai.AiSettingsStore
import org.levimc.zaphre.ai.AgentTurnItem
import org.levimc.zaphre.ai.ChatHistoryStore
import org.levimc.zaphre.ai.ChatExport
import org.levimc.zaphre.ai.ChatRole
import org.levimc.zaphre.ai.ChatSession
import org.levimc.zaphre.ai.ChatUiMessage
import org.levimc.zaphre.ai.LocalLlama
import org.levimc.zaphre.ai.ReAgentEngine
import org.levimc.zaphre.ai.ToolStepRecord
import org.levimc.zaphre.files.FileSlice
import org.levimc.zaphre.files.WorkspaceEntry
import org.levimc.zaphre.files.WorkspaceFiles
import org.levimc.zaphre.mc.BgController
import org.levimc.zaphre.mc.BgRunnerSnapshot
import org.levimc.zaphre.mc.BgStateStore
import org.levimc.zaphre.mc.McTargetManager
import org.levimc.zaphre.mc.ZaphkielConfig
import org.levimc.zaphre.terminal.ShellExecutor

enum class MainTab(val label: String) {
    CHAT("Chat"),
    BG_RUN("BG Run"),
    FILES("Files"),
    CMD("CMD"),
    MODELS("Models"),
    AI("AI")
}

data class TerminalEntry(
    val command: String,
    val output: String,
    val isError: Boolean
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appCtx = application.applicationContext

    // Navigation & toast banner
    private val _activeTab = MutableStateFlow(MainTab.CHAT)
    val activeTab: StateFlow<MainTab> = _activeTab.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // Chat state
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSession = MutableStateFlow(ChatSession())
    val currentSession: StateFlow<ChatSession> = _currentSession.asStateFlow()

    private val _agentBusy = MutableStateFlow(false)
    val agentBusy: StateFlow<Boolean> = _agentBusy.asStateFlow()

    private val _liveDraft = MutableStateFlow<ChatUiMessage?>(null)
    val liveDraft: StateFlow<ChatUiMessage?> = _liveDraft.asStateFlow()

    private var agentJob: Job? = null

    // BG Runner & Zaphkiel state
    private val _bgSnapshot = MutableStateFlow(BgRunnerSnapshot())
    val bgSnapshot: StateFlow<BgRunnerSnapshot> = _bgSnapshot.asStateFlow()

    private val _bgLog = MutableStateFlow("")
    val bgLog: StateFlow<String> = _bgLog.asStateFlow()

    private val _targets = MutableStateFlow<List<McTargetManager.McTarget>>(emptyList())
    val targets: StateFlow<List<McTargetManager.McTarget>> = _targets.asStateFlow()

    private val _selectedTarget = MutableStateFlow<McTargetManager.McTarget?>(null)
    val selectedTarget: StateFlow<McTargetManager.McTarget?> = _selectedTarget.asStateFlow()

    private val _installedMods = MutableStateFlow<List<McTargetManager.InstalledModInfo>>(emptyList())
    val installedMods: StateFlow<List<McTargetManager.InstalledModInfo>> = _installedMods.asStateFlow()

    private val _launchMode = MutableStateFlow(McTargetManager.LaunchMode.BG_MINIMAL_LINK)
    val launchMode: StateFlow<McTargetManager.LaunchMode> = _launchMode.asStateFlow()

    private val _workspaceMode = MutableStateFlow(ZaphkielConfig.WorkspaceMode.ISOLATED)
    val workspaceMode: StateFlow<ZaphkielConfig.WorkspaceMode> = _workspaceMode.asStateFlow()

    private val _workspacePath = MutableStateFlow("")
    val workspacePath: StateFlow<String> = _workspacePath.asStateFlow()

    private val _timerSecs = MutableStateFlow(30)
    val timerSecs: StateFlow<Int> = _timerSecs.asStateFlow()

    private val _autoStopOnComplete = MutableStateFlow(true)
    val autoStopOnComplete: StateFlow<Boolean> = _autoStopOnComplete.asStateFlow()

    private val _keepAliveHot = MutableStateFlow(false)
    val keepAliveHot: StateFlow<Boolean> = _keepAliveHot.asStateFlow()

    private val _configText = MutableStateFlow("")
    val configText: StateFlow<String> = _configText.asStateFlow()

    private val _zInputText = MutableStateFlow("")
    val zInputText: StateFlow<String> = _zInputText.asStateFlow()

    private val _sigTargetsText = MutableStateFlow("")
    val sigTargetsText: StateFlow<String> = _sigTargetsText.asStateFlow()

    private val _importingBusy = MutableStateFlow(false)
    val importingBusy: StateFlow<Boolean> = _importingBusy.asStateFlow()

    // Files state
    private val _currentFolder = MutableStateFlow("")
    val currentFolder: StateFlow<String> = _currentFolder.asStateFlow()

    private val _fileEntries = MutableStateFlow<List<WorkspaceEntry>>(emptyList())
    val fileEntries: StateFlow<List<WorkspaceEntry>> = _fileEntries.asStateFlow()

    private val _openedFile = MutableStateFlow<FileSlice?>(null)
    val openedFile: StateFlow<FileSlice?> = _openedFile.asStateFlow()

    private val _fileSearchOutput = MutableStateFlow<String?>(null)
    val fileSearchOutput: StateFlow<String?> = _fileSearchOutput.asStateFlow()

    // Terminal state
    private val _termHistory = MutableStateFlow<List<TerminalEntry>>(emptyList())
    val termHistory: StateFlow<List<TerminalEntry>> = _termHistory.asStateFlow()

    private val _termBusy = MutableStateFlow(false)
    val termBusy: StateFlow<Boolean> = _termBusy.asStateFlow()

    private val _termEngine = MutableStateFlow(ShellExecutor.Engine.AUTO)
    val termEngine: StateFlow<ShellExecutor.Engine> = _termEngine.asStateFlow()

    private val _termuxOnline = MutableStateFlow(false)
    val termuxOnline: StateFlow<Boolean> = _termuxOnline.asStateFlow()

    // AI Settings state
    private val _aiSettings = MutableStateFlow(ActiveAiSettings())
    val aiSettings: StateFlow<ActiveAiSettings> = _aiSettings.asStateFlow()

    private val _fetchedModels = MutableStateFlow<List<String>>(emptyList())
    val fetchedModels: StateFlow<List<String>> = _fetchedModels.asStateFlow()

    private val _aiTestStatus = MutableStateFlow<String?>(null)
    val aiTestStatus: StateFlow<String?> = _aiTestStatus.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            ZaphkielConfig.ensureWorkspaceLayout(appCtx)
            refreshAll()
            val saved = ChatHistoryStore.listSessions(appCtx)
            _sessions.value = saved
            if (saved.isNotEmpty()) {
                _currentSession.value = saved.first()
            }
        }
        startBgPoller()
    }

    private fun startBgPoller() {
        viewModelScope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                val snap = BgController.readVerifiedSnapshot(appCtx)
                _bgSnapshot.value = snap
                if (snap.status.isActive || _activeTab.value == MainTab.BG_RUN) {
                    _bgLog.value = BgStateStore.readLogTail(appCtx)
                }
                if (_activeTab.value == MainTab.CMD && tick % 6 == 0) {
                    _termuxOnline.value = ShellExecutor.pingTermuxBridge(appCtx)
                }
                tick++
                delay(if (snap.status.isActive) 550L else 1400L)
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _activeTab.value = tab
        viewModelScope.launch(Dispatchers.IO) {
            when (tab) {
                MainTab.BG_RUN -> {
                    refreshTargetsAndMods()
                    reloadZaphkielEditors()
                    _bgLog.value = BgStateStore.readLogTail(appCtx)
                }
                MainTab.FILES -> refreshFiles()
                MainTab.CMD -> {
                    _termEngine.value = ShellExecutor.getPreferredEngine(appCtx)
                    _termuxOnline.value = ShellExecutor.pingTermuxBridge(appCtx)
                }
                MainTab.MODELS -> { LocalLlama.restoreOptions(appCtx); LocalLlama.refresh(appCtx) }
                MainTab.AI -> _aiSettings.value = AiSettingsStore.load(appCtx)
                MainTab.CHAT -> refreshTargetsAndMods()
            }
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    private fun notify(msg: String) {
        _toastMessage.value = msg
    }

    fun refreshAll() {
        refreshTargetsAndMods()
        reloadZaphkielEditors()
        refreshFiles()
        _aiSettings.value = AiSettingsStore.load(appCtx)
        _termEngine.value = ShellExecutor.getPreferredEngine(appCtx)
        _timerSecs.value = McTargetManager.getDefaultTimerSecs(appCtx)
        _autoStopOnComplete.value = McTargetManager.isAutoStopOnComplete(appCtx)
        _keepAliveHot.value = McTargetManager.isKeepAliveHot(appCtx)
        _launchMode.value = McTargetManager.getLaunchMode(appCtx)
        _bgLog.value = BgStateStore.readLogTail(appCtx)
    }

    fun refreshTargetsAndMods() {
        _workspaceMode.value = ZaphkielConfig.getWorkspaceMode(appCtx)
        _workspacePath.value = ZaphkielConfig.getWorkspacePathSlash(appCtx)
        _targets.value = McTargetManager.listAllTargets(appCtx)
        _selectedTarget.value = McTargetManager.getSelectedTarget(appCtx)
        _installedMods.value = McTargetManager.listInstalledMods(appCtx)
    }

    fun reloadZaphkielEditors() {
        ZaphkielConfig.ensureWorkspaceLayout(appCtx)
        _configText.value = runCatching { ZaphkielConfig.configFile(appCtx).readText() }.getOrDefault("")
        _zInputText.value = runCatching { ZaphkielConfig.inputFile(appCtx).readText() }.getOrDefault("")
        _sigTargetsText.value = runCatching { ZaphkielConfig.sigTargetsFile(appCtx).readText() }.getOrDefault("")
    }

    // ---- Chat actions ----

    fun newChatSession() {
        if (_agentBusy.value) return
        _currentSession.value = ChatSession()
    }

    fun openSession(session: ChatSession) {
        if (_agentBusy.value) return
        _currentSession.value = session
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ChatHistoryStore.deleteSession(appCtx, sessionId)
            val updated = ChatHistoryStore.listSessions(appCtx)
            _sessions.value = updated
            if (_currentSession.value.id == sessionId) {
                _currentSession.value = updated.firstOrNull() ?: ChatSession()
            }
        }
    }

    fun sendPrompt(rawText: String) {
        val prompt = rawText.trim()
        if (prompt.isEmpty() || _agentBusy.value) return

        val cur = _currentSession.value
        val userMsg = ChatUiMessage(role = ChatRole.USER, text = prompt)
        val newTitle = if (cur.messages.isEmpty()) prompt.take(42) else cur.title
        val prior = cur.messages
        val withUser = cur.copy(
            title = newTitle,
            updatedAtMs = System.currentTimeMillis(),
            messages = prior + userMsg
        )
        _currentSession.value = withUser
        _agentBusy.value = true
        _liveDraft.value = ChatUiMessage(role = ChatRole.ASSISTANT, text = "Starting RE loop...")

        agentJob = viewModelScope.launch(Dispatchers.IO) {
            ChatHistoryStore.saveSession(appCtx, withUser)
            _sessions.value = ChatHistoryStore.listSessions(appCtx)
            try {
                val reply = ReAgentEngine.runTurn(
                    context = appCtx,
                    priorMessages = prior,
                    userPrompt = prompt,
                    listener = object : ReAgentEngine.StepListener {
                        override fun onAssistantDraft(text: String, steps: List<ToolStepRecord>, stepIndex: Int) {
                            _liveDraft.value = ChatUiMessage(
                                role = ChatRole.ASSISTANT,
                                text = text,
                                toolSteps = steps,
                                modelTag = "Step $stepIndex"
                            )
                        }
                    }
                )
                val completed = withUser.copy(
                    updatedAtMs = System.currentTimeMillis(),
                    messages = withUser.messages + reply
                )
                _currentSession.value = completed
                ChatHistoryStore.saveSession(appCtx, completed)
                _sessions.value = ChatHistoryStore.listSessions(appCtx)
                reloadZaphkielEditors()
                refreshFiles()
            } catch (_: kotlinx.coroutines.CancellationException) {
                val partial = _liveDraft.value
                val stopMsg = ChatUiMessage(
                    role = ChatRole.ASSISTANT,
                    text = (partial?.text.orEmpty() + "\n\n*(Stopped by user. Background MC stop requested.)*").trim(),
                    toolSteps = partial?.toolSteps.orEmpty().map { it.copy(running = false) }
                )
                val completed = withUser.copy(
                    updatedAtMs = System.currentTimeMillis(),
                    messages = withUser.messages + stopMsg
                )
                _currentSession.value = completed
                ChatHistoryStore.saveSession(appCtx, completed)
            } finally {
                _liveDraft.value = null
                _agentBusy.value = false
            }
        }
    }

    fun stopAgentLoop() {
        agentJob?.cancel()
        LocalLlama.cancelInference()
        viewModelScope.launch(Dispatchers.IO) {
            BgController.stopNow(appCtx)
        }
    }

    // ---- BG Runner & Zaphkiel actions ----

    fun updateTimerSecs(secs: Int) {
        val c = secs.coerceIn(5, 1800)
        _timerSecs.value = c
        McTargetManager.setDefaultTimerSecs(appCtx, c)
    }

    fun setAutoStopOnComplete(enabled: Boolean) {
        _autoStopOnComplete.value = enabled
        McTargetManager.setAutoStopOnComplete(appCtx, enabled)
        if (enabled && _keepAliveHot.value) {
            _keepAliveHot.value = false
            McTargetManager.setKeepAliveHot(appCtx, false)
        }
    }

    fun setKeepAliveHot(enabled: Boolean) {
        _keepAliveHot.value = enabled
        McTargetManager.setKeepAliveHot(appCtx, enabled)
        if (enabled) {
            _autoStopOnComplete.value = false
            McTargetManager.setAutoStopOnComplete(appCtx, false)
        }
    }

    fun selectTarget(id: String) {
        McTargetManager.setSelectedTarget(appCtx, id)
        refreshTargetsAndMods()
    }

    fun deleteTarget(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            McTargetManager.deleteTarget(appCtx, id)
            refreshTargetsAndMods()
            notify("Removed imported MC target")
        }
    }

    fun selectLaunchMode(mode: McTargetManager.LaunchMode) {
        _launchMode.value = mode
        McTargetManager.setLaunchMode(appCtx, mode)
    }

    fun selectWorkspaceMode(mode: ZaphkielConfig.WorkspaceMode) {
        viewModelScope.launch(Dispatchers.IO) {
            ZaphkielConfig.setWorkspaceMode(appCtx, mode)
            _workspaceMode.value = mode
            _workspacePath.value = ZaphkielConfig.getWorkspacePathSlash(appCtx)
            reloadZaphkielEditors()
            refreshFiles()
            notify("Workspace switched to ${mode.label}")
        }
    }

    fun importMcUri(uri: Uri) {
        if (_importingBusy.value) return
        _importingBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val res = McTargetManager.importMcTargetFromUri(appCtx, uri) { line ->
                BgStateStore.appendLog(appCtx, "[Import] $line")
                _bgLog.value = BgStateStore.readLogTail(appCtx)
            }
            _importingBusy.value = false
            refreshTargetsAndMods()
            res.fold(
                onSuccess = { notify("Imported ${it.label}") },
                onFailure = { notify("Import failed: ${it.message}") }
            )
        }
    }

    fun importModUri(uri: Uri, forceZaphkiel: Boolean) {
        if (_importingBusy.value) return
        _importingBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val res = McTargetManager.importModFromUri(appCtx, uri, forceZaphkiel)
            _importingBusy.value = false
            refreshTargetsAndMods()
            res.fold(
                onSuccess = { notify("Loaded ${it.fileName} (${WorkspaceFiles.formatBytes(it.sizeBytes)})") },
                onFailure = { notify("Mod import failed: ${it.message}") }
            )
        }
    }

    fun toggleMod(fileName: String, enabled: Boolean) {
        McTargetManager.setModEnabled(appCtx, fileName, enabled)
        refreshTargetsAndMods()
    }

    fun removeMod(fileName: String) {
        McTargetManager.deleteMod(appCtx, fileName)
        refreshTargetsAndMods()
        notify("Deleted $fileName")
    }

    fun startBgRun(forceReExtract: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            BgController.startRun(
                context = appCtx,
                timeoutSecs = _timerSecs.value,
                autoStopOnComplete = _autoStopOnComplete.value,
                keepAliveHot = _keepAliveHot.value,
                forceReExtract = forceReExtract,
                mode = _launchMode.value
            )
            _bgSnapshot.value = BgStateStore.read(appCtx)
        }
    }

    fun triggerHotRerun() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasSig = _sigTargetsText.value.lineSequence()
                .map { it.trim() }
                .any { it.startsWith("fn<") || it.startsWith("add<") }
            BgController.hotRerun(
                context = appCtx,
                timeoutSecs = _timerSecs.value,
                includeSigGen = hasSig,
                stopAfter = _autoStopOnComplete.value
            )
        }
    }

    fun stopBgRun() {
        viewModelScope.launch(Dispatchers.IO) {
            BgController.stopNow(appCtx)
            _bgSnapshot.value = BgStateStore.read(appCtx)
            _bgLog.value = BgStateStore.readLogTail(appCtx)
        }
    }

    fun clearBgLog() {
        viewModelScope.launch(Dispatchers.IO) {
            BgStateStore.clearLog(appCtx)
            _bgLog.value = ""
        }
    }

    fun applyConfigPreset(preset: ZaphkielConfig.Preset) {
        viewModelScope.launch(Dispatchers.IO) {
            ZaphkielConfig.applyPreset(appCtx, preset)
            updateTimerSecs(preset.recommendedTimerSecs)
            reloadZaphkielEditors()
            notify("Applied preset: ${preset.title} (timer ${preset.recommendedTimerSecs}s)")
        }
    }

    fun onConfigTextChange(v: String) { _configText.value = v }
    fun onZInputTextChange(v: String) { _zInputText.value = v }
    fun onSigTargetsTextChange(v: String) { _sigTargetsText.value = v }

    fun saveConfigText() {
        viewModelScope.launch(Dispatchers.IO) {
            ZaphkielConfig.configFile(appCtx).writeText(_configText.value)
            notify("Saved zaphkiel_config.txt")
        }
    }

    fun saveTargets(andRunNow: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            ZaphkielConfig.inputFile(appCtx).writeText(_zInputText.value)
            ZaphkielConfig.sigTargetsFile(appCtx).writeText(_sigTargetsText.value)
            if (andRunNow) {
                startBgRun(forceReExtract = false)
                notify("Saved targets & launched BG session!")
            } else {
                notify("Saved zInput.txt & sig_targets.txt")
            }
        }
    }

    // ---- File Manager actions ----

    fun refreshFiles(subFolder: String = _currentFolder.value) {
        viewModelScope.launch(Dispatchers.IO) {
            _currentFolder.value = subFolder
            _fileEntries.value = WorkspaceFiles.list(appCtx, subFolder)
        }
    }

    fun navigateUpFolder() {
        val cur = _currentFolder.value
        if (cur.isEmpty()) return
        val parent = cur.substringBeforeLast('/', missingDelimiterValue = "")
        refreshFiles(parent)
    }

    fun openFile(relativePath: String, startLine: Int = 1, rawForEdit: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            _fileSearchOutput.value = null
            WorkspaceFiles.readSlice(
                context = appCtx,
                path = relativePath,
                startLine = startLine,
                maxLines = if (rawForEdit) 1200 else 300,
                maxChars = if (rawForEdit) 120_000 else 45_000,
                withLineNumbers = !rawForEdit
            ).fold(
                onSuccess = { _openedFile.value = it },
                onFailure = { notify("Cannot open: ${it.message}") }
            )
        }
    }

    fun closeOpenedFile() {
        _openedFile.value = null
        _fileSearchOutput.value = null
    }

    fun searchOpenedFile(query: String, isRegex: Boolean) {
        val slice = _openedFile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            WorkspaceFiles.searchInFile(
                context = appCtx,
                path = slice.relativePath,
                pattern = query,
                isRegex = isRegex,
                maxMatches = 120,
                contextLines = 1
            ).fold(
                onSuccess = { _fileSearchOutput.value = it },
                onFailure = { notify("Search error: ${it.message}") }
            )
        }
    }

    fun saveFileContent(relativePath: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            WorkspaceFiles.writeText(appCtx, relativePath, content).fold(
                onSuccess = {
                    notify("Saved $relativePath")
                    refreshFiles()
                    reloadZaphkielEditors()
                    openFile(relativePath, 1)
                },
                onFailure = { notify("Save failed: ${it.message}") }
            )
        }
    }

    fun createNewEntry(name: String, isFolder: Boolean) {
        val clean = name.trim().replace("/", "_")
        if (clean.isEmpty()) return
        val rel = if (_currentFolder.value.isEmpty()) clean else "${_currentFolder.value}/$clean"
        viewModelScope.launch(Dispatchers.IO) {
            val res = if (isFolder) WorkspaceFiles.createFolder(appCtx, rel) else WorkspaceFiles.writeText(appCtx, rel, "")
            res.fold(
                onSuccess = {
                    refreshFiles()
                    notify("Created $rel")
                },
                onFailure = { notify("Failed: ${it.message}") }
            )
        }
    }

    fun deleteFileEntry(relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            WorkspaceFiles.delete(appCtx, relativePath)
            if (_openedFile.value?.relativePath == relativePath) closeOpenedFile()
            refreshFiles()
            notify("Deleted $relativePath")
        }
    }

    fun importWorkspaceFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            WorkspaceFiles.importFileIntoDir(appCtx, uri, _currentFolder.value).fold(
                onSuccess = {
                    refreshFiles()
                    notify("Imported ${it.name}")
                },
                onFailure = { notify("Import failed: ${it.message}") }
            )
        }
    }

    fun shareFile(relativePath: String) {
        runCatching {
            val f = WorkspaceFiles.resolveSafe(appCtx, relativePath)
            val uri = FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", f)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appCtx.startActivity(Intent.createChooser(share, "Share ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            notify("Share failed: ${it.message}")
        }
    }

    fun exportChat(uri: Uri, session: ChatSession) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stream = appCtx.contentResolver.openOutputStream(uri, "wt")
                    ?: throw java.io.IOException("Cannot open selected destination")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(ChatExport.format(session)) }
                notify("Chat exported")
            } catch (e: Exception) { notify("Chat export failed: ${e.message}") }
        }
    }

    fun saveFinding(message: ChatUiMessage, session: ChatSession) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = "notes/finding-${java.util.UUID.randomUUID()}.txt"
            val content = ChatExport.format(session.copy(messages = listOf(message)))
            WorkspaceFiles.writeText(appCtx, path, content).fold(
                onSuccess = { notify("Saved to Files / $path") },
                onFailure = { notify("Could not save finding: ${it.message}") }
            )
        }
    }

    // ---- Terminal actions ----

    fun setTerminalEngine(engine: ShellExecutor.Engine) {
        _termEngine.value = engine
        ShellExecutor.setPreferredEngine(appCtx, engine)
    }

    fun runUserCommand(cmd: String) {
        val clean = cmd.trim()
        if (clean.isEmpty() || _termBusy.value) return
        if (clean == "clear") {
            _termHistory.value = emptyList()
            return
        }
        _termBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val res = ShellExecutor.execute(appCtx, clean, _termEngine.value, timeoutSecs = 45)
            _termHistory.value = _termHistory.value + TerminalEntry(
                command = clean,
                output = res.formattedOutput(16_000),
                isError = res.exitCode != 0
            )
            _termBusy.value = false
            _termuxOnline.value = ShellExecutor.pingTermuxBridge(appCtx)
        }
    }

    fun autoStartTermuxBridge() {
        viewModelScope.launch(Dispatchers.IO) {
            val sent = ShellExecutor.launchBridgeViaTermuxIntent(appCtx)
            delay(1200)
            val ok = ShellExecutor.pingTermuxBridge(appCtx)
            _termuxOnline.value = ok
            notify(
                when {
                    ok -> "Termux Bridge connected!"
                    sent -> "Sent start command to Termux — if offline, paste setup command once in Termux."
                    else -> "Copy & run the Termux setup command inside Termux."
                }
            )
        }
    }

    fun getTermuxSetupScript(): String = ShellExecutor.generateTermuxBridgeCommand(appCtx)

    // ---- AI Settings actions ----

    fun loadLocalModel(name: String) {
        if (_agentBusy.value) { notify("Stop the current chat before switching models"); return }
        viewModelScope.launch {
            try {
                LocalLlama.select(appCtx, name)
                switchAiProvider("local_builtin")
                quickPickModel(name)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: LinkageError) { LocalLlama.status.value = "Local runtime unavailable on this device: ${e.message}" }
            catch (e: Exception) { LocalLlama.status.value = "Load failed: ${e.message}" }
        }
    }

    fun switchAiProvider(providerId: String) {
        _fetchedModels.value = emptyList()
        _aiTestStatus.value = null
        _aiSettings.value = AiSettingsStore.selectProvider(appCtx, providerId)
    }

    fun saveAiSettings(updated: ActiveAiSettings) {
        AiSettingsStore.save(appCtx, updated)
        _aiSettings.value = AiSettingsStore.load(appCtx)
        notify("Saved ${updated.preset.name} (${updated.model})")
    }

    fun quickPickModel(modelName: String) {
        val updated = _aiSettings.value.copy(model = modelName.trim())
        AiSettingsStore.save(appCtx, updated)
        _aiSettings.value = updated
    }

    fun fetchModelsFromApi(current: ActiveAiSettings) {
        AiSettingsStore.save(appCtx, current)
        _aiSettings.value = current
        _aiTestStatus.value = "Fetching models from ${current.preset.name}..."
        viewModelScope.launch {
            val res = AiApiClient.fetchAvailableModels(current)
            res.fold(
                onSuccess = { list ->
                    _fetchedModels.value = list
                    _aiTestStatus.value = "Found ${list.size} models — tap any model to select."
                },
                onFailure = { e ->
                    _aiTestStatus.value = "Fetch failed: ${e.message}"
                }
            )
        }
    }

    fun testAiConnection(current: ActiveAiSettings) {
        AiSettingsStore.save(appCtx, current)
        _aiSettings.value = current
        _aiTestStatus.value = "Testing ${current.model}..."
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    AiApiClient.chat(
                        settings = current,
                        systemPrompt = "Reply with 'ZaphRE connected OK' in one short sentence.",
                        history = listOf(AgentTurnItem.User("Ping")),
                        tools = emptyList()
                    )
                }
            }
            val dt = System.currentTimeMillis() - t0
            res.fold(
                onSuccess = { r -> _aiTestStatus.value = "OK (${dt}ms): ${r.text.take(140)}" },
                onFailure = { e -> _aiTestStatus.value = "Failed: ${e.message}" }
            )
        }
    }
}
