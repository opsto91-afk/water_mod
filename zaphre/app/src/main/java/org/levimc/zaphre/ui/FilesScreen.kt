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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.levimc.zaphre.files.FileSlice
import org.levimc.zaphre.files.WorkspaceFiles
import org.levimc.zaphre.ui.theme.ZaphAccent
import org.levimc.zaphre.ui.theme.ZaphAmber
import org.levimc.zaphre.ui.theme.ZaphBg
import org.levimc.zaphre.ui.theme.ZaphBorder
import org.levimc.zaphre.ui.theme.ZaphCodeBg
import org.levimc.zaphre.ui.theme.ZaphGreen
import org.levimc.zaphre.ui.theme.ZaphPrimary
import org.levimc.zaphre.ui.theme.ZaphSubtext
import org.levimc.zaphre.ui.theme.ZaphSurface
import org.levimc.zaphre.ui.theme.ZaphText

private val FILE_CATEGORIES = listOf("All", "Outputs", "Pseudo-C", "Signatures", "Dumps", "Headers", "Config")

@Composable
fun FilesScreen(
    vm: MainViewModel,
    onPickImportFile: () -> Unit,
    onPickBackupFolder: () -> Unit
) {
    val openedFile by vm.openedFile.collectAsState()
    val searchOutput by vm.fileSearchOutput.collectAsState()
    val folder by vm.currentFolder.collectAsState()
    val entries by vm.fileEntries.collectAsState()
    val wsPath by vm.workspacePath.collectAsState()

    var categoryFilter by rememberSaveable { mutableStateOf("All") }
    var createDialogFolder by remember { mutableStateOf<Boolean?>(null) }

    if (openedFile != null) {
        FileViewerPane(
            slice = openedFile!!,
            searchOutput = searchOutput,
            onClose = { vm.closeOpenedFile() },
            onLoadPage = { startLine -> vm.openFile(openedFile!!.relativePath, startLine) },
            onRequestRawForEdit = { vm.openFile(openedFile!!.relativePath, 1, rawForEdit = true) },
            onSearch = { q -> vm.searchOpenedFile(q, isRegex = false) },
            onSave = { content -> vm.saveFileContent(openedFile!!.relativePath, content) },
            onShare = { vm.shareFile(openedFile!!.relativePath) }
        )
        return
    }

    createDialogFolder?.let { isDir ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createDialogFolder = null },
            title = { Text(if (isDir) "New Folder" else "New File") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(if (isDir) "my_folder" else "LiquidBlock.h") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createNewEntry(name, isDir)
                    createDialogFolder = null
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { createDialogFolder = null }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZaphBg)
            .statusBarsPadding()
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (folder.isNotEmpty()) {
                IconButton(onClick = { vm.navigateUpFolder() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up", tint = ZaphText)
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text("RE Workspace Files", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = ZaphText)
                Text(
                    text = wsPath + folder,
                    color = ZaphSubtext,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { createDialogFolder = false }) {
                Icon(Icons.Default.NoteAdd, "New File", tint = ZaphSubtext, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { createDialogFolder = true }) {
                Icon(Icons.Default.CreateNewFolder, "New Folder", tint = ZaphSubtext, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onPickImportFile) {
                Icon(Icons.Default.UploadFile, "Import", tint = ZaphPrimary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { vm.refreshFiles() }) {
                Icon(Icons.Default.Refresh, "Refresh", tint = ZaphSubtext, modifier = Modifier.size(20.dp))
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPickBackupFolder) { Text("Choose backup folder") }
            TextButton(onClick = { vm.clearBackupFolder() }) { Text("Clear selection") }
        }
        Text("Selected folder receives completed chat backups and saved findings. RE workspace remains separate.", color = ZaphSubtext, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))

        // Category Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FILE_CATEGORIES.forEach { cat ->
                FilterChip(
                    selected = categoryFilter == cat,
                    onClick = { categoryFilter = cat },
                    label = { Text(cat, fontSize = 11.sp) }
                )
            }
        }
        HorizontalDivider(color = ZaphBorder.copy(alpha = 0.6f))

        val filtered = entries.filter { categoryFilter == "All" || it.isDirectory || it.category == categoryFilter }
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No files in this view yet.", color = ZaphSubtext, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(filtered, key = { it.relativePath }) { entry ->
                    Surface(
                        onClick = {
                            if (entry.isDirectory) {
                                vm.refreshFiles(entry.relativePath)
                            } else {
                                vm.openFile(entry.relativePath, 1)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = ZaphSurface,
                        border = BorderStroke(1.dp, ZaphBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                contentDescription = null,
                                tint = when {
                                    entry.isDirectory -> ZaphAmber
                                    entry.category == "Signatures" -> ZaphGreen
                                    entry.category == "Pseudo-C" -> ZaphAccent
                                    else -> ZaphPrimary
                                },
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    color = ZaphText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${entry.category} · ${entry.formattedSize()} · ${entry.formattedTime()}",
                                    color = ZaphSubtext,
                                    fontSize = 11.sp
                                )
                            }
                            if (!entry.isDirectory) {
                                IconButton(
                                    onClick = { vm.shareFile(entry.relativePath) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Share, "Share", tint = ZaphSubtext, modifier = Modifier.size(16.dp))
                                }
                            }
                            IconButton(
                                onClick = { vm.deleteFileEntry(entry.relativePath) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, "Delete", tint = ZaphSubtext, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileViewerPane(
    slice: FileSlice,
    searchOutput: String?,
    onClose: () -> Unit,
    onLoadPage: (Int) -> Unit,
    onRequestRawForEdit: () -> Unit,
    onSearch: (String) -> Unit,
    onSave: (String) -> Unit,
    onShare: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var editing by remember { mutableStateOf(false) }
    var editBuffer by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    LaunchedEffect(editing, slice.content) {
        if (editing) {
            editBuffer = slice.content
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZaphBg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZaphSurface)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ZaphText)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    slice.relativePath,
                    color = ZaphText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Lines ${slice.startLine}..${slice.endLine} · ${WorkspaceFiles.formatBytes(slice.sizeBytes)}",
                    color = ZaphSubtext,
                    fontSize = 10.sp
                )
            }
            IconButton(onClick = { showSearch = !showSearch }) {
                Icon(Icons.Default.Search, "Search", tint = ZaphAccent, modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(slice.content)) }) {
                Icon(Icons.Default.ContentCopy, "Copy", tint = ZaphSubtext, modifier = Modifier.size(18.dp))
            }
            if (slice.sizeBytes < 256 * 1024) {
                if (editing) {
                    IconButton(onClick = {
                        onSave(editBuffer)
                        editing = false
                    }) {
                        Icon(Icons.Default.Save, "Save", tint = ZaphGreen, modifier = Modifier.size(20.dp))
                    }
                } else {
                    IconButton(onClick = {
                        editing = true
                        onRequestRawForEdit()
                    }) {
                        Icon(Icons.Default.Edit, "Edit", tint = ZaphSubtext, modifier = Modifier.size(18.dp))
                    }
                }
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, "Share", tint = ZaphSubtext, modifier = Modifier.size(18.dp))
            }
        }

        if (showSearch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ZaphSurface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Grep inside file (works on 100MB+ dumps)", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { if (searchQuery.isNotBlank()) onSearch(searchQuery) },
                    colors = ButtonDefaults.buttonColors(containerColor = ZaphPrimary)
                ) {
                    Text("Find", color = Color.White, fontSize = 12.sp)
                }
            }
        }

        if (editing) {
            OutlinedTextField(
                value = editBuffer,
                onValueChange = { editBuffer = it },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ZaphText),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        } else {
            val display = searchOutput ?: slice.content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(ZaphCodeBg)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = display.ifEmpty { "(empty file)" },
                        color = ZaphText,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (searchOutput == null && (slice.startLine > 1 || slice.truncated)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ZaphSurface)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        enabled = slice.startLine > 1,
                        onClick = { onLoadPage((slice.startLine - 300).coerceAtLeast(1)) }
                    ) { Text("Prev 300", fontSize = 12.sp) }

                    OutlinedButton(
                        enabled = slice.truncated,
                        onClick = { onLoadPage(slice.endLine + 1) }
                    ) { Text("Next 300", fontSize = 12.sp) }
                }
            }
        }
    }
}
