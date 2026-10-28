package io.vt.container.imports

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Base64
import io.vt.container.data.GuestRecord
import io.vt.container.data.GuestStore
import io.vt.container.data.InstalledApp
import io.vt.container.data.ModuleStore
import io.vt.container.data.StoredActivityInfo
import io.vt.container.data.StoredProviderInfo
import io.vt.container.data.StoredServiceInfo
import io.vt.container.data.UpdateStatus
import io.vt.xposed.XposedLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class GuestImporter(
    private val context: Context,
    private val store: GuestStore
) {
    private val pm: PackageManager get() = context.packageManager

    suspend fun listInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(query, 0)
        val seen = HashSet<String>()
        val out = ArrayList<InstalledApp>()
        for (ri in resolved) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            val pkg = ai.packageName ?: continue
            if (pkg == context.packageName || !seen.add(pkg)) continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            if (ai.category == ApplicationInfo.CATEGORY_GAME) continue
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
            val module = runCatching { isXposedModule(pkg) }.getOrDefault(false)
            out.add(
                InstalledApp(
                    packageName = pkg,
                    label = label,
                    versionName = info?.versionName.orEmpty(),
                    versionCode = info?.longVersionCode ?: 0L,
                    isGame = ai.category == ApplicationInfo.CATEGORY_GAME,
                    isXposedModule = module
                )
            )
        }
        out.sortedBy { it.label.lowercase() }
    }

    suspend fun importInstalled(
        packageName: String,
        progress: (String) -> Unit = {}
    ): GuestRecord? = withContext(Dispatchers.IO) {
        try {
            progress("读取应用信息")
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES or
                PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES or
                PackageManager.GET_SIGNING_CERTIFICATES
            val packageInfo = pm.getPackageInfo(packageName, flags)
            val appInfo = packageInfo.applicationInfo ?: return@withContext null
            val launcher = resolveLauncher(packageName)
            if (launcher == null) {
                XposedLog.log("导入失败：$packageName 没有可启动的 Activity")
                return@withContext null
            }

            val existing = store.getById(packageName) ?: store.findBySourcePackage(packageName)
            val now = System.currentTimeMillis()
            val record = existing ?: GuestRecord(
                id = packageName,
                sourcePackage = packageName,
                importedAt = now
            )
            record.label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(packageName)
            record.versionName = packageInfo.versionName.orEmpty()
            record.versionCode = packageInfo.longVersionCode
            record.launcherActivity = launcher
            record.appClass = appInfo.className ?: "android.app.Application"
            record.applicationTheme = appInfo.theme
            record.applicationFlags = appInfo.flags
            record.targetSdk = appInfo.targetSdkVersion
            record.minSdk = appInfo.minSdkVersion
            record.updatedAt = now
            record.signatures = extractSignatures(packageInfo).toMutableList()
            record.importOrigin = "installed"
            // 真机安装路径。应用重装后路径会变（/data/app/~~<hash>==/...），
            // 所以每次导入都刷新为最新值；运行期即使该路径已失效也不回退容器副本。
            record.installedSourceDir = appInfo.sourceDir
            record.installedSplitSourceDirs = appInfo.splitSourceDirs?.toMutableList()
                ?: mutableListOf()
            record.installedNativeLibDir = appInfo.nativeLibraryDir
            record.installerPackage = (if (Build.VERSION.SDK_INT >= 30) {
                runCatching {
                    pm.getInstallSourceInfo(packageName).installingPackageName
                }.getOrNull()
            } else null) ?: runCatching { pm.getInstallerPackageName(packageName) }.getOrNull()
            record.firstInstallTime = packageInfo.firstInstallTime
            record.lastUpdateTime = packageInfo.lastUpdateTime
            record.dataDir = File(guestDir(record), "data").absolutePath
            // 真机语义：credentialProtectedDataDir 与 dataDir 同路径，
            // 只有 deviceProtectedDataDir 位于另一个受保护目录。
            record.credentialProtectedDataDir = record.dataDir
            record.deviceProtectedDataDir = File(guestDir(record), "data_de").absolutePath
            collectRuntimeMetadata(record, packageInfo, appInfo)
            store.ensureGuestLayout(record)

            progress("复制安装包")
            record.apkFiles = copyApks(appInfo, File(guestDir(record), "apk"))

            progress("解析 Manifest Receiver")
            record.receivers = AxmlManifestParser.parseReceivers(record.baseApk().orEmpty())
            XposedLog.log("导入 Receiver 数量: ${record.receivers.size}")

            progress("处理原生库")
            val libDir = File(guestDir(record), "lib")
            prepareDir(libDir)
            record.nativeLibDir = copyNativeLibraries(appInfo, record.apkFiles, libDir).absolutePath

            progress("解析组件")
            record.activities = collectActivities(packageInfo, launcher)
            record.providers = collectProviders(packageInfo)
            record.services = collectServices(packageInfo)
            record.metaData = collectMetaData(appInfo)

            progress("保存图标")
            record.iconPath = saveIcon(appInfo, File(guestDir(record), "icon.png"))

            if (existing != null) {
                record.enabledModules = existing.enabledModules
                record.autoUpdate = existing.autoUpdate
            }
            store.upsert(record)
            XposedLog.log("导入成功：${record.label} (${record.versionName}), apk=${record.apkFiles.size}, activities=${record.activities.size}")
            record
        } catch (t: Throwable) {
            XposedLog.log("导入 $packageName 失败")
            XposedLog.log(t)
            null
        }
    }

    suspend fun importApkFile(uri: Uri, progress: (String) -> Unit = {}): GuestRecord? =
        withContext(Dispatchers.IO) {
            try {
                progress("读取安装包")
                val temp = File(context.cacheDir, "import_${System.currentTimeMillis()}.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                } ?: return@withContext null

                val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES or
                    PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES or
                    PackageManager.GET_SIGNING_CERTIFICATES
                val packageInfo = pm.getPackageArchiveInfo(temp.absolutePath, flags)
                    ?: return@withContext null
                val appInfo = packageInfo.applicationInfo ?: return@withContext null
                appInfo.sourceDir = temp.absolutePath
                appInfo.publicSourceDir = temp.absolutePath
                val pkg = packageInfo.packageName

                val existing = store.getById(pkg) ?: store.findBySourcePackage(pkg)
                val launcher = resolveArchiveLauncher(packageInfo)
                if (launcher == null) {
                    XposedLog.log("文件导入失败：无法识别启动 Activity，建议在真机安装后再导入")
                    return@withContext null
                }
                val now = System.currentTimeMillis()
                val record = existing ?: GuestRecord(
                    id = pkg,
                    sourcePackage = pkg,
                    importedAt = now
                )
                record.label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(pkg)
                record.versionName = packageInfo.versionName.orEmpty()
                record.versionCode = packageInfo.longVersionCode
                record.launcherActivity = launcher
                record.appClass = appInfo.className ?: "android.app.Application"
                record.applicationTheme = appInfo.theme
                record.applicationFlags = appInfo.flags
                record.targetSdk = appInfo.targetSdkVersion
                record.minSdk = appInfo.minSdkVersion
                record.updatedAt = now
                record.signatures = extractSignatures(packageInfo).toMutableList()
                record.importOrigin = "file"
                record.installedSourceDir = null
                record.installedSplitSourceDirs = mutableListOf()
                record.installedNativeLibDir = null
                record.installerPackage = null
                record.firstInstallTime = 0L
                record.lastUpdateTime = 0L
                record.dataDir = File(guestDir(record), "data").absolutePath
                record.credentialProtectedDataDir = record.dataDir
                record.deviceProtectedDataDir = File(guestDir(record), "data_de").absolutePath
                collectRuntimeMetadata(record, packageInfo, appInfo)
                store.ensureGuestLayout(record)

                val apkDir = File(guestDir(record), "apk")
                prepareDir(apkDir)
                val base = File(apkDir, "base.apk")
                temp.copyTo(base, overwrite = true)
                base.setReadOnly()
                record.apkFiles = mutableListOf(base.absolutePath)

                record.receivers = AxmlManifestParser.parseReceivers(base.absolutePath)
                val libDir = File(guestDir(record), "lib")
                prepareDir(libDir)
                record.nativeLibDir = copyNativeLibraries(appInfo, record.apkFiles, libDir).absolutePath
                record.activities = collectActivities(packageInfo, launcher)
                record.providers = collectProviders(packageInfo)
                record.services = collectServices(packageInfo)
                record.metaData = collectMetaData(appInfo)
                record.iconPath = saveIcon(appInfo, File(guestDir(record), "icon.png"))
                if (existing != null) {
                    record.enabledModules = existing.enabledModules
                    record.autoUpdate = existing.autoUpdate
                }
                temp.delete()
                store.upsert(record)
                XposedLog.log("文件导入成功：${record.label} ${record.versionName}")
                record
            } catch (t: Throwable) {
                XposedLog.log("文件导入失败")
                XposedLog.log(t)
                null
            }
        }

    suspend fun checkUpdate(guest: GuestRecord): UpdateStatus = withContext(Dispatchers.IO) {
        val info = runCatching { pm.getPackageInfo(guest.sourcePackage, 0) }.getOrNull()
            ?: return@withContext UpdateStatus(
                false, guest.versionName, null, "真机中未安装 ${guest.sourcePackage}，无法检查更新"
            )
        val newer = info.longVersionCode > guest.versionCode
        UpdateStatus(
            updateAvailable = newer,
            installedVersionName = guest.versionName,
            sourceVersionName = info.versionName,
            message = if (newer) "发现新版本 ${info.versionName}" else "已是最新版本"
        )
    }

    suspend fun updateFromDevice(guest: GuestRecord): GuestRecord? {
        val installed = runCatching { pm.getPackageInfo(guest.sourcePackage, 0) }.getOrNull()
            ?: return null
        if (installed.longVersionCode <= guest.versionCode) return null
        return importInstalled(guest.sourcePackage)
    }

    /**
     * 采集真机上该应用的元数据。
     *
     * 容器内自造的 ApplicationInfo / PackageInfo 只要有一个字段与真机不符，
     * 应用就能通过交叉比对（uid、dataDir、processName、签名、ABI 等）判定这是虚拟环境。
     * 因此这里逐项按真机值落地，运行期直接复用，不再由框架编造。
     */
    private fun collectRuntimeMetadata(
        record: GuestRecord,
        packageInfo: PackageInfo,
        appInfo: ApplicationInfo
    ) {
        record.appProcessName = appInfo.processName
        record.appTaskAffinity = appInfo.taskAffinity
        record.appCategory = appInfo.category
        // primaryCpuAbi 是 @hide 字段，公开 SDK 里取不到，只能反射读。
        record.primaryCpuAbi = readHiddenField(appInfo, "primaryCpuAbi") as? String
        record.requestedPermissions = packageInfo.requestedPermissions?.toMutableList()
            ?: mutableListOf()
        record.requestedPermissionsFlags = packageInfo.requestedPermissionsFlags?.toMutableList()
            ?: mutableListOf()

        val signingInfo = packageInfo.signingInfo
        if (signingInfo != null) {
            record.signingSchemeVersion = signingInfo.schemeVersion
            record.multipleSigners = signingInfo.hasMultipleSigners()
            record.signingHistory = signingInfo.signingCertificateHistory
                ?.map { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }
                ?.toMutableList()
                ?: mutableListOf()
        }
    }

    /** 读取 @hide 字段（如 ApplicationInfo.primaryCpuAbi）。 */
    private fun readHiddenField(target: Any, name: String): Any? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun guestDir(record: GuestRecord): File =
        File(File(context.filesDir, "guests"), record.id)

    private fun isXposedModule(pkg: String): Boolean {
        val ai = pm.getPackageInfo(pkg, PackageManager.GET_META_DATA).applicationInfo ?: return false
        val declared = ai.metaData?.get("xposedmodule")?.toString()?.equals("true", true) == true
        return declared || ModuleStore.hasXposedEntry(ai.sourceDir)
    }

    private fun resolveLauncher(pkg: String): String? {
        val launchIntent = pm.getLaunchIntentForPackage(pkg)?.component?.className
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(pkg)
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.name
            ?: pm.queryIntentActivities(intent, 0)
                .sortedByDescending { it.priority }
                .firstOrNull()?.activityInfo?.name
        XposedLog.log(
            "resolveLauncher($pkg): launchIntent=$launchIntent resolved=$resolved " +
                "candidates=" + pm.queryIntentActivities(intent, 0).joinToString {
                    "${it.activityInfo.name}@${it.priority}"
                }
        )
        val candidate = launchIntent ?: resolved ?: return null
        // Keep the launcher alias. Apps often branch on the component they were
        // started with (e.g. SplashActivity is an alias of the main Activity),
        // so replacing it with targetActivity skips startup work.
        XposedLog.log(
            "resolveLauncher($pkg): launcher=$candidate target=" +
                resolveActivityAlias(pkg, candidate)
        )
        return candidate
    }

    private fun resolveActivityAlias(pkg: String, className: String): String {
        return try {
            val info = pm.getActivityInfo(ComponentName(pkg, className), 0)
            info.targetActivity?.takeIf { it.isNotBlank() } ?: className
        } catch (_: Throwable) {
            className
        }
    }

    private fun resolveArchiveLauncher(info: PackageInfo): String? {
        val activities = info.activities ?: return null
        if (activities.isEmpty()) return null
        return activities.firstOrNull { !it.name.contains("$") }?.name ?: activities.first().name
    }

    private fun collectActivities(
        info: PackageInfo,
        launcher: String
    ): MutableMap<String, StoredActivityInfo> {
        val map = mutableMapOf<String, StoredActivityInfo>()
        info.activities?.forEach { activity ->
            map[activity.name] = StoredActivityInfo(
                name = activity.name,
                theme = activity.theme,
                launchMode = activity.launchMode,
                configChanges = activity.configChanges,
                screenOrientation = activity.screenOrientation,
                softInputMode = activity.softInputMode,
                exported = activity.exported,
                processName = activity.processName,
                taskAffinity = activity.taskAffinity,
                flags = activity.flags,
                parentActivityName = activity.parentActivityName,
                targetActivity = activity.targetActivity
            )
        }
        if (!map.containsKey(launcher)) {
            val info = runCatching {
                pm.getActivityInfo(ComponentName(info.packageName, launcher), 0)
            }.getOrNull()
            map[launcher] = info?.let {
                StoredActivityInfo(
                    name = it.name,
                    theme = it.theme,
                    launchMode = it.launchMode,
                    configChanges = it.configChanges,
                    screenOrientation = it.screenOrientation,
                    softInputMode = it.softInputMode,
                    exported = it.exported,
                    processName = it.processName,
                    taskAffinity = it.taskAffinity,
                    flags = it.flags,
                    parentActivityName = it.parentActivityName,
                    targetActivity = it.targetActivity
                )
            } ?: StoredActivityInfo(name = launcher, exported = true)
        }
        return map
    }

    private fun collectMetaData(info: ApplicationInfo): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        val meta = info.metaData ?: return result
        for (key in meta.keySet()) {
            when (val value = meta.get(key)) {
                is String -> result[key] = "s:$value"
                is Int -> result[key] = "i:$value"
                is Boolean -> result[key] = "b:$value"
                is Long -> result[key] = "l:$value"
                is Float -> result[key] = "f:$value"
                is Double -> result[key] = "d:$value"
                is ByteArray -> result[key] = "y:" + android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)
            }
        }
        return result
    }

    private fun collectServices(info: PackageInfo): MutableList<StoredServiceInfo> {
        val result = mutableListOf<StoredServiceInfo>()
        info.services?.forEach { service ->
            if (service.name.isNullOrBlank()) return@forEach
            result.add(
                StoredServiceInfo(
                    name = service.name,
                    processName = service.processName,
                    exported = service.exported,
                    permission = service.permission,
                    foregroundServiceType = service.foregroundServiceType,
                    flags = service.flags
                )
            )
        }
        return result
    }

    private fun collectProviders(info: PackageInfo): MutableList<StoredProviderInfo> {
        val result = mutableListOf<StoredProviderInfo>()
        info.providers?.forEach { provider ->
            val authorities = provider.authority?.split(';').orEmpty()
            for (authority in authorities) {
                if (authority.isBlank()) continue
                result.add(
                    StoredProviderInfo(
                        authority = authority,
                        className = provider.name.orEmpty(),
                        exported = provider.exported,
                        readPermission = provider.readPermission,
                        writePermission = provider.writePermission,
                        grantUriPermissions = provider.grantUriPermissions,
                        processName = provider.processName,
                        initOrder = provider.initOrder,
                        multiprocess = provider.multiprocess
                    )
                )
            }
        }
        return result
    }

    private fun copyApks(appInfo: ApplicationInfo, apkDir: File): MutableList<String> {
        prepareDir(apkDir)
        val sources = linkedSetOf<String>()
        appInfo.sourceDir?.let { sources.add(it) }
        appInfo.splitSourceDirs?.forEach { if (it.isNotBlank()) sources.add(it) }
        appInfo.splitPublicSourceDirs?.forEach { if (it.isNotBlank()) sources.add(it) }
        val result = mutableListOf<String>()
        sources.forEachIndexed { index, path ->
            val src = File(path)
            if (!src.isFile) return@forEachIndexed
            val name = if (index == 0 || path == appInfo.sourceDir) "base.apk" else "split_$index.apk"
            val dst = File(apkDir, name)
            src.copyTo(dst, overwrite = true)
            dst.setReadOnly()
            result.add(dst.absolutePath)
        }
        return result
    }

    private fun copyNativeLibraries(
        appInfo: ApplicationInfo,
        apkFiles: List<String>,
        libDir: File
    ): File {
        prepareDir(libDir)
        val nativeDir = appInfo.nativeLibraryDir?.let(::File)
        if (nativeDir != null && nativeDir.isDirectory) {
            nativeDir.listFiles()?.filter { it.isFile && it.name.endsWith(".so") }?.forEach { lib ->
                lib.copyTo(File(libDir, lib.name), overwrite = true)
            }
        }
        if (libDir.listFiles()?.any { it.name.endsWith(".so") } != true) {
            extractNativeLibraries(apkFiles, libDir)
        }
        return libDir
    }

    private fun extractNativeLibraries(apkFiles: List<String>, libDir: File) {
        val abis = Build.SUPPORTED_ABIS
        val chosen = abis.firstOrNull { abi ->
            apkFiles.any { path ->
                runCatching {
                    ZipFile(path).use { zip ->
                        zip.entries().asSequence().any {
                            it.name.startsWith("lib/$abi/") && it.name.endsWith(".so")
                        }
                    }
                }.getOrDefault(false)
            }
        } ?: return
        apkFiles.forEach { path ->
            runCatching {
                ZipFile(path).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.startsWith("lib/$chosen/") && it.name.endsWith(".so") }
                        .forEach { entry ->
                            val name = entry.name.substringAfterLast('/')
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(File(libDir, name)).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSignatures(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: info.signatures
        } else {
            info.signatures
        } ?: return emptyList()
        return signatures.map { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }
    }

    private fun saveIcon(appInfo: ApplicationInfo, target: File): String? {
        return try {
            val drawable: Drawable = pm.getApplicationIcon(appInfo)
            val bitmap = drawableToBitmap(drawable)
            FileOutputStream(target).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            target.absolutePath
        } catch (t: Throwable) {
            XposedLog.log("保存图标失败: $t")
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    private fun prepareDir(dir: File) {
        if (dir.exists()) {
            dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        }
        dir.mkdirs()
    }
}
