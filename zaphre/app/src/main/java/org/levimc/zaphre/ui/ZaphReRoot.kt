package org.levimc.zaphre.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.levimc.zaphre.ui.theme.ZaphBg
import org.levimc.zaphre.ui.theme.ZaphGreen
import org.levimc.zaphre.ui.theme.ZaphPrimary
import org.levimc.zaphre.ui.theme.ZaphSubtext
import org.levimc.zaphre.ui.theme.ZaphSurface
import org.levimc.zaphre.ui.theme.ZaphSurfaceHigh

@Composable
fun ZaphReRoot(
    vm: MainViewModel,
    onRequestAllFilesAccess: () -> Unit
) {
    val activeTab by vm.activeTab.collectAsState()
    val bgSnapshot by vm.bgSnapshot.collectAsState()
    val toastMsg by vm.toastMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) focusManager.clearFocus(force = true)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(toastMsg) {
        val m = toastMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        vm.clearToast()
    }

    // System file pickers for MC APK/.so, libZaphkiel.so/.so.txt/.levipack, extra mods, and workspace files
    val mcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importMcUri(uri)
    }
    val zaphPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModUri(uri, forceZaphkiel = true)
    }
    val extraModPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModUri(uri, forceZaphkiel = false)
    }
    val wsFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importWorkspaceFile(uri)
    }

    val backupFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.chooseBackupFolder(uri)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = ZaphBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = ZaphSurface,
                tonalElevation = 0.dp
            ) {
                MainTab.entries.forEach { tab ->
                    val selected = tab == activeTab
                    val icon = when (tab) {
                        MainTab.CHAT -> Icons.Outlined.ChatBubbleOutline
                        MainTab.BG_RUN -> Icons.Outlined.Memory
                        MainTab.FILES -> Icons.Outlined.Folder
                        MainTab.CMD -> Icons.Outlined.Terminal
                        MainTab.AI -> Icons.Outlined.Psychology
                        MainTab.MODELS -> Icons.Outlined.Download
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { vm.selectTab(tab) },
                        icon = {
                            if (tab == MainTab.BG_RUN && bgSnapshot.status.isActive) {
                                BadgedBox(badge = { Badge(containerColor = ZaphGreen) }) {
                                    Icon(icon, contentDescription = tab.label, modifier = Modifier.size(22.dp))
                                }
                            } else {
                                Icon(icon, contentDescription = tab.label, modifier = Modifier.size(22.dp))
                            }
                        },
                        label = { Text(tab.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ZaphPrimary,
                            selectedTextColor = ZaphPrimary,
                            indicatorColor = ZaphSurfaceHigh,
                            unselectedIconColor = ZaphSubtext,
                            unselectedTextColor = ZaphSubtext
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            when (activeTab) {
                MainTab.CHAT -> ChatScreen(
                    vm = vm,
                    onJumpToTab = { vm.selectTab(it) },
                    onImportZaphkiel = { zaphPicker.launch(arrayOf("*/*")) }
                )

                MainTab.BG_RUN -> BgMonitorScreen(
                    vm = vm,
                    onPickMc = { mcPicker.launch(arrayOf("*/*")) },
                    onPickZaphkiel = { zaphPicker.launch(arrayOf("*/*")) },
                    onPickExtraMod = { extraModPicker.launch(arrayOf("*/*")) },
                    onRequestAllFilesAccess = onRequestAllFilesAccess
                )

                MainTab.FILES -> FilesScreen(
                    vm = vm,
                    onPickImportFile = { wsFilePicker.launch(arrayOf("*/*")) },
                    onPickBackupFolder = { backupFolderPicker.launch(null) }
                )

                MainTab.CMD -> TerminalScreen(vm = vm)

                MainTab.MODELS -> LocalModelsScreen(vm = vm)
                MainTab.AI -> AiSettingsScreen(vm = vm)
            }
        }
    }
}
