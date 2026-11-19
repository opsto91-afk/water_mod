package org.levimc.zaphre.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.levimc.zaphre.files.WorkspaceFiles
import org.levimc.zaphre.mc.BgRunnerSnapshot
import org.levimc.zaphre.mc.McTargetManager
import org.levimc.zaphre.mc.RunnerStatus
import org.levimc.zaphre.mc.ZaphkielConfig
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

private val TIMER_PRESETS = listOf(
    10 to "10s",
    15 to "15s",
    30 to "30s",
    60 to "1m",
    300 to "5m"
)

@Composable
fun BgMonitorScreen(
    vm: MainViewModel,
    onPickMc: () -> Unit,
    onPickZaphkiel: () -> Unit,
    onPickExtraMod: () -> Unit,
    onRequestAllFilesAccess: () -> Unit
) {
    val snap by vm.bgSnapshot.collectAsState()
    val bgLog by vm.bgLog.collectAsState()
    val timerSecs by vm.timerSecs.collectAsState()
    val autoStop by vm.autoStopOnComplete.collectAsState()
    val keepAlive by vm.keepAliveHot.collectAsState()
    val targets by vm.targets.collectAsState()
    val selectedTarget by vm.selectedTarget.collectAsState()
    val mods by vm.installedMods.collectAsState()
    val launchMode by vm.launchMode.collectAsState()
    val workspaceMode by vm.workspaceMode.collectAsState()
    val workspacePath by vm.workspacePath.collectAsState()
    val configText by vm.configText.collectAsState()
    val zInputText by vm.zInputText.collectAsState()
    val sigText by vm.sigTargetsText.collectAsState()
    val importing by vm.importingBusy.collectAsState()

    var subTab by rememberSaveable { mutableIntStateOf(0) }
    var showCustomTimer by remember { mutableStateOf(false) }

    if (showCustomTimer) {
        CustomTimerDialog(
            initialSecs = timerSecs,
            onDismiss = { showCustomTimer = false },
            onConfirm = {
                vm.updateTimerSecs(it)
                showCustomTimer = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZaphBg)
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Background MC Runner", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ZaphText)
                Text("Levi v1.7.1 Runtime · Zaphkiel Live Controller", fontSize = 11.sp, color = ZaphSubtext)
            }
            if (importing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ZaphPrimary)
            } else {
                IconButton(onClick = { vm.refreshAll() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = ZaphSubtext)
                }
            }
        }

        // Sub-tabs: Monitor | Setup | Config | Targets
        val tabs = listOf("Monitor", "Setup", "Config", "Targets")
        TabRow(
            selectedTabIndex = subTab,
            containerColor = ZaphSurface,
            contentColor = ZaphPrimary
        ) {
            tabs.forEachIndexed { idx, label ->
                Tab(
                    selected = subTab == idx,
                    onClick = { subTab = idx },
                    text = {
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (subTab == idx) ZaphPrimary else ZaphSubtext
                        )
                    }
                )
            }
        }

        when (subTab) {
            0 -> LiveMonitorPane(
                snap = snap,
                logText = bgLog,
                timerSecs = timerSecs,
                autoStop = autoStop,
                keepAlive = keepAlive,
                onSelectTimer = { vm.updateTimerSecs(it) },
                onOpenCustomTimer = { showCustomTimer = true },
                onToggleAutoStop = { vm.setAutoStopOnComplete(it) },
                onToggleKeepAlive = { vm.setKeepAliveHot(it) },
                onStart = { vm.startBgRun(false) },
                onHotRun = { vm.triggerHotRerun() },
                onStop = { vm.stopBgRun() },
                onClearLog = { vm.clearBgLog() }
            )

            1 -> SetupPane(
                targets = targets,
                selected = selectedTarget,
                mods = mods,
                launchMode = launchMode,
                workspaceMode = workspaceMode,
                workspacePath = workspacePath,
                onSelectTarget = { vm.selectTarget(it) },
                onDeleteTarget = { vm.deleteTarget(it) },
                onPickMc = onPickMc,
                onReExtract = { vm.startBgRun(forceReExtract = true) },
                onPickZaphkiel = onPickZaphkiel,
                onPickExtraMod = onPickExtraMod,
                onToggleMod = { f, en -> vm.toggleMod(f, en) },
                onRemoveMod = { vm.removeMod(it) },
                onSelectLaunchMode = { vm.selectLaunchMode(it) },
                onSelectWorkspaceMode = { vm.selectWorkspaceMode(it) },
                onRequestAllFilesAccess = onRequestAllFilesAccess
            )

            2 -> ConfigEditorPane(
                configText = configText,
                onConfigChange = { vm.onConfigTextChange(it) },
                onApplyPreset = { vm.applyConfigPreset(it) },
                onSave = { vm.saveConfigText() }
            )

            3 -> TargetsEditorPane(
                zInputText = zInputText,
                sigText = sigText,
                onZInputChange = { vm.onZInputTextChange(it) },
                onSigChange = { vm.onSigTargetsTextChange(it) },
                onSave = { vm.saveTargets(andRunNow = false) },
                onSaveAndRun = {
                    vm.saveTargets(andRunNow = true)
                    subTab = 0
                }
            )
        }
    }
}

@Composable
private fun LiveMonitorPane(
    snap: BgRunnerSnapshot,
    logText: String,
    timerSecs: Int,
    autoStop: Boolean,
    keepAlive: Boolean,
    onSelectTimer: (Int) -> Unit,
    onOpenCustomTimer: () -> Unit,
    onToggleAutoStop: (Boolean) -> Unit,
    onToggleKeepAlive: (Boolean) -> Unit,
    onStart: () -> Unit,
    onHotRun: () -> Unit,
    onStop: () -> Unit,
    onClearLog: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val statusColor = when (snap.status) {
        RunnerStatus.RUNNING_ZAPHKIEL, RunnerStatus.LOADING_MC, RunnerStatus.PREPARING -> ZaphAccent
        RunnerStatus.HOT_IDLE, RunnerStatus.COMPLETED -> ZaphGreen
        RunnerStatus.ERROR, RunnerStatus.CRASHED -> ZaphRed
        RunnerStatus.STOPPED -> ZaphAmber
        RunnerStatus.IDLE -> ZaphSubtext
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Live Process Telemetry Card
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ZaphSurface,
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = snap.status.name,
                            color = statusColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (snap.pid > 0) "PID ${snap.pid} · ${snap.rssMb} MB" else "RAM Free",
                            color = ZaphSubtext,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(snap.stage.ifBlank { "Ready" }, color = ZaphText, fontSize = 13.sp)

                    if (snap.status.isActive && snap.timeoutSecs > 0) {
                        val progress = (snap.elapsedSecs.toFloat() / snap.timeoutSecs.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = statusColor,
                            trackColor = ZaphSurfaceHigh
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Elapsed: ${snap.elapsedSecs}s", color = ZaphSubtext, fontSize = 11.sp)
                            Text("Auto-stop in: ${snap.remainingSecs}s", color = ZaphSubtext, fontSize = 11.sp)
                        }
                    }

                    if (snap.mcBaseHex.isNotBlank() || snap.zaphkielBaseHex.isNotBlank()) {
                        Text(
                            text = "libminecraftpe.so @ ${snap.mcBaseHex.ifBlank { "-" }}  |  Zaphkiel @ ${snap.zaphkielBaseHex.ifBlank { "-" }}",
                            color = ZaphAccent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!snap.status.isActive) {
                            Button(
                                onClick = onStart,
                                colors = ButtonDefaults.buttonColors(containerColor = ZaphPrimary),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Start BG (${formatDuration(timerSecs)})", color = Color.White, fontSize = 13.sp)
                            }
                        } else {
                            OutlinedButton(
                                onClick = onHotRun,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Bolt, null, tint = ZaphAccent, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Hot Re-Run", color = ZaphAccent, fontSize = 12.sp)
                            }
                            Button(
                                onClick = onStop,
                                colors = ButtonDefaults.buttonColors(containerColor = ZaphRed),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Stop, null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Stop MC", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Auto-Stop Timer Card (10s / 15s / 30s / 1m / 5m / Custom)
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ZaphSurface,
                border = BorderStroke(1.dp, ZaphBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("BG Run Timer: ${formatDuration(timerSecs)}", color = ZaphText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TIMER_PRESETS.forEach { (secs, label) ->
                            FilterChip(
                                selected = timerSecs == secs,
                                onClick = { onSelectTimer(secs) },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ZaphPrimary.copy(alpha = 0.25f),
                                    selectedLabelColor = ZaphPrimary
                                )
                            )
                        }
                        val isCustom = TIMER_PRESETS.none { it.first == timerSecs }
                        FilterChip(
                            selected = isCustom,
                            onClick = onOpenCustomTimer,
                            label = { Text(if (isCustom) "Custom (${timerSecs}s)" else "Custom", fontSize = 12.sp) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-stop on 'run() complete'", color = ZaphText, fontSize = 12.sp)
                            Text("Kills BG MC & frees RAM as soon as Zaphkiel finishes", color = ZaphSubtext, fontSize = 11.sp)
                        }
                        Switch(checked = autoStop, onCheckedChange = onToggleAutoStop)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keep MC Hot in RAM until timer", color = ZaphText, fontSize = 12.sp)
                            Text("Allows 1-second hot re-runs without reloading MC", color = ZaphSubtext, fontSize = 11.sp)
                        }
                        Switch(checked = keepAlive, onCheckedChange = onToggleKeepAlive)
                    }
                }
            }
        }

        // Live Console Card (:mcre + zaphkiel_log.txt + native crash trace)
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ZaphCodeBg,
                border = BorderStroke(1.dp, ZaphBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ZaphSurfaceHigh)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Live BG + Zaphkiel Console", color = ZaphText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row {
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(logText)) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, "Copy", tint = ZaphSubtext, modifier = Modifier.size(15.dp))
                            }
                            IconButton(onClick = onClearLog, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, "Clear", tint = ZaphSubtext, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        SelectionContainer {
                            Text(
                                text = logText.ifBlank { "(No background logs yet — tap Start BG or ask ZaphRE in Chat.)" },
                                color = ZaphSubtext,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupPane(
    targets: List<McTargetManager.McTarget>,
    selected: McTargetManager.McTarget?,
    mods: List<McTargetManager.InstalledModInfo>,
    launchMode: McTargetManager.LaunchMode,
    workspaceMode: ZaphkielConfig.WorkspaceMode,
    workspacePath: String,
    onSelectTarget: (String) -> Unit,
    onDeleteTarget: (String) -> Unit,
    onPickMc: () -> Unit,
    onReExtract: () -> Unit,
    onPickZaphkiel: () -> Unit,
    onPickExtraMod: () -> Unit,
    onToggleMod: (String, Boolean) -> Unit,
    onRemoveMod: (String) -> Unit,
    onSelectLaunchMode: (McTargetManager.LaunchMode) -> Unit,
    onSelectWorkspaceMode: (ZaphkielConfig.WorkspaceMode) -> Unit,
    onRequestAllFilesAccess: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Minecraft Target Card
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ZaphSurface,
                border = BorderStroke(1.dp, ZaphBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Minecraft Target (1.21.80+ / 26.x / Beta)", color = ZaphText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (targets.isEmpty()) {
                        Text(
                            "No installed Minecraft detected. Install official Minecraft Bedrock or import an APK / APKS / XAPK / libminecraftpe.so below.",
                            color = ZaphAmber,
                            fontSize = 12.sp
                        )
                    } else {
                        targets.forEach { t ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected?.id == t.id,
                                    onClick = { onSelectTarget(t.id) }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(t.label, color = ZaphText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(t.flags.joinToString(" · "), color = ZaphSubtext, fontSize = 11.sp)
                                }
                                if (t.kind != McTargetManager.TargetKind.INSTALLED_MC) {
                                    IconButton(onClick = { onDeleteTarget(t.id) }) {
                                        Icon(Icons.Default.Delete, null, tint = ZaphSubtext, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPickMc, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Import APK / .SO", fontSize = 12.sp)
                        }
                        if (selected != null) {
                            OutlinedButton(onClick = onReExtract) {
                                Text("Refresh Cache", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // 2. Zaphkiel & Native RE Mods Card
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ZaphSurface,
                border = BorderStroke(1.dp, ZaphBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. Zaphkiel & RE Native Mods", color = ZaphText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "Accepts libZaphkiel.so, libZaphkiel.so.txt (auto-strips .txt), or .levipack archives.",
                        color = ZaphSubtext,
                        fontSize = 11.sp
                    )
                    if (mods.isEmpty()) {
                        Text("libZaphkiel.so not imported yet.", color = ZaphAmber, fontSize = 12.sp)
                    } else {
                        mods.forEach { m ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        m.name,
                                        color = if (m.isZaphkiel) ZaphGreen else ZaphText,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "${m.fileName} · ${WorkspaceFiles.formatBytes(m.sizeBytes)}",
                                        color = ZaphSubtext,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Switch(
                                    checked = m.enabled,
                                    onCheckedChange = { onToggleMod(m.fileName, it) }
                                )
                                IconButton(onClick = { onRemoveMod(m.fileName) }) {
                                    Icon(Icons.Default.Delete, null, tint = ZaphSubtext, modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onPickZaphkiel,
                            colors = ButtonDefaults.buttonColors(containerColor = ZaphPrimary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Import libZaphkiel.so", color = Color.White, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onPickExtraMod) {
                            Text("+ Mod", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 3. Launch Mode Card
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ZaphSurface,
                border = BorderStroke(1.dp, ZaphBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("3. Background Execution Mode", color = ZaphText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    McTargetManager.LaunchMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            RadioButton(
                                selected = launchMode == mode,
                                onClick = { onSelectLaunchMode(mode) }
                            )
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                Text(mode.title, color = ZaphText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(mode.desc, color = ZaphSubtext, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }
                    }
                }
            }
        }

        // 4. Workspace Mode Card
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ZaphSurface,
                border = BorderStroke(1.dp, ZaphBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("4. Zaphkiel Workspace Folder", color = ZaphText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(workspacePath, color = ZaphAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    ZaphkielConfig.WorkspaceMode.entries.forEach { wm ->
                        Row(verticalAlignment = Alignment.Top) {
                            RadioButton(
                                selected = workspaceMode == wm,
                                onClick = { onSelectWorkspaceMode(wm) }
                            )
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                Text(wm.label, color = ZaphText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(wm.subtitle, color = ZaphSubtext, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }
                    }
                    if (workspaceMode == ZaphkielConfig.WorkspaceMode.SHARED_LEVI && !ZaphkielConfig.hasAllFilesAccess()) {
                        HorizontalDivider(color = ZaphBorder)
                        Button(
                            onClick = onRequestAllFilesAccess,
                            colors = ButtonDefaults.buttonColors(containerColor = ZaphAmber),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant All Files Access (for shared kurumi/)", color = Color.Black, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigEditorPane(
    configText: String,
    onConfigChange: (String) -> Unit,
    onApplyPreset: (ZaphkielConfig.Preset) -> Unit,
    onSave: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("One-Tap Zaphkiel Presets", color = ZaphText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ZaphkielConfig.Preset.entries.forEach { preset ->
                    OutlinedButton(onClick = { onApplyPreset(preset) }) {
                        Text(preset.title, fontSize = 11.sp)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("zaphkiel_config.txt", color = ZaphAccent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = ZaphPrimary)) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save Config", color = Color.White, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = configText,
                onValueChange = onConfigChange,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ZaphText),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            )
        }
    }
}

@Composable
private fun TargetsEditorPane(
    zInputText: String,
    sigText: String,
    onZInputChange: (String) -> Unit,
    onSigChange: (String) -> Unit,
    onSave: () -> Unit,
    onSaveAndRun: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Insert Zaphkiel Target Syntax", color = ZaphSubtext, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ZaphkielConfig.COMMAND_TEMPLATES.forEach { tpl ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            val sep = if (zInputText.endsWith("\n") || zInputText.isEmpty()) "" else "\n"
                            onZInputChange(zInputText + sep + tpl.snippet + "\n")
                        },
                        label = { Text(tpl.label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    )
                }
            }
        }

        item {
            Text("zInput.txt (Analysis Targets)", color = ZaphAccent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = zInputText,
                onValueChange = onZInputChange,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ZaphText),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
            )
        }

        item {
            Text("sig_targets.txt (fn<0xADDR> / add<0xADDR>)", color = ZaphAccent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = sigText,
                onValueChange = onSigChange,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ZaphText),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            )
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text("Save Files", fontSize = 12.sp)
                }
                Button(
                    onClick = onSaveAndRun,
                    colors = ButtonDefaults.buttonColors(containerColor = ZaphGreen),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save & Run BG", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CustomTimerDialog(
    initialSecs: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initialSecs.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom BG Timer (seconds)") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() } },
                label = { Text("Seconds (5 .. 1800)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.toIntOrNull() ?: initialSecs) }) {
                Text("Set")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatDuration(secs: Int): String {
    return if (secs >= 60 && secs % 60 == 0) "${secs / 60}m" else "${secs}s"
}
