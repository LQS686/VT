package io.vt.framework.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.vt.container.GuestLauncher
import io.vt.container.data.GuestRecord
import io.vt.container.data.GuestStore
import io.vt.container.data.InstalledApp
import io.vt.container.data.LaunchOutcome
import io.vt.container.data.ModuleRecord
import io.vt.container.data.ModuleStore
import io.vt.container.imports.GuestImporter
import io.vt.container.imports.ModuleImporter
import io.vt.xposed.XposedLog
import io.vt.xposed.XposedRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VtRoot()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VtRoot() {
    val context = LocalContext.current
    val store = remember { GuestStore.get(context) }
    val moduleStore = remember { ModuleStore.get(context) }
    val importer = remember { GuestImporter(context, store) }
    val moduleImporter = remember { ModuleImporter(context, moduleStore) }
    val launcher = remember { GuestLauncher(context, store) }
    val guests by store.guests.collectAsState()
    val modules by moduleStore.modules.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var tab by remember { mutableIntStateOf(0) }
    var importOpen by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var selectedGuestId by remember { mutableStateOf<String?>(null) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busyText = "正在导入 APK"
                val result = importer.importApkFile(uri) { busyText = it }
                busyText = null
                if (result == null) {
                    snackbar.showSnackbar("导入失败，请查看日志")
                } else {
                    snackbar.showSnackbar("已导入 ${result.label}")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { moduleStore.refresh() }
    }

    val moduleFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busyText = "正在导入模块"
                val module = moduleImporter.importApk(uri)
                busyText = null
                snackbar.showSnackbar(if (module != null) "模块已导入：${module.label}" else "导入失败，请查看日志")
            }
        }
    }

    LaunchedEffect(importOpen) {
        if (importOpen && installedApps == null) {
            installedApps = withContext(Dispatchers.IO) { importer.listInstalledApps() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(
                    when (tab) {
                        0 -> "VT 框架"
                        1 -> "Xposed 模块"
                        2 -> "运行日志"
                        else -> "设置"
                    }
                )
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Apps, contentDescription = null) },
                    label = { Text("应用") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = {
                        tab = 1
                        scope.launch {
                            busyText = "扫描模块"
                            moduleStore.refresh()
                            busyText = null
                        }
                    },
                    icon = { Icon(Icons.Filled.Extension, contentDescription = null) },
                    label = { Text("模块") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Article, contentDescription = null) },
                    label = { Text("日志") }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = { importOpen = true }) {
                    Icon(Icons.Filled.Android, contentDescription = "导入应用")
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> AppsScreen(
                    guests = guests,
                    onLaunch = { guest ->
                        scope.launch {
                            busyText = "启动 ${guest.label}"
                            if (guest.autoUpdate) {
                                runCatching { importer.updateFromDevice(guest) }
                            }
                            busyText = null
                            when (val outcome = launcher.launch(guest)) {
                                is LaunchOutcome.Success -> Unit
                                is LaunchOutcome.Failure -> snackbar.showSnackbar(outcome.message)
                            }
                        }
                    },
                    onOpen = { selectedGuestId = it.id },
                    onImport = { importOpen = true }
                )
                1 -> ModulesScreen(
                    modules = modules,
                    busy = busyText,
                    onRefresh = {
                        scope.launch {
                            busyText = "扫描模块"
                            moduleStore.refresh()
                            busyText = null
                        }
                    },
                    onImport = { moduleFilePicker.launch(arrayOf("application/vnd.android.package-archive", "*/*")) }
                )
                2 -> LogsScreen()
                else -> SettingsScreen(
                    guestsCount = guests.size,
                    modulesCount = modules.size
                )
            }
            busyText?.let {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(it)
                    }
                }
            }
        }
    }

    if (importOpen) {
        val apps = installedApps
        AlertDialog(
            onDismissRequest = { importOpen = false },
            title = { Text("把应用导入 VT") },
            text = {
                Column {
                    Text("选择已安装应用；导入后会复制 APK 与原生库，原应用不受影响。列表已隐藏系统应用与游戏。")
                    Spacer(Modifier.height(12.dp))
                    if (apps == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在读取应用列表")
                        }
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            items(apps, key = { it.packageName }) { app ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(app.label, fontWeight = FontWeight.Medium)
                                        Text(
                                            "${app.packageName}  ${app.versionName}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (app.isXposedModule) {
                                            Text("Xposed 模块", fontSize = 11.sp)
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = {
                                        scope.launch {
                                            busyText = "正在导入 ${app.label}"
                                            val record = importer.importInstalled(app.packageName) { busyText = it }
                                            busyText = null
                                            if (record == null) {
                                                snackbar.showSnackbar("导入失败，请查看日志")
                                            } else {
                                                importOpen = false
                                                snackbar.showSnackbar("已导入 ${record.label}，可在详情中勾选 Xposed 模块")
                                            }
                                        }
                                    }) { Text("导入") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { filePicker.launch(arrayOf("application/vnd.android.package-archive", "*/*")) }) {
                    Text("从 APK 文件导入")
                }
            },
            dismissButton = { TextButton(onClick = { importOpen = false }) { Text("关闭") } }
        )
    }

    val selected = guests.firstOrNull { it.id == selectedGuestId }
    if (selected != null) {
        val liveModules = modules
        GuestDetailDialog(
            guest = selected,
            modules = liveModules,
            onDismiss = { selectedGuestId = null },
            onToggleModule = { pkg, enabled ->
                val updated = selected.enabledModules.toMutableSet()
                if (enabled) updated.add(pkg) else updated.remove(pkg)
                store.setEnabledModules(selected.id, updated)
            },
            onToggleAutoUpdate = { store.setAutoUpdate(selected.id, it) },
            onLaunch = {
                scope.launch {
                    busyText = "启动 ${selected.label}"
                    if (selected.autoUpdate) runCatching { importer.updateFromDevice(selected) }
                    busyText = null
                    when (val outcome = launcher.launch(selected)) {
                        is LaunchOutcome.Success -> Unit
                        is LaunchOutcome.Failure -> snackbar.showSnackbar(outcome.message)
                    }
                }
            },
            onCheckUpdate = {
                scope.launch {
                    busyText = "检查更新"
                    val status = importer.checkUpdate(selected)
                    busyText = null
                    snackbar.showSnackbar(status.message)
                    if (status.updateAvailable) {
                        val updated = importer.updateFromDevice(selected)
                        if (updated != null) {
                            snackbar.showSnackbar("已更新到 ${updated.versionName}，数据与模块选择已保留")
                        }
                    }
                }
            },
            onClearData = {
                launcher.stop(selected)
                scope.launch {
                    kotlinx.coroutines.delay(600)
                    withContext(Dispatchers.IO) {
                        File(selected.dataDir).deleteRecursively()
                        store.ensureGuestLayout(selected)
                    }
                    snackbar.showSnackbar("已清除 ${selected.label} 的容器数据")
                }
            },
            onStop = {
                launcher.stop(selected)
                scope.launch { snackbar.showSnackbar("已请求停止 ${selected.label}") }
            },
            onDelete = { confirmDeleteId = selected.id }
        )
    }

    if (confirmDeleteId != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("删除导入的应用？") },
            text = { Text("将删除容器内的 APK、原生库和全部数据，真机原应用不受影响。请先确认该应用未在运行。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = confirmDeleteId
                    confirmDeleteId = null
                    selectedGuestId = null
                    if (id != null) {
                        store.getById(id)?.let { launcher.stop(it) }
                        scope.launch(Dispatchers.IO) {
                            kotlinx.coroutines.delay(500)
                            store.getById(id)?.let { File(store.guestRoot(it).absolutePath).deleteRecursively() }
                            store.remove(id)
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteId = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun AppsScreen(
    guests: List<GuestRecord>,
    onLaunch: (GuestRecord) -> Unit,
    onOpen: (GuestRecord) -> Unit,
    onImport: () -> Unit
) {
    if (guests.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("还没有导入应用", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("点击右下角 + 导入，然后在应用详情中勾选 Xposed 模块。")
            Spacer(Modifier.height(20.dp))
            Button(onClick = onImport) { Text("导入应用") }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        items(guests, key = { it.id }) { guest ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                onClick = { onOpen(guest) }
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    GuestIcon(guest)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(guest.label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text(
                            "${guest.sourcePackage} · ${guest.versionName}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "模块 ${guest.enabledModules.size} 个 · ${guest.activities.size} 个 Activity",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { onLaunch(guest) }) { Text("启动") }
                }
            }
        }
    }
}

@Composable
private fun GuestIcon(guest: GuestRecord) {
    val bitmap = remember(guest.iconPath) {
        guest.iconPath?.let { BitmapFactory.decodeFile(it) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Android, contentDescription = null, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun ModulesScreen(
    modules: List<ModuleRecord>,
    busy: String?,
    onRefresh: () -> Unit,
    onImport: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("已识别 ${modules.size} 个 Xposed 模块", modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
        }
        if (modules.isEmpty()) {
            Column(Modifier.padding(24.dp)) {
                Text("未检测到 Xposed 模块")
                Spacer(Modifier.height(8.dp))
                Text("请在真机安装模块后刷新；模块需要声明 xposedmodule 元数据或 assets/xposed_init。")
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onImport) { Text("导入模块 APK（实验）") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                items(modules, key = { it.packageName }) { module ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(module.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${module.packageName} · ${module.versionName}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (module.description.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(module.description, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        if (busy != null) {
            Text(busy, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun LogsScreen() {
    val context = LocalContext.current
    var content by remember { mutableStateOf("") }
    var refreshToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshToken) {
        content = withContext(Dispatchers.IO) {
            val file = File(File(context.filesDir, "logs"), "xposed.log")
            if (file.isFile) file.readLines().takeLast(400).joinToString("\n") else "暂无日志"
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("容器与模块日志", modifier = Modifier.weight(1f))
            IconButton(onClick = { refreshToken++ }) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
            TextButton(onClick = {
                File(File(context.filesDir, "logs"), "xposed.log").delete()
                refreshToken++
            }) { Text("清空") }
        }
        SelectionContainer(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Text(
                text = content,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun SettingsScreen(guestsCount: Int, modulesCount: Int) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("VT 框架 0.1.0", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text("免 root、免改包的 Xposed 容器", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))

        SettingsRow("Android 版本", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        SettingsRow("支持范围", "Android 9–15（minSdk 28；已在 Android 15 验证）")
        SettingsRow("ABI", Build.SUPPORTED_ABIS.joinToString(", "))
        SettingsRow("Hook 引擎", if (XposedRuntime.isReady()) "LSPlant（源码构建）+ Xposed API 82" else "LSPlant 未初始化")
        SettingsRow("已导入应用", guestsCount.toString())
        SettingsRow("已识别模块", modulesCount.toString())
        Spacer(Modifier.height(20.dp))

        Text("使用方式", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("1. 在「应用」页导入真机已安装应用（复制 APK / split / 原生库，不改原包）。")
        Text("2. 在应用详情中勾选要生效的 Xposed 模块。")
        Text("3. 点启动；应用在独立 :g0/:g1 进程内运行，模块只作用于该容器进程。")
        Text("4. 真机应用更新后，VT 会按版本号检测并复制新 APK，容器数据与模块选择保留。")
        Spacer(Modifier.height(16.dp))
        Text("已知限制", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("• 模块的 XSharedPreferences 需要手动镜像到 files/module_prefs（当前只读兼容）。")
        Text("• 资源替换（IXposedHookInitPackageResources）仅提供字符串级有限支持。")
        Text("• 多进程应用的非主进程、ContentProvider/Service 与签名级反容器检测仍在完善。")
        Spacer(Modifier.height(16.dp))
        SettingsRow("日志文件", "App files/logs/xposed.log")
    }
}

@Composable
private fun SettingsRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, modifier = Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun GuestDetailDialog(
    guest: GuestRecord,
    modules: List<ModuleRecord>,
    onDismiss: () -> Unit,
    onToggleModule: (String, Boolean) -> Unit,
    onToggleAutoUpdate: (Boolean) -> Unit,
    onLaunch: () -> Unit,
    onCheckUpdate: () -> Unit,
    onClearData: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(guest.label) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(guest.sourcePackage, fontSize = 12.sp)
                Text("版本 ${guest.versionName} · ${guest.activities.size} 个 Activity")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("随真机更新", modifier = Modifier.weight(1f))
                    Switch(checked = guest.autoUpdate, onCheckedChange = onToggleAutoUpdate)
                }
                Spacer(Modifier.height(8.dp))
                Text("Xposed 模块（仅对当前应用生效）", fontWeight = FontWeight.SemiBold)
                if (modules.isEmpty()) {
                    Text("没有可用模块，请先在「模块」页刷新或安装模块。", fontSize = 13.sp)
                } else {
                    modules.forEach { module ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(module.label, fontSize = 14.sp)
                                Text(module.packageName, fontSize = 11.sp)
                            }
                            Switch(
                                checked = guest.enabledModules.contains(module.packageName),
                                onCheckedChange = { onToggleModule(module.packageName, it) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLaunch, modifier = Modifier.weight(1f)) { Text("启动") }
                    OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("停止") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCheckUpdate, modifier = Modifier.weight(1f)) { Text("检查更新") }
                    OutlinedButton(onClick = onClearData, modifier = Modifier.weight(1f)) { Text("清除数据") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("删除导入") }
                Spacer(Modifier.height(10.dp))
                Text(
                    "提示：首次启动某应用时，模块会在启动 Activity 实例化与 guest Application 创建之前加载。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
