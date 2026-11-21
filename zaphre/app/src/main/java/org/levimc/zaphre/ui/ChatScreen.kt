package org.levimc.zaphre.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.levimc.zaphre.ai.TextAttachment
import org.levimc.zaphre.ai.ChatRole
import org.levimc.zaphre.ai.ChatUiMessage
import org.levimc.zaphre.ai.ToolStepRecord
import org.levimc.zaphre.mc.BgRunnerSnapshot
import org.levimc.zaphre.ui.theme.ZaphAccent
import org.levimc.zaphre.ui.theme.ZaphAmber
import org.levimc.zaphre.ui.theme.ZaphBg
import org.levimc.zaphre.ui.theme.ZaphBorder
import org.levimc.zaphre.ui.theme.ZaphCodeBg
import org.levimc.zaphre.ui.theme.ZaphGreen
import org.levimc.zaphre.ui.theme.ZaphPrimary
import org.levimc.zaphre.ui.theme.ZaphRed
import org.levimc.zaphre.ui.theme.ZaphSubtext
import org.levimc.zaphre.ui.theme.ZaphSurface
import org.levimc.zaphre.ui.theme.ZaphSurfaceHigh
import org.levimc.zaphre.ui.theme.ZaphText

private data class StarterPrompt(val title: String, val subtitle: String, val prompt: String)

private val STARTER_PROMPTS = listOf(
    StarterPrompt(
        "Index New MC Build",
        "Dump RTTI classes, dynsym & strings once in BG",
        "Check my MC target & Zaphkiel status, then run a first-time RTTI + dynsym dump in background, stop MC when done, and summarize total classes/symbols."
    ),
    StarterPrompt(
        "Find Function & Signature",
        "Search class/string -> pseudo-C -> UNIQUE IDA sig",
        "Find LiquidBlock in libminecraftpe.so, inspect its tick / water delay logic with Zaphkiel, generate a verified UNIQUE cross-version signature, and stop BG MC."
    ),
    StarterPrompt(
        "Dump VTable + C++ Hook",
        "Resolve RTTI vtable slots & generate Levi mod hook",
        "Search RTTI for Player and Actor, dump key vtable slots and hierarchy using Zaphkiel, and write a starter C++ hook into generated_mods/."
    ),
    StarterPrompt(
        "Offline Static ELF Recon",
        "Zero-launch ELF64 header, DT_NEEDED & RTTI check",
        "Run offline elf_static_scan info and RTTI check on my selected libminecraftpe.so and tell me its segments, DT_NEEDED libs, and build ID."
    )
)

@Composable
fun ChatScreen(
    vm: MainViewModel,
    onJumpToTab: (MainTab) -> Unit,
    onImportZaphkiel: () -> Unit
) {
    val sessions by vm.sessions.collectAsState()
    val currentSession by vm.currentSession.collectAsState()
    val agentBusy by vm.agentBusy.collectAsState()
    val keySwitchRequest by vm.keySwitchRequest.collectAsState()
    val liveDraft by vm.liveDraft.collectAsState()
    val bgSnapshot by vm.bgSnapshot.collectAsState()
    val aiSettings by vm.aiSettings.collectAsState()
    val selectedTarget by vm.selectedTarget.collectAsState()
    val mods by vm.installedMods.collectAsState()

    keySwitchRequest?.let { keyNumber ->
        AlertDialog(
            onDismissRequest = { vm.decideKeySwitch(false) },
            title = { Text("Switch API key?") },
            text = { Text("The provider returned a rate-limit response. Try key $keyNumber for the same provider and model?") },
            confirmButton = { TextButton(onClick = { vm.decideKeySwitch(true) }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { vm.decideKeySwitch(false) }) { Text("Don't switch") } }
        )
    }

    val hasZaphkiel = mods.any { it.isZaphkiel }
    val localLoaded by org.levimc.zaphre.ai.LocalLlama.loaded.collectAsState()
    val hasAiReady = if (aiSettings.providerId == "local_builtin") localLoaded != null else aiSettings.preset.isLocal || aiSettings.apiKey.isNotBlank()

    var inputText by rememberSaveable { mutableStateOf("") }
    var attachment by remember { mutableStateOf<TextAttachment.Item?>(null) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val attachmentScope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachmentScope.launch {
            try { attachment = withContext(Dispatchers.IO) { TextAttachment.read(context, uri) }; attachmentError = null }
            catch (e: Exception) { attachmentError = e.message ?: "Could not read file" }
        }
    }
    LaunchedEffect(currentSession.id) { attachment = null; attachmentError = null }
    var exportSnapshot by remember { mutableStateOf<org.levimc.zaphre.ai.ChatSession?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val snapshot = exportSnapshot
        if (uri != null && snapshot != null) vm.exportChat(uri, snapshot)
        exportSnapshot = null
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val totalCount = currentSession.messages.size + (if (liveDraft != null) 1 else 0)
    LaunchedEffect(totalCount, liveDraft?.toolSteps?.size, liveDraft?.text?.length) {
        if (totalCount > 0) {
            runCatching { listState.animateScrollToItem(totalCount - 1) }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = ZaphSurface,
                modifier = Modifier.width(290.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .statusBarsPadding()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ZaphRE Chats", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = ZaphText)
                        Surface(
                            onClick = {
                                vm.newChatSession()
                                scope.launch { drawerState.close() }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = ZaphPrimary.copy(alpha = 0.18f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Add, null, tint = ZaphPrimary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("New", color = ZaphPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = ZaphBorder)
                    Spacer(Modifier.height(8.dp))

                    if (sessions.isEmpty()) {
                        Text(
                            "No saved RE sessions yet.",
                            color = ZaphSubtext,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(sessions, key = { it.id }) { s ->
                                val isCur = s.id == currentSession.id
                                Surface(
                                    onClick = {
                                        vm.openSession(s)
                                        scope.launch { drawerState.close() }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isCur) ZaphSurfaceHigh else Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = s.title,
                                            color = ZaphText,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = { vm.deleteSession(s.id) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = ZaphSubtext,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ZaphBg)
                .statusBarsPadding()
        ) {
            // Top ChatGPT-style portrait header
            ChatTopBar(
                modelName = aiSettings.model,
                bgSnapshot = bgSnapshot,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onClickModel = { onJumpToTab(MainTab.AI) },
                onClickBgPill = { onJumpToTab(MainTab.BG_RUN) },
                onNewChat = { vm.newChatSession() },
                onExport = { exportSnapshot = currentSession; exportPicker.launch("ZaphRE-chat.txt") },
                canExport = currentSession.messages.isNotEmpty() && !agentBusy
            )
            HorizontalDivider(color = ZaphBorder.copy(alpha = 0.6f))

            // Main chat area
            if (currentSession.messages.isEmpty() && liveDraft == null) {
                EmptyChatWelcome(
                    modifier = Modifier.weight(1f),
                    mcLabel = selectedTarget?.let { "MC ${it.versionName.ifBlank { "SO Ready" }}" },
                    hasZaphkiel = hasZaphkiel,
                    hasAiReady = hasAiReady,
                    aiLabel = "${aiSettings.preset.name.substringBefore(" (")}: ${aiSettings.model}",
                    onOpenBg = { onJumpToTab(MainTab.BG_RUN) },
                    onImportZaphkiel = onImportZaphkiel,
                    onOpenAi = { onJumpToTab(MainTab.AI) },
                    onPickStarter = { prompt -> vm.sendPrompt(prompt) }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(currentSession.messages, key = { it.id }) { msg ->
                        ChatMessageBubble(msg = msg, isLive = false, onSaveFinding = { vm.saveFinding(msg, currentSession) })
                    }
                    liveDraft?.let { draft ->
                        item(key = "live_draft") {
                            ChatMessageBubble(msg = draft, isLive = true, onSaveFinding = {})
                        }
                    }
                }
            }

            // Attachments are deliberately explicit: this path supports UTF-8 text only.
            attachment?.let { item ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Attached: ${item.name}", modifier = Modifier.weight(1f), color = ZaphSubtext, fontSize = 12.sp)
                    TextButton(onClick = { attachment = null }) { Text("Remove") }
                }
            }
            attachmentError?.let { Text(it, color = ZaphRed, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
            ChatComposerBar(
                value = inputText,
                onValueChange = { inputText = it },
                busy = agentBusy,
                onAttach = { filePicker.launch(arrayOf("text/*", "application/json", "application/javascript", "application/octet-stream")) },
                onSend = {
                    val toSend = attachment?.let { TextAttachment.compose(inputText, it) } ?: inputText
                    inputText = ""
                    attachment = null
                    vm.sendPrompt(toSend)
                },
                onStop = { vm.stopAgentLoop() }
            )
        }
    }
}

@Composable
private fun ChatTopBar(
    modelName: String,
    bgSnapshot: BgRunnerSnapshot,
    onOpenDrawer: () -> Unit,
    onClickModel: () -> Unit,
    onClickBgPill: () -> Unit,
    onNewChat: () -> Unit,
    onExport: () -> Unit,
    canExport: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Default.Menu, contentDescription = "Chats", tint = ZaphText)
        }

        IconButton(onClick = onExport, enabled = canExport) {
            Icon(Icons.Default.FileDownload, contentDescription = "Export chat", tint = if (canExport) ZaphText else ZaphSubtext)
        }

        // Active AI model pill
        Surface(
            onClick = onClickModel,
            shape = RoundedCornerShape(18.dp),
            color = ZaphSurfaceHigh,
            border = BorderStroke(1.dp, ZaphBorder),
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = modelName,
                    color = ZaphText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // Live BG MC status chip (tap to inspect BG Run screen anytime!)
        val active = bgSnapshot.status.isActive
        val pillColor = if (active) ZaphGreen.copy(alpha = 0.16f) else ZaphSurface
        val dotColor = if (active) ZaphGreen else ZaphSubtext
        Surface(
            onClick = onClickBgPill,
            shape = RoundedCornerShape(16.dp),
            color = pillColor,
            border = BorderStroke(1.dp, if (active) ZaphGreen.copy(alpha = 0.5f) else ZaphBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (active) "BG ${bgSnapshot.elapsedSecs}s/${bgSnapshot.timeoutSecs}s" else "BG Idle",
                    color = if (active) ZaphGreen else ZaphSubtext,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        IconButton(onClick = onNewChat) {
            Icon(Icons.Default.Add, contentDescription = "New Chat", tint = ZaphText)
        }
    }
}

@Composable
private fun EmptyChatWelcome(
    modifier: Modifier,
    mcLabel: String?,
    hasZaphkiel: Boolean,
    hasAiReady: Boolean,
    aiLabel: String,
    onOpenBg: () -> Unit,
    onImportZaphkiel: () -> Unit,
    onOpenAi: () -> Unit,
    onPickStarter: (String) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "ZaphRE",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = ZaphText
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Ghidra-MCP style Minecraft Bedrock RE agent. Runs MC + Zaphkiel silently in background, inspects outputs via Files & Termux/CMD, and returns verified signatures & hooks.",
                fontSize = 13.sp,
                color = ZaphSubtext,
                lineHeight = 19.sp
            )
        }

        // Quick readiness status chips
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReadinessChip(
                    ok = mcLabel != null,
                    text = mcLabel ?: "Select MC APK/.so",
                    onClick = onOpenBg
                )
                ReadinessChip(
                    ok = hasZaphkiel,
                    text = if (hasZaphkiel) "libZaphkiel.so Ready" else "Import libZaphkiel.so",
                    onClick = onImportZaphkiel
                )
                ReadinessChip(
                    ok = hasAiReady,
                    text = if (hasAiReady) aiLabel else "Set AI Key / Local Model",
                    onClick = onOpenAi
                )
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text("Quick RE Tasks", color = ZaphSubtext, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        items(STARTER_PROMPTS) { item ->
            Surface(
                onClick = { onPickStarter(item.prompt) },
                shape = RoundedCornerShape(14.dp),
                color = ZaphSurface,
                border = BorderStroke(1.dp, ZaphBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(item.title, color = ZaphText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(item.subtitle, color = ZaphSubtext, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ReadinessChip(ok: Boolean, text: String, onClick: () -> Unit) {
    val tint = if (ok) ZaphGreen else ZaphAmber
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = tint.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Spacer(Modifier.width(6.dp))
            Text(text, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ChatMessageBubble(msg: ChatUiMessage, isLive: Boolean, onSaveFinding: () -> Unit) {
    val clipboard = LocalClipboardManager.current

    if (msg.role == ChatRole.USER) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = ZaphPrimary.copy(alpha = 0.22f),
                border = BorderStroke(1.dp, ZaphPrimary.copy(alpha = 0.4f)),
                modifier = Modifier.widthIn(max = 310.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = msg.text,
                        color = ZaphText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
        return
    }

    // Assistant RE turn (tool execution cards + markdown/code response)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ZaphRE", color = ZaphAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (msg.modelTag.isNotBlank()) {
                    Text("  ·  ${msg.modelTag}", color = ZaphSubtext, fontSize = 11.sp)
                }
                if (isLive) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.8.dp,
                        color = ZaphAccent
                    )
                }
            }
        }

        // Collapsible tool call steps (just like Claude Code / Ghidra MCP)
        if (msg.toolSteps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                msg.toolSteps.forEach { step ->
                    ToolStepCard(step)
                }
            }
        }

        // Formatted body with fenced ```code``` blocks and 1-tap copy buttons
        if (msg.text.isNotBlank()) {
            RichMessageContent(msg.text)
        }
        if (!isLive && msg.text.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(msg.text)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy")
                }
                TextButton(onClick = onSaveFinding) { Text("Save finding") }
            }
        }
    }
}

@Composable
private fun ToolStepCard(step: ToolStepRecord) {
    var expanded by remember { mutableStateOf(false) }
    val borderCol = when {
        step.running -> ZaphAccent.copy(alpha = 0.6f)
        step.isError -> ZaphRed.copy(alpha = 0.5f)
        else -> ZaphBorder
    }

    Surface(
        onClick = { if (step.output.isNotBlank()) expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = ZaphSurface,
        border = BorderStroke(1.dp, borderCol),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    step.running -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = ZaphAccent
                    )
                    step.isError -> Icon(
                        Icons.Default.ErrorOutline,
                        null,
                        tint = ZaphRed,
                        modifier = Modifier.size(15.dp)
                    )
                    else -> Icon(
                        Icons.Default.CheckCircle,
                        null,
                        tint = ZaphGreen,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = step.toolName,
                    color = ZaphText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = step.argsSummary,
                    color = ZaphSubtext,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!step.running && step.durationMs > 0) {
                    Text(
                        text = if (step.durationMs >= 1000) "${step.durationMs / 1000}s" else "${step.durationMs}ms",
                        color = ZaphSubtext,
                        fontSize = 10.sp
                    )
                }
                if (step.output.isNotBlank()) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = ZaphSubtext,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (expanded && step.output.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ZaphCodeBg)
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            text = step.output.take(4000),
                            color = ZaphSubtext,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RichMessageContent(text: String) {
    val clipboard = LocalClipboardManager.current
    val parts = remember(text) { splitFencedCodeBlocks(text) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((isCode, lang, body) in parts) {
            if (!isCode) {
                if (body.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = body.trim(),
                            color = ZaphText,
                            fontSize = 14.sp,
                            lineHeight = 21.sp
                        )
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ZaphCodeBg,
                    border = BorderStroke(1.dp, ZaphBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ZaphSurfaceHigh)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.ifBlank { "code" },
                                color = ZaphSubtext,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Row(
                                modifier = Modifier.clickable { clipboard.setText(AnnotatedString(body)) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ContentCopy, null, tint = ZaphAccent, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy", color = ZaphAccent, fontSize = 11.sp)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = body,
                                    color = ZaphText,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun splitFencedCodeBlocks(raw: String): List<Triple<Boolean, String, String>> {
    val result = mutableListOf<Triple<Boolean, String, String>>()
    val regex = Regex("```([A-Za-z0-9_+.-]*)\\n?([\\s\\S]*?)```")
    var last = 0
    for (m in regex.findAll(raw)) {
        if (m.range.first > last) {
            result.add(Triple(false, "", raw.substring(last, m.range.first)))
        }
        result.add(Triple(true, m.groupValues[1], m.groupValues[2].trimEnd()))
        last = m.range.last + 1
    }
    if (last < raw.length) {
        result.add(Triple(false, "", raw.substring(last)))
    }
    return result
}

@Composable
private fun ChatComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = ZaphBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = onAttach, enabled = !busy) {
                Icon(Icons.Default.AttachFile, contentDescription = "Attach UTF-8 text file", tint = ZaphSubtext)
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (busy) "RE Agent running in background..." else "Ask ZaphRE (find fn, vtable, offset, sig)...",
                        color = ZaphSubtext,
                        fontSize = 13.sp
                    )
                },
                maxLines = 5,
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ZaphSurface,
                    unfocusedContainerColor = ZaphSurface,
                    focusedBorderColor = ZaphPrimary,
                    unfocusedBorderColor = ZaphBorder,
                    focusedTextColor = ZaphText,
                    unfocusedTextColor = ZaphText
                )
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                Surface(
                    onClick = onStop,
                    shape = CircleShape,
                    color = ZaphRed,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White)
                    }
                }
            } else {
                val canSend = value.isNotBlank()
                Surface(
                    onClick = { if (canSend) onSend() },
                    shape = CircleShape,
                    color = if (canSend) ZaphPrimary else ZaphSurfaceHigh,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (canSend) Color.White else ZaphSubtext,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
