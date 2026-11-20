package org.levimc.zaphre.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.levimc.zaphre.ai.LocalLlama
import org.levimc.zaphre.ui.theme.*

@Composable
fun LocalModelsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by LocalLlama.models.collectAsState()
    val status by LocalLlama.status.collectAsState()
    val loaded by LocalLlama.loaded.collectAsState()
    val transferring by LocalLlama.transferring.collectAsState()
    val options by LocalLlama.options.collectAsState()
    var query by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var deleteName by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) LocalLlama.startImport(context, uri)
    }
    LaunchedEffect(Unit) { LocalLlama.restoreOptions(context); LocalLlama.refresh(context) }
    if (deleteName != null) {
        AlertDialog(
            onDismissRequest = { deleteName = null },
            title = { Text("Delete model?") },
            text = { Text("${deleteName} will be removed from ZaphRE's storage. Imported original files are not deleted.") },
            confirmButton = { TextButton(onClick = {
                val name = deleteName; deleteName = null
                if (name != null) scope.launch { runCatching { LocalLlama.deleteModel(context, name) }.onFailure { LocalLlama.status.value = "Delete failed: ${it.message}" } }
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteName = null }) { Text("Cancel") } }
        )
    }
    LazyColumn(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Local Models", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ZaphText)
            Text("On-device GGUF · CPU safe mode · no Termux needed", color = ZaphSubtext, fontSize = 12.sp)
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (loaded != null) "Loaded: $loaded" else "No model loaded", fontWeight = FontWeight.SemiBold)
                    Text(status, fontSize = 12.sp)
                    if (transferring) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { LocalLlama.cancelTransfer() }) { Text("Cancel transfer") }
                        Text("Keep app running during download. A cancelled transfer restarts from zero.", fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !transferring) { Text("Import GGUF") }
                        if (loaded != null) OutlinedButton(onClick = { scope.launch { LocalLlama.unloadModel() } }) { Text("Unload") }
                    }
                }
            }
        }
        item { Text("Downloaded models", color = ZaphText, fontWeight = FontWeight.Bold) }
        if (models.isEmpty()) item { Text("None yet — download or import one below.", color = ZaphSubtext, fontSize = 12.sp) }
        items(models, key = { it }) { name ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(name, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.loadLocalModel(name) }) { Text(if (loaded == name) "Reload" else "Load & use") }
                        TextButton(onClick = { deleteName = name }) { Text("Delete") }
                    }
                }
            }
        }
        item {
            Text("Recommended downloads", color = ZaphText, fontWeight = FontWeight.Bold)
            Text(status, color = ZaphSubtext, fontSize = 12.sp)
            if (transferring) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { LocalLlama.cancelTransfer() }) { Text("Cancel transfer") }
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search model list") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        items(LocalLlama.catalogue.filter { it.name.contains(query, ignoreCase = true) }, key = { it.url }) { model ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(model.name, modifier = Modifier.weight(1f), fontSize = 12.sp)
                    TextButton(onClick = { LocalLlama.startDownload(context, model) }, enabled = !transferring) { Text("Download") }
                }
            }
        }
        item {
            Text("Custom GGUF URL", color = ZaphText, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = customUrl, onValueChange = { customUrl = it }, label = { Text("Direct HTTPS file URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(onClick = { LocalLlama.startDownload(context, LocalLlama.CatalogueItem("Custom model", customUrl.trim())) }, enabled = !transferring && customUrl.startsWith("https://")) { Text("Download URL") }
            Text("Only use trusted GGUF files. Gated files requiring login are not supported.", color = ZaphSubtext, fontSize = 11.sp)
        }
        item {
            Text("CPU inference settings", color = ZaphText, fontWeight = FontWeight.Bold)
            Text("Context: ${options.context} tokens · reload model to apply", color = ZaphSubtext, fontSize = 12.sp)
            Slider(value = options.context.toFloat(), onValueChange = { LocalLlama.saveOptions(context, options.copy(context = (it / 1024).toInt() * 1024)) }, valueRange = 2048f..16384f, steps = 13)
            Text("CPU threads: ${options.threads} · reload model to apply", color = ZaphSubtext, fontSize = 12.sp)
            Slider(value = options.threads.toFloat(), onValueChange = { LocalLlama.saveOptions(context, options.copy(threads = it.toInt())) }, valueRange = 1f..8f, steps = 6)
            Text("Temperature: ${"%.1f".format(options.temperature)} · reload model to apply", color = ZaphSubtext, fontSize = 12.sp)
            Slider(value = options.temperature, onValueChange = { LocalLlama.saveOptions(context, options.copy(temperature = it)) }, valueRange = 0f..1.5f)
            Text("Max output: ${options.maxTokens} tokens", color = ZaphSubtext, fontSize = 12.sp)
            Slider(value = options.maxTokens.toFloat(), onValueChange = { LocalLlama.saveOptions(context, options.copy(maxTokens = (it / 128).toInt() * 128)) }, valueRange = 128f..2048f, steps = 14)
            Text("Larger contexts use more RAM. GPU/NPU acceleration and vision are not available in this build.", color = ZaphSubtext, fontSize = 11.sp)
        }
    }
}
