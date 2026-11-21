package org.levimc.zaphre.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.levimc.zaphre.files.BackupFolder
import org.levimc.zaphre.files.SelectedFolderTools
import org.levimc.zaphre.ui.theme.*

@Composable
fun SelectedFolderScreen(onClose: () -> Unit, onPick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<SelectedFolderTools.Entry>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var createType by remember { mutableStateOf<Boolean?>(null) }
    var rename by remember { mutableStateOf<SelectedFolderTools.Entry?>(null) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var editTree by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var tree by remember { mutableStateOf(BackupFolder.selected(context)?.toString().orEmpty()) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("peta_backup_folder", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "tree") { tree = BackupFolder.selected(context)?.toString().orEmpty(); path = ""; selectedFile = null }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun full(name: String) = if (path.isBlank()) name else "$path/$name"
    fun runOp(block: () -> Unit) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { error = ""; version++ }
                .onFailure { error = it.message ?: "Operation failed" }
        }
    }
    LaunchedEffect(path, version, tree) {
        if (tree.isNotBlank()) runCatching { withContext(Dispatchers.IO) { SelectedFolderTools.entries(context, path) } }
            .onSuccess { entries = it; error = "" }.onFailure { entries = emptyList(); error = it.message ?: "Cannot open folder" }
    }
    createType?.let { isDir ->
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { createType = null }, title = { Text(if (isDir) "New folder" else "New file") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { val target = full(name); val selected = tree; createType = null; runOp { SelectedFolderTools.create(context, target, isDir, "", selected) } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { createType = null }) { Text("Cancel") } })
    }
    rename?.let { item ->
        var name by remember(item.name) { mutableStateOf(item.name) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename ${item.name}") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { val target = full(item.name); val selected = tree; rename = null; runOp { SelectedFolderTools.rename(context, target, name, selected) } }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } })
    }
    if (confirmDelete || confirmSave) {
        val deleting = confirmDelete
        AlertDialog(onDismissRequest = { confirmDelete = false; confirmSave = false }, title = { Text(if (deleting) "Delete file?" else "Replace file?") },
            text = { Text("${selectedFile.orEmpty()}\nA recovery copy will be stored in the workspace. Provider writes may not be atomic.") },
            confirmButton = { TextButton(onClick = {
                val target = selectedFile ?: return@TextButton
                val content = if (deleting) null else text
                val expected = SelectedFolderTools.digest(original)
                val selected = editTree
                confirmDelete = false; confirmSave = false
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { SelectedFolderTools.modify(context, target, expected, content, selected) } }
                        .onSuccess { selectedFile = null; error = it; version++ }
                        .onFailure { error = it.message ?: "Operation failed" }
                }
            }) { Text(if (deleting) "Delete" else "Save") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false; confirmSave = false }) { Text("Cancel") } })
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { if (selectedFile != null) selectedFile = null else if (path.isNotBlank()) path = path.substringBeforeLast('/', "") else onClose() }) { Text("Back") }
            Text("Selected folder", color = ZaphText, modifier = Modifier.weight(1f))
            TextButton(onClick = onPick) { Text("Choose") }
        }
        if (error.isNotBlank()) Text(error, color = ZaphSubtext, fontSize = 11.sp)
        if (tree.isBlank()) Text("Choose a folder to browse.", color = ZaphSubtext)
        else if (selectedFile != null) {
            Text(selectedFile.orEmpty(), color = ZaphText, fontSize = 12.sp)
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("UTF-8 text, up to 64 KiB") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirmSave = true }, enabled = text != original) { Text("Save") }
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        } else {
            Text("/$path", color = ZaphSubtext, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { createType = false }) { Text("New file") }
                OutlinedButton(onClick = { createType = true }) { Text("New folder") }
                TextButton(onClick = { version++ }) { Text("Refresh") }
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(entries, key = { it.name }) { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = {
                            val target = full(entry.name)
                            if (entry.directory) path = target else scope.launch {
                                val selected = tree
                                runCatching { withContext(Dispatchers.IO) { SelectedFolderTools.read(context, target) } }
                                    .onSuccess { original = it; text = it; editTree = selected; selectedFile = target; error = "" }
                                    .onFailure { error = it.message ?: "Cannot read file" }
                            }
                        }, modifier = Modifier.weight(1f)) { Text((if (entry.directory) "📁 " else "📄 ") + entry.name) }
                        TextButton(onClick = { rename = entry }) { Text("Rename") }
                    }
                }
            }
        }
    }
}
