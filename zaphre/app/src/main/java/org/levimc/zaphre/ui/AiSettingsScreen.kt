package org.levimc.zaphre.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import org.levimc.zaphre.ai.LocalLlama
import org.levimc.zaphre.ai.GitHubIntegration
import org.levimc.zaphre.ai.AiSettingsStore
import org.levimc.zaphre.ai.WebSearch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.levimc.zaphre.ai.BUILTIN_PROVIDERS
import org.levimc.zaphre.ai.ToolCallMode
import org.levimc.zaphre.terminal.ShellExecutor
import org.levimc.zaphre.ui.theme.ZaphAccent
import org.levimc.zaphre.ui.theme.ZaphBg
import org.levimc.zaphre.ui.theme.ZaphBorder
import org.levimc.zaphre.ui.theme.ZaphGreen
import org.levimc.zaphre.ui.theme.ZaphPrimary
import org.levimc.zaphre.ui.theme.ZaphRed
import org.levimc.zaphre.ui.theme.ZaphSubtext
import org.levimc.zaphre.ui.theme.ZaphSurface
import org.levimc.zaphre.ui.theme.ZaphText

@Composable
fun AiSettingsScreen(vm: MainViewModel) {
    val saved by vm.aiSettings.collectAsState()
    val customProfiles by vm.customProfiles.collectAsState()
    var profileName by remember { mutableStateOf("") }
    val fetchedModels by vm.fetchedModels.collectAsState()
    val testStatus by vm.aiTestStatus.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localModels by LocalLlama.models.collectAsState()
    val localStatus by LocalLlama.status.collectAsState()
    var showLegacyImport by remember { mutableStateOf(false) }
    val legacySettingsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLegacyAiSettings(uri)
    }
    if (showLegacyImport) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showLegacyImport = false }, title = { Text("Import v3 settings copy?") },
        text = { Text("Select an accessible copy of v3 zaphre_ai_settings.xml. Existing provider settings are not overwritten. The old app's private data cannot be accessed automatically. This file can contain API keys; keep it private.") },
        confirmButton = { TextButton(onClick = { showLegacyImport = false; legacySettingsPicker.launch(arrayOf("text/xml", "application/xml", "*/*")) }) { Text("Choose file") } },
        dismissButton = { TextButton(onClick = { showLegacyImport = false }) { Text("Cancel") } })
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { runCatching { LocalLlama.importModel(context, uri) }.onFailure { LocalLlama.status.value = "Import failed: ${it.message}" } }
    }
    LaunchedEffect(Unit) { LocalLlama.refresh(context) }

    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var extraKeys by remember { mutableStateOf(saved.additionalKeys) }
    var newKey by remember { mutableStateOf("") }
    var keyRenameIndex by remember { mutableStateOf<Int?>(null) }
    var keyNewName by remember { mutableStateOf("") }
    var keyLabelVersion by remember { mutableIntStateOf(0) }
    var autoSwitchKeys by remember { mutableStateOf(saved.autoSwitchKeys) }
    var askSwitchKeys by remember { mutableStateOf(saved.askSwitchKeys) }
    var model by remember { mutableStateOf(saved.model) }
    var toolMode by remember { mutableStateOf(saved.toolCallMode) }
    var maxSteps by remember { mutableIntStateOf(saved.maxAgentSteps) }
    var temperature by remember { mutableFloatStateOf(saved.temperature) }
    var autoStopLoop by remember { mutableStateOf(saved.autoStopMcAfterLoop) }
    var customPrompt by remember { mutableStateOf(saved.customPrompt) }
    var showKey by remember { mutableStateOf(false) }
    var webSearchEnabled by remember { mutableStateOf(WebSearch.enabled(context)) }
    val initialGitHub = remember { GitHubIntegration.config(context) }
    var ghEnabled by remember { mutableStateOf(initialGitHub.enabled) }
    var ghOwner by remember { mutableStateOf(initialGitHub.owner) }
    var ghRepo by remember { mutableStateOf(initialGitHub.repo) }
    var ghBranch by remember { mutableStateOf(initialGitHub.branch) }
    var ghToken by remember { mutableStateOf("") }
    var ghStatus by remember { mutableStateOf("") }

    LaunchedEffect(saved) {
        baseUrl = saved.baseUrl
        apiKey = saved.apiKey
        extraKeys = saved.additionalKeys
        newKey = ""
        autoSwitchKeys = saved.autoSwitchKeys
        askSwitchKeys = saved.askSwitchKeys
        model = saved.model
        toolMode = saved.toolCallMode
        maxSteps = saved.maxAgentSteps
        temperature = saved.temperature
        autoStopLoop = saved.autoStopMcAfterLoop
        customPrompt = saved.customPrompt
    }

    fun currentDraft() = saved.copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        additionalKeys = extraKeys,
        autoSwitchKeys = autoSwitchKeys,
        askSwitchKeys = askSwitchKeys,
        model = model.trim().ifBlank { saved.preset.defaultModel },
        toolCallMode = toolMode,
        maxAgentSteps = maxSteps,
        temperature = temperature,
        autoStopMcAfterLoop = autoStopLoop,
        customPrompt = customPrompt
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ZaphBg)
            .statusBarsPadding(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("AI Providers (Online + Local)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ZaphText)
            Text(
                "Google Gemini, OpenRouter, OpenAI, Claude, DeepSeek, Groq, Grok, Mistral + Local llama.cpp / Ollama / LM Studio",
                fontSize = 11.sp,
                color = ZaphSubtext
            )
        }

        item {
            Surface(shape = RoundedCornerShape(14.dp), color = ZaphSurface) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Web search (optional)", modifier = Modifier.weight(1f), color = ZaphText)
                        Switch(checked = webSearchEnabled, onCheckedChange = {
                            webSearchEnabled = it
                            WebSearch.setEnabled(context, it)
                        })
                    }
                    Text("Off by default. When enabled, AI search queries are sent to DuckDuckGo over the internet, including when using an offline model. Search results may be inaccurate or unavailable; avoid private data.", color = ZaphSubtext, fontSize = 11.sp)
                }
            }
        }

        item {
            Surface(shape = RoundedCornerShape(14.dp), color = ZaphSurface) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("GitHub integration", color = ZaphText, fontWeight = FontWeight.Bold)
                    Text("Off by default. AI can propose creating or updating text files on the configured branch; every commit needs approval and updates require the current Git SHA.", color = ZaphSubtext, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable GitHub tool", modifier = Modifier.weight(1f), color = ZaphText)
                        Switch(checked = ghEnabled, onCheckedChange = { value ->
                            ghEnabled = value
                            if (!value) runCatching { GitHubIntegration.save(context, false, ghOwner, ghRepo, ghBranch, "") }.onFailure { ghStatus = "Could not disable: ${it.message}" }
                        })
                    }
                    OutlinedTextField(value = ghOwner, onValueChange = { ghOwner = it }, label = { Text("Owner") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = ghRepo, onValueChange = { ghRepo = it }, label = { Text("Repository") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = ghBranch, onValueChange = { ghBranch = it }, label = { Text("Branch") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = ghToken, onValueChange = { ghToken = it }, label = { Text("GitHub credential (leave blank to keep saved)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        runCatching { GitHubIntegration.save(context, ghEnabled, ghOwner, ghRepo, ghBranch, ghToken) }
                            .onSuccess { ghStatus = "GitHub settings saved"; ghToken = "" }
                            .onFailure { ghStatus = "Could not save: ${it.message}" }
                    }) { Text("Save GitHub settings") }
                    if (ghStatus.isNotBlank()) Text(ghStatus, color = ZaphSubtext, fontSize = 11.sp)
                }
            }
        }

        item {
            OutlinedButton(onClick = { showLegacyImport = true }, modifier = Modifier.fillMaxWidth()) { Text("Import v3 AI settings copy") }
        }

        // Online Providers
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ZaphSurface,
                border = BorderStroke(1.dp, ZaphBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Online APIs", color = ZaphText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BUILTIN_PROVIDERS.filter { !it.isLocal }.forEach { p ->
                            FilterChip(
                                selected = saved.providerId == p.id,
                                onClick = { vm.switchAiProvider(p.id) },
                                label = { Text(p.name.substringBefore(" ("), fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ZaphPrimary.copy(alpha = 0.25f),
                                    selectedLabelColor = ZaphPrimary
                                )
                            )
                        }
                    }

                    Text("Named custom OpenAI-compatible profiles", color = ZaphText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        customProfiles.forEach { profile ->
                            FilterChip(selected = saved.providerId == profile.id, onClick = { vm.switchAiProvider(profile.id) }, label = { Text(profile.name) })
                        }
                    }
                    OutlinedTextField(value = profileName, onValueChange = { profileName = it.take(80) }, label = { Text("Profile name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.createAiProfile(profileName); profileName = "" }, enabled = profileName.isNotBlank()) { Text("Add profile") }
                        if (saved.providerId.startsWith("profile_")) {
                            OutlinedButton(onClick = { vm.renameAiProfile(saved.providerId, profileName); profileName = "" }, enabled = profileName.isNotBlank()) { Text("Rename selected") }
                        }
                    }
                    Text("Save each profile's URL, model and keys below. Save changes before switching.", color = ZaphSubtext, fontSize = 11.sp)

                    Text("Local / On-Device Models", color = ZaphGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BUILTIN_PROVIDERS.filter { it.isLocal }.forEach { p ->
                            FilterChip(
                                selected = saved.providerId == p.id,
                                onClick = { vm.switchAiProvider(p.id) },
                                label = { Text(p.name.removePrefix("Local AI — "), fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ZaphGreen.copy(alpha = 0.22f),
                                    selectedLabelColor = ZaphGreen
                                )
                            )
                        }
                    }
                }
            }
        }

        if (saved.providerId == "local_builtin") {
            item {
                OutlinedButton(onClick = { vm.selectTab(MainTab.MODELS) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Models: download, import and load GGUF")
                }
            }
        }
        // Active Provider Credentials & Model Card
        if (saved.providerId != "local_builtin") item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ZaphSurface,
                border = BorderStroke(1.dp, ZaphBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(saved.preset.name, color = ZaphAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(saved.preset.keyHint, fontSize = 12.sp) },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle key"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (saved.additionalKeysUnavailable) Text("Additional keys cannot be decrypted on this device. Existing encrypted data was preserved; settings cannot be saved until resolved.", color = ZaphRed, fontSize = 12.sp)
                    Text("Key sets (same provider and endpoint)", color = ZaphSubtext, fontSize = 12.sp)
                    val allKeys = listOf(apiKey) + extraKeys
                    allKeys.forEachIndexed { index, key ->
                        if (key.isNotBlank()) {
                            val label = remember(saved.providerId, key, keyLabelVersion) { AiSettingsStore.keyLabel(context, saved.providerId, key, "Key ${index + 1}") }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label, modifier = Modifier.weight(1f), color = ZaphSubtext)
                                TextButton(onClick = { keyRenameIndex = index; keyNewName = label }) { Text("Rename") }
                                if (index > 0) TextButton(onClick = { extraKeys = extraKeys.filterIndexed { i, _ -> i != index - 1 }; keyRenameIndex = null }) { Text("Remove") }
                            }
                            if (keyRenameIndex == index) {
                                OutlinedTextField(value = keyNewName, onValueChange = { keyNewName = it.take(80) }, label = { Text("Key name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                TextButton(onClick = {
                                    runCatching { AiSettingsStore.renameKey(context, saved.providerId, key, keyNewName) }
                                        .onSuccess { keyLabelVersion++; keyRenameIndex = null }
                                }, enabled = keyNewName.isNotBlank()) { Text("Save name") }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = newKey, onValueChange = { newKey = it },
                        label = { Text("Add another key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = {
                        val key = newKey.trim()
                        if (key.isNotEmpty() && key != apiKey && key !in extraKeys) extraKeys = extraKeys + key
                        newKey = ""
                    }) { Text("Add key") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto switch on HTTP 429", modifier = Modifier.weight(1f), color = ZaphText)
                        Switch(checked = autoSwitchKeys, onCheckedChange = { autoSwitchKeys = it; if (it) askSwitchKeys = false })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ask before switching keys", modifier = Modifier.weight(1f), color = ZaphText)
                        Switch(checked = askSwitchKeys, onCheckedChange = { askSwitchKeys = it; if (it) autoSwitchKeys = false })
                    }
                    Text("Save settings to apply. Additional keys use device Keystore; the existing primary key uses the legacy settings storage. Shared provider quotas may still block all keys.", color = ZaphSubtext, fontSize = 11.sp)

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL", fontSize = 12.sp) },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ZaphText),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model ID", fontSize = 12.sp) },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = ZaphText),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Suggested + Live-Fetched Model Chips
                    val chips = (fetchedModels + saved.preset.suggestedModels).distinct()
                    if (chips.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            chips.take(40).forEach { m ->
                                FilterChip(
                                    selected = model == m,
                                    onClick = {
                                        model = m
                                        vm.quickPickModel(m)
                                    },
                                    label = { Text(m, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                                )
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.fetchModelsFromApi(currentDraft()) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Fetch Models", fontSize = 12.sp) }

                        OutlinedButton(
                            onClick = { vm.testAiConnection(currentDraft()) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Test API", fontSize = 12.sp) }

                        Button(
                            onClick = { vm.saveAiSettings(currentDraft()) },
                            colors = ButtonDefaults.buttonColors(containerColor = ZaphPrimary),
                            modifier = Modifier.weight(1f)
                        ) { Text("Save", color = Color.White, fontSize = 12.sp) }
                    }

                    testStatus?.let { status ->
                        val col = when {
                            status.startsWith("OK") || status.startsWith("Found") -> ZaphGreen
                            status.startsWith("Fail") || status.startsWith("Fetch failed") -> ZaphRed
                            else -> ZaphAccent
                        }
                        Text(status, color = col, fontSize = 12.sp, lineHeight = 17.sp)
                    }

                    // Helper box when Local llama.cpp is selected
                    if (saved.preset.isLocal) {
                        val llamaCmd = remember { ShellExecutor.generateLocalAiTermuxCommand() }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ZaphBg,
                            border = BorderStroke(1.dp, ZaphBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Run any local .gguf model on phone (Termux):", color = ZaphGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(llamaCmd, color = ZaphSubtext, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(llamaCmd)) }) {
                                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Copy llama-server Cmd", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Autonomous RE Loop Settings
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ZaphSurface,
                border = BorderStroke(1.dp, ZaphBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("RE Agent Loop Behavior", color = ZaphText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                    Text("Max Autonomous Tool Steps per Prompt: $maxSteps", color = ZaphSubtext, fontSize = 12.sp)
                    Slider(
                        value = maxSteps.toFloat(),
                        onValueChange = { maxSteps = it.toInt() },
                        valueRange = 5f..60f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-stop BG MC when AI finishes", color = ZaphText, fontSize = 12.sp)
                            Text("Guarantees zero leftover MC RAM after every chat turn", color = ZaphSubtext, fontSize = 11.sp)
                        }
                        Switch(checked = autoStopLoop, onCheckedChange = { autoStopLoop = it })
                    }

                    Text("Tool Calling Mode", color = ZaphSubtext, fontSize = 12.sp)
                    ToolCallMode.entries.forEach { tm ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = toolMode == tm, onClick = { toolMode = tm })
                            Text(tm.label, color = ZaphText, fontSize = 12.sp)
                        }
                    }

                    OutlinedTextField(
                        value = customPrompt,
                        onValueChange = { customPrompt = it },
                        label = { Text("Extra Instructions (optional, e.g. reply in Hinglish)", fontSize = 12.sp) },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { vm.saveAiSettings(currentDraft()) },
                        colors = ButtonDefaults.buttonColors(containerColor = ZaphPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Agent Preferences", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
