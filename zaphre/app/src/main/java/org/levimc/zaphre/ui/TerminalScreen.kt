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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.levimc.zaphre.terminal.ShellExecutor
import org.levimc.zaphre.ui.theme.ZaphAccent
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

private val QUICK_CMDS = listOf(
    "ls -lh" to "ls -lh",
    "zaphkiel_log" to "tail -n 50 zaphkiel_log.txt",
    "signatures.json" to "cat signatures.json",
    "zaphkiel_out" to "head -n 80 zaphkiel_out.txt",
    "pseudo-C" to "head -n 80 zaphkiel_out.c",
    "count dumps" to "wc -l *.txt *.c 2>/dev/null",
    "grep RTTI" to "grep -i 'Block' rtti_dump.txt | head -n 40"
)

@Composable
fun TerminalScreen(vm: MainViewModel) {
    val history by vm.termHistory.collectAsState()
    val busy by vm.termBusy.collectAsState()
    val engine by vm.termEngine.collectAsState()
    val termuxOnline by vm.termuxOnline.collectAsState()
    val clipboard = LocalClipboardManager.current

    var cmdText by rememberSaveable { mutableStateOf("") }
    var showTermuxDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(history.lastIndex) }
        }
    }

    if (showTermuxDialog) {
        val script = remember { vm.getTermuxSetupScript() }
        AlertDialog(
            onDismissRequest = { showTermuxDialog = false },
            title = { Text("Connect Real Termux") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Connects ZaphRE & the AI agent to your phone's Termux (python3, rg, readelf, llvm-objdump, clang++, radare2) over authenticated localhost.",
                        fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                clipboard.setText(AnnotatedString(script))
                                showTermuxDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ZaphPrimary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy Termux Cmd", color = Color.White, fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = {
                            vm.autoStartTermuxBridge()
                            showTermuxDialog = false
                        }) {
                            Text("Auto-Run", fontSize = 11.sp)
                        }
                    }
                    Text(
                        "Paste once in Termux (keep Termux running in background). When the top badge turns green 'Termux ON', both CMD & AI use Termux automatically!",
                        fontSize = 11.sp,
                        color = ZaphSubtext
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showTermuxDialog = false }) { Text("Close") }
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
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("RE Terminal & Termux", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = ZaphText)
                Text("Shared with AI agent (\$WORKSPACE, \$MC_LIB, \$HEADERS)", fontSize = 11.sp, color = ZaphSubtext)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { showTermuxDialog = true },
                    shape = RoundedCornerShape(14.dp),
                    color = if (termuxOnline) ZaphGreen.copy(alpha = 0.16f) else ZaphSurfaceHigh,
                    border = BorderStroke(1.dp, if (termuxOnline) ZaphGreen else ZaphBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (termuxOnline) ZaphGreen else ZaphSubtext)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (termuxOnline) "Termux ON" else "Termux Setup",
                            color = if (termuxOnline) ZaphGreen else ZaphText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                IconButton(onClick = { vm.runUserCommand("clear") }) {
                    Icon(Icons.Default.DeleteSweep, "Clear", tint = ZaphSubtext)
                }
            }
        }

        // Engine selector + quick commands
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ShellExecutor.Engine.entries.forEach { e ->
                val shortLabel = when (e) {
                    ShellExecutor.Engine.AUTO -> "Auto"
                    ShellExecutor.Engine.BUILTIN_SH -> "Android sh"
                    ShellExecutor.Engine.TERMUX_BRIDGE -> "Termux"
                }
                FilterChip(
                    selected = engine == e,
                    onClick = { vm.setTerminalEngine(e) },
                    label = { Text(shortLabel, fontSize = 11.sp) }
                )
            }
            QUICK_CMDS.forEach { (label, cmd) ->
                FilterChip(
                    selected = false,
                    onClick = { vm.runUserCommand(cmd) },
                    label = { Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                )
            }
        }
        HorizontalDivider(color = ZaphBorder.copy(alpha = 0.6f))

        // Terminal scrollback
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ZaphCodeBg),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (history.isEmpty()) {
                item {
                    Text(
                        text = "ZaphRE Shell Ready.\n• Built-in toybox: grep, awk, sed, head, tail, wc, sort, strings, xxd, find\n• Tap 'Termux Setup' above to attach full Termux (python3, rg, readelf, llvm-objdump, clang++)\n• Type any command below or tap a quick chip.",
                        color = ZaphSubtext,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            items(history) { item ->
                Column {
                    Text(
                        text = "$ ${item.command}",
                        color = ZaphGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(
                                text = item.output,
                                color = if (item.isError) ZaphRed.copy(alpha = 0.9f) else ZaphText,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // Command input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZaphSurface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$", color = ZaphGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = cmdText,
                onValueChange = { cmdText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("grep -rn LiquidBlock .", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = ZaphText),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val c = cmdText
                    cmdText = ""
                    vm.runUserCommand(c)
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ZaphCodeBg,
                    unfocusedContainerColor = ZaphCodeBg,
                    focusedBorderColor = ZaphAccent,
                    unfocusedBorderColor = ZaphBorder
                )
            )
            Spacer(Modifier.width(6.dp))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp, color = ZaphAccent)
            } else {
                IconButton(onClick = {
                    val c = cmdText
                    cmdText = ""
                    vm.runUserCommand(c)
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Run", tint = ZaphAccent)
                }
            }
        }
    }
}
