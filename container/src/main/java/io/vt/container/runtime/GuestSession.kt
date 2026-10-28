package io.vt.container.runtime

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContentProvider
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Bundle
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.BroadcastReceiver
import android.content.ServiceConnection
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.Build
import android.os.Process
import android.util.Base64
import android.util.DisplayMetrics
import dalvik.system.DexClassLoader
import io.vt.container.data.GuestRecord
import io.vt.container.data.GuestStore
import io.vt.container.data.ModuleStore
import io.vt.container.data.StoredActivityInfo
import io.vt.container.data.syntheticInstalledNativeLibDir
import io.vt.container.data.syntheticInstalledSourceDir
import io.vt.container.data.syntheticInstalledSplitDir
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.vt.xposed.Hooker
import io.vt.xposed.ModuleSpec
import io.vt.xposed.XposedLog
import io.vt.xposed.XposedRuntime
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

class GuestSession(
    val host: Application,
    val record: GuestRecord,
    val slot: Int,
    val guestProcessName: String = record.sourcePackage
) {
    companion object {
        const val EXTRA_GUEST_ID = "vt.guest.id"
        const val EXTRA_GUEST_CLASS = "vt.guest.class"
        const val EXTRA_GUEST_COMPONENT = "vt.guest.component"
        const val EXTRA_GUEST_INTENT = "vt.guest.intent"
        const val EXTRA_GUEST_PROCESS = "vt.guest.process"
        const val EXTRA_GUEST_SERVICE_CLASS = "vt.guest.service.class"
        const val STUB_PACKAGE_PREFIX = "io.vt.container.stub.Stub"
        const val STUB_PACKAGE = "io.vt.container.stub"
    }

    lateinit var classLoader: DexClassLoader
    lateinit var resources: Resources
    lateinit var appInfo: ApplicationInfo
    private var publicAppInfoCache: ApplicationInfo? = null
    private var runtimeApkFiles: List<String> = emptyList()
    private var runtimeNativeLibDir: String? = null
    lateinit var loadedApk: Any
    lateinit var packageInfo: PackageInfo
    var guestApplication: Application? = null
    var modulesLoaded = false
    private var rpcCompatInitialized = false
    private var accountCompatInitialized = false
    private var providersInstalled = false
    private var staticReceiversInstalled = false
    private val providerTransports = HashMap<String, Any>()
    private val guestProviders = HashMap<String, ContentProvider>()

    private val guestServices = HashMap<String, GuestServiceInstance>()
    private val serviceStopCallbacks = HashMap<String, () -> Unit>()
    private val foregroundNotificationIds = HashMap<String, Int>()
    private val serviceBindings = HashMap<ServiceConnection, ComponentName>()
    private val registeredReceivers = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<BroadcastReceiver, Boolean>()
    )
    private val registeredActions = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val activityInfos = HashMap<String, ActivityInfo>()
    private val guestRoot: File = File(File(host.filesDir, "guests"), record.id)
    private val hostPackage: String = host.packageName

    @Synchronized
    fun initialize() {
        if (::classLoader.isInitialized) return
        require(record.apkFiles.isNotEmpty()) { "Guest APK is missing" }
        record.apkFiles.forEach {
            require(File(it).isFile) { "Guest APK not found: $it" }
        }

        // 应用仍安装在真机上时优先用真机 APK/lib 路径：
        // 原生安全 SDK 会把 dladdr/maps 里的实际加载路径与 PackageManager 视图比对，
        // 用容器副本会形成矛盾（内容接口 ILLEGAL_ACCESS 的可疑来源之一）。
        // 真机路径失效（已卸载/已更新）时回退到容器副本。
        val realApks = buildList {
            record.installedSourceDir?.takeIf { it.isNotBlank() && File(it).isFile && File(it).canRead() }?.let(::add)
            if (isNotEmpty()) {
                addAll(record.installedSplitSourceDirs.filter { it.isNotBlank() && File(it).isFile && File(it).canRead() })
            }
        }
        runtimeApkFiles = if (realApks.isNotEmpty() && realApks.size == record.apkFiles.size) {
            realApks
        } else {
            record.apkFiles
        }
        runtimeNativeLibDir = record.installedNativeLibDir
            ?.takeIf { it.isNotBlank() && File(it).isDirectory && File(it).canRead() }
            ?: record.nativeLibDir

        classLoader = DexClassLoader(
            runtimeApkFiles.joinToString(File.pathSeparator),
            File(guestRoot, "oat").apply { mkdirs() }.absolutePath,
            runtimeNativeLibDir,
            ClassLoader.getSystemClassLoader().parent ?: ClassLoader.getSystemClassLoader()
        )
        resources = buildResources()
        appInfo = buildApplicationInfo()
        loadedApk = createLoadedApk()
        setField(loadedApk, "mClassLoader", classLoader)
        setField(loadedApk, "mResources", resources)
        packageInfo = buildPackageInfo()
        XposedLog.log(
            "GuestSession ready: ${record.sourcePackage} v${record.versionName}, slot=$slot, " +
                "dex=${runtimeApkFiles.size}, lib=$runtimeNativeLibDir, data=${record.dataDir}"
        )
    }

    private fun buildResources(): Resources {
        val managerClass = AssetManager::class.java
        val manager = managerClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val addAssetPath = managerClass.getDeclaredMethod("addAssetPath", String::class.java)
        addAssetPath.isAccessible = true
        for (apk in runtimeApkFiles) {
            try {
                addAssetPath.invoke(manager, apk)
            } catch (t: Throwable) {
                XposedLog.log("addAssetPath failed for $apk: $t")
            }
        }
        val metrics = DisplayMetrics().also { it.setTo(host.resources.displayMetrics) }
        val configuration = Configuration(host.resources.configuration)
        val res = Resources(manager, metrics, configuration)
        res.updateConfiguration(configuration, metrics)
        return res
    }

    /**
 * 对 Guest 暴露的 ApplicationInfo 视图（"真机视图"）。
 *
 * 与 appInfo（框架内部视图）的区别只在安装路径：
 * - appInfo：指向容器内真实存在的 APK 副本，框架要靠它建 ClassLoader/Resources；
 * - publicAppInfo：指向真机安装路径（/data/app/~~<hash>==/<pkg>-<hash>==/base.apk），
 *   应用比对 PackageManager 结果时看到的是这个。
 *
 * 真机路径只在导入时采集得到；应用重装后路径会变，
 * 所以这里保留最后一次记录的真机路径形态，而不是回退到容器副本路径。
 */
    fun publicAppInfo(): ApplicationInfo {
        // 缓存：列表查询与直接查询必须返回同一份视图，
        // 否则 uid/dataDir/sourceDir 的比对会出现实例级差异。
        publicAppInfoCache?.let { return it }
        val info = ApplicationInfo(appInfo)
        val realSource = record.installedSourceDir?.takeIf { it.isNotBlank() }
            ?: syntheticInstalledSourceDir(record.sourcePackage)
        info.sourceDir = realSource
        info.publicSourceDir = realSource
        val realSplits = record.installedSplitSourceDirs.filter { it.isNotBlank() }
        info.splitSourceDirs = if (realSplits.isNotEmpty()) realSplits.toTypedArray() else null
        info.splitPublicSourceDirs = info.splitSourceDirs
        info.nativeLibraryDir = record.installedNativeLibDir?.takeIf { it.isNotBlank() }
            ?: syntheticInstalledNativeLibDir(record.sourcePackage)
        publicAppInfoCache = info
        return info
    }

    /**
     * 按 flags 返回 ApplicationInfo 视图。
     *
     * 真机 PMS 语义：GET_META_DATA 未请求时 metaData 为 null。
     * metaData 挂在 ApplicationInfo 上（PackageInfo 没有该字段），
     * 所以需要在 flags 不含 GET_META_DATA 时给出一份 metaData 为空的副本，
     * 否则应用会看到"没申请也拿到了 metaData"，与真机不一致。
     */
    fun publicAppInfoFor(flags: Int): ApplicationInfo {
        val base = publicAppInfo()
        if (flags and PackageManager.GET_META_DATA != 0) return base
        if (base.metaData == null) return base
        val copy = ApplicationInfo(base)
        copy.metaData = null
        return copy
    }

private fun buildApplicationInfo(): ApplicationInfo {
        val info = ApplicationInfo()
        info.packageName = record.sourcePackage
        info.className = record.appClass?.takeIf { it.isNotBlank() } ?: "android.app.Application"
        // 这里是"框架内部视图"：LoadedApk / DexClassLoader / Resources 都要真的能打开这些文件。
        // 优先与 DexClassLoader 实际使用的真机路径保持一致，避免加载路径与 PM 视图矛盾。
        info.sourceDir = runtimeApkFiles.firstOrNull() ?: record.baseApk()
        info.publicSourceDir = info.sourceDir
        info.splitSourceDirs = runtimeApkFiles.drop(1).toTypedArray()
        info.splitPublicSourceDirs = info.splitSourceDirs
        info.dataDir = record.dataDir
        // 真机语义：credentialProtectedDataDir 与 dataDir 同路径，
        // deviceProtectedDataDir 位于另一受保护目录。
        info.deviceProtectedDataDir = record.deviceProtectedDataDir.takeIf { it.isNotBlank() }
            ?: record.dataDir
        runCatching {
            setField(
                info,
                "credentialProtectedDataDir",
                record.credentialProtectedDataDir.takeIf { it.isNotBlank() } ?: record.dataDir
            )
        }
        info.nativeLibraryDir = runtimeNativeLibDir
        info.uid = Process.myUid()
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP or
            0x40000000
        info.flags = (record.applicationFlags and systemFlags.inv()) or ApplicationInfo.FLAG_HAS_CODE
        info.targetSdkVersion = record.targetSdk
        info.minSdkVersion = record.minSdk
        info.theme = record.applicationTheme
        info.processName = record.appProcessName?.takeIf { it.isNotBlank() }
            ?: record.sourcePackage
        info.taskAffinity = record.appTaskAffinity?.takeIf { it.isNotBlank() }
            ?: record.sourcePackage
        info.enabled = true
        runCatching { setField(info, "exported", true) }
        info.category = record.appCategory
        info.metaData = buildMetaData()
        try {
            setField(
                info,
                "primaryCpuAbi",
                record.primaryCpuAbi?.takeIf { it.isNotBlank() }
                    ?: Build.SUPPORTED_ABIS.firstOrNull()
            )
        } catch (_: Throwable) {
        }
        return info
    }

    /**
     * 构造 metaData Bundle。
     *
     * 真机上 getPackageInfo(pkg, GET_META_DATA) 与 getApplicationInfo(pkg, GET_META_DATA)
     * 都会带上 metaData；容器里如果缺失，依赖 manifest metaData 做初始化/校验的
     * 应用会走到"读不到配置"的降级分支。
     */
    private fun buildMetaData(): Bundle? {
        if (record.metaData.isEmpty()) return null
        val bundle = Bundle()
        for ((key, encoded) in record.metaData) {
            try {
                when {
                    encoded.startsWith("s:") -> bundle.putString(key, encoded.substring(2))
                    encoded.startsWith("i:") -> bundle.putInt(key, encoded.substring(2).toInt())
                    encoded.startsWith("b:") -> bundle.putBoolean(key, encoded.substring(2).toBoolean())
                    encoded.startsWith("l:") -> bundle.putLong(key, encoded.substring(2).toLong())
                    encoded.startsWith("f:") -> bundle.putFloat(key, encoded.substring(2).toFloat())
                    encoded.startsWith("d:") -> bundle.putDouble(key, encoded.substring(2).toDouble())
                    encoded.startsWith("y:") -> bundle.putByteArray(
                        key,
                        Base64.decode(encoded.substring(2), Base64.NO_WRAP)
                    )
                }
            } catch (_: Throwable) {
            }
        }
        return bundle
    }

    /**
     * 对 Guest 暴露的 ServiceInfo。
     *
     * 真机上 getServiceInfo(ComponentName, flags) 会返回该 Service 的完整信息。
     * 容器里如果不接管，原方法要么抛 NameNotFoundException（目标应用真机已卸载），
     * 要么返回宿主进程视角的错误值——应用据此可以判定"我在容器里"。
     */
    fun serviceInfoFor(className: String): android.content.pm.ServiceInfo? {
        val stored = record.services.firstOrNull { it.name == className } ?: return null
        return android.content.pm.ServiceInfo().apply {
            name = stored.name
            packageName = record.sourcePackage
            applicationInfo = publicAppInfo()
            processName = stored.processName?.takeIf { it.isNotBlank() }
                ?: record.sourcePackage
            exported = stored.exported
            permission = stored.permission
            this.flags = stored.flags
            enabled = true
        }.also { svc ->
            if (stored.foregroundServiceType != 0) {
                runCatching {
                    val setter = android.content.pm.ServiceInfo::class.java
                        .getDeclaredMethod(
                            "setForegroundServiceType", Int::class.javaPrimitiveType
                        )
                        .apply { isAccessible = true }
                    setter.invoke(svc, stored.foregroundServiceType)
                }
            }
        }
    }

    /** 对 Guest 暴露的 ReceiverInfo（Receiver 在 PM 里也是 ActivityInfo）。 */
    fun receiverInfoFor(className: String): ActivityInfo? {
        val stored = record.receivers.firstOrNull { it.name == className } ?: return null
        return ActivityInfo().apply {
            name = stored.name
            packageName = record.sourcePackage
            applicationInfo = publicAppInfo()
            processName = stored.processName?.takeIf { it.isNotBlank() }
                ?: record.sourcePackage
            exported = stored.exported
            enabled = true
        }
    }

    /**
     * 对 Guest 暴露的 ProviderInfo。
     *
     * 真机 getProviderInfo(ComponentName, flags) 的第一个参数是
     * ComponentName(packageName, providerClassName)，authority 由 ProviderInfo 暴露。
     */
    fun providerInfoFor(providerClassName: String): ProviderInfo? {
        val stored = record.providers.firstOrNull {
            it.className == providerClassName || it.authority == providerClassName
        } ?: return null
        return ProviderInfo().apply {
            authority = stored.authority
            name = stored.className
            packageName = record.sourcePackage
            applicationInfo = publicAppInfo()
            processName = stored.processName?.takeIf { it.isNotBlank() }
                ?: record.sourcePackage
            exported = stored.exported
            readPermission = stored.readPermission
            writePermission = stored.writePermission
            grantUriPermissions = stored.grantUriPermissions
            initOrder = stored.initOrder
            multiprocess = stored.multiprocess
        }
    }

    private fun createLoadedApk(): Any {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
        val compatibilityClass = Class.forName("android.content.res.CompatibilityInfo")
        val defaultCompatibility = compatibilityClass
            .getDeclaredField("DEFAULT_COMPATIBILITY_INFO")
            .apply { isAccessible = true }
            .get(null)

        for (method in activityThreadClass.declaredMethods) {
            if (method.name != "getPackageInfoNoCheck") continue
            method.isAccessible = true
            val args = method.parameterTypes.map { type ->
                when {
                    type == ApplicationInfo::class.java -> appInfo
                    type == compatibilityClass -> defaultCompatibility
                    type == Boolean::class.javaPrimitiveType -> false
                    type == Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }.toTypedArray()
            try {
                val result = method.invoke(activityThread, *args)
                if (result != null) return result
            } catch (t: Throwable) {
                XposedLog.log("getPackageInfoNoCheck(${method.parameterCount}) failed: $t")
            }
        }

        XposedLog.log("Falling back to LoadedApk constructor")
        val loadedApkClass = Class.forName("android.app.LoadedApk")
        val constructor = loadedApkClass.declaredConstructors.firstOrNull {
            it.parameterCount >= 3 && it.parameterTypes[0] == activityThreadClass
        } ?: throw IllegalStateException("LoadedApk constructor not found")
        constructor.isAccessible = true
        val args = constructor.parameterTypes.mapIndexed { index, type ->
            when {
                index == 0 -> activityThread
                type == ApplicationInfo::class.java -> appInfo
                type == compatibilityClass -> defaultCompatibility
                type == ClassLoader::class.java -> null
                type == Boolean::class.javaPrimitiveType -> index == 5
                else -> null
            }
        }.toTypedArray()
        return constructor.newInstance(*args)
    }

    private fun buildPackageInfo(): PackageInfo {
        val info = PackageInfo()
        info.packageName = record.sourcePackage
        info.versionName = record.versionName
        applyVersionCode(info)
        info.applicationInfo = appInfo
        info.firstInstallTime = record.firstInstallTime.takeIf { it > 0 } ?: record.importedAt
        info.lastUpdateTime = record.lastUpdateTime.takeIf { it > 0 } ?: record.updatedAt
        @Suppress("DEPRECATION")
        info.signatures = signatures()
        @Suppress("DEPRECATION")
        info.signingInfo = buildSigningInfo()
        info.requestedPermissions = record.requestedPermissions.toTypedArray()
        info.requestedPermissionsFlags = record.requestedPermissionsFlags.toIntArray()
        info.activities = activityInfos.values.toTypedArray()
        return info
    }

    /**
     * versionCode 是 int，低 32 位；长版本号的高位在 versionCodeMajor。
     * 直接 toInt() 截断会让 getLongVersionCode() 与真机不一致。
     */
    private fun applyVersionCode(info: PackageInfo) {
        val long = record.versionCode
        @Suppress("DEPRECATION")
        info.versionCode = long.toInt()
        runCatching { info.setLongVersionCode(long) }
    }

    private fun buildSigningInfo(): SigningInfo? {
        return try {
            val signatures = signatures()
            if (signatures.isEmpty()) return null
            val history = record.signingHistory.mapNotNull { encoded ->
                runCatching { Signature(Base64.decode(encoded, Base64.NO_WRAP)) }.getOrNull()
            }
            val ctor = SigningInfo::class.java.getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                java.util.Collection::class.java,
                java.util.Collection::class.java,
                java.util.Collection::class.java
            )
            ctor.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            ctor.newInstance(
                if (record.signingSchemeVersion > 0) record.signingSchemeVersion else 2,
                signatures.toList(),
                null,
                history.toList()
            ) as SigningInfo
        } catch (t: Throwable) {
            XposedLog.log("Unable to build SigningInfo: $t")
            null
        }
    }

    fun signatures(): Array<Signature> {
        return record.signatures.mapNotNull { encoded ->
            try {
                Signature(Base64.decode(encoded, Base64.NO_WRAP))
            } catch (_: Throwable) {
                null
            }
        }.toTypedArray()
    }

    /**
     * 按真机 PMS 的 flags 语义构造 PackageInfo。
     *
     * 真机上 getPackageInfo(pkg, 0) 不会返回签名，
     * 只有显式请求 GET_SIGNATURES / GET_SIGNING_CERTIFICATES 时才返回对应字段。
     * 容器里如果不看 flags 一律塞满签名，等于主动告诉应用"我不是系统 PMS"。
     */
    fun packageInfoFor(flags: Int): PackageInfo {
        val info = PackageInfo()
        info.packageName = record.sourcePackage
        info.versionName = record.versionName
        applyVersionCode(info)
        // 对 Guest 暴露的是"真机视图"：安装路径指向真机安装位置。
        // metaData 挂在 ApplicationInfo 上，按 flags 决定是否携带。
        info.applicationInfo = publicAppInfoFor(flags)
        info.firstInstallTime = record.firstInstallTime.takeIf { it > 0 } ?: record.importedAt
        info.lastUpdateTime = record.lastUpdateTime.takeIf { it > 0 } ?: record.updatedAt
        if (flags and PackageManager.GET_SIGNATURES != 0) {
            @Suppress("DEPRECATION")
            info.signatures = signatures()
        }
        if (flags and PackageManager.GET_SIGNING_CERTIFICATES != 0) {
            @Suppress("DEPRECATION")
            info.signingInfo = buildSigningInfo()
        }
        if (flags and PackageManager.GET_ACTIVITIES != 0) {
            // 必须遍历 record.activities 全量构造，不能依赖 activityInfos 缓存：
            // 该缓存是 activityInfoFor() 被调用时才懒加载的，
            // 直接用缓存会让"先查组件列表、后启动 Activity"的顺序拿到空列表。
            info.activities = record.activities.keys.mapNotNull { name ->
                runCatching { queryActivityInfo(name) }.getOrNull()
            }.toTypedArray()
        }
        if (flags and PackageManager.GET_RECEIVERS != 0) {
            info.receivers = record.receivers.mapNotNull { receiver ->
                receiverInfoFor(receiver.name)
            }.toTypedArray()
        }
        if (flags and PackageManager.GET_SERVICES != 0) {
            info.services = record.services.mapNotNull { service ->
                serviceInfoFor(service.name)
            }.toTypedArray()
        }
        if (flags and PackageManager.GET_PROVIDERS != 0) {
            info.providers = record.providers.mapNotNull { provider ->
                providerInfoFor(provider.authority)
            }.toTypedArray()
        }
        if (flags and PackageManager.GET_PERMISSIONS != 0) {
            info.requestedPermissions = record.requestedPermissions.toTypedArray()
            info.requestedPermissionsFlags = record.requestedPermissionsFlags.toIntArray()
        }
        return info
    }

    fun activityInfoFor(className: String?, resolveAlias: Boolean = true): ActivityInfo {
        val resolved = if (resolveAlias) resolveActivityClass(className) else className
        val name = resolved ?: record.launcherActivity
            ?: throw IllegalArgumentException("No activity class")
        return synchronized(activityInfos) {
            activityInfos.getOrPut(name) {
                buildActivityInfo(name, record.activities[name])
            }
        }
    }

    /**
     * 严格按组件名查询 ActivityInfo，找不到返回 null。
     *
     * 真机语义：getActivityInfo 查不存在的组件必须抛 NameNotFoundException。
     * 框架内部路径（activityInfoFor）为了容错会对未知类名兜底，
     * 但 PM 查询路径不能兜底 —— 否则应用查任意一个不存在的组件都能拿到
     * 一个"合法"的 ActivityInfo，与真机行为相反。
     */
    fun queryActivityInfo(className: String): ActivityInfo? {
        val stored = record.activities[className] ?: return null
        return synchronized(activityInfos) {
            activityInfos.getOrPut(className) { buildActivityInfo(className, stored) }
        }
    }

    private fun buildActivityInfo(name: String, stored: StoredActivityInfo?): ActivityInfo {
        val info = ActivityInfo()
        info.name = name
        info.packageName = record.sourcePackage
        info.applicationInfo = publicAppInfo()
        info.enabled = true
        info.exported = stored?.exported ?: true
        info.processName = stored?.processName?.takeIf { it.isNotBlank() }
            ?: record.sourcePackage
        info.taskAffinity = stored?.taskAffinity?.takeIf { it.isNotBlank() }
            ?: record.sourcePackage
        info.theme = stored?.theme?.takeIf { it != 0 }
            ?: record.applicationTheme.takeIf { it != 0 }
            ?: android.R.style.Theme_DeviceDefault_Light_NoActionBar
        info.launchMode = stored?.launchMode ?: ActivityInfo.LAUNCH_MULTIPLE
        info.configChanges = stored?.configChanges ?: 0
        info.screenOrientation = stored?.screenOrientation ?: -1
        info.softInputMode = stored?.softInputMode ?: 0
        info.flags = stored?.flags ?: 0
        info.parentActivityName = stored?.parentActivityName
        info.targetActivity = stored?.targetActivity
        return info
    }

    fun isStubIntent(intent: Intent?): Boolean {
        val component = intent?.component ?: return false
        return component.packageName == hostPackage &&
            component.className.startsWith(STUB_PACKAGE_PREFIX) &&
            intent.hasExtra(EXTRA_GUEST_ID)
    }

    fun guestIntentFrom(
        stubIntent: Intent,
        guestClass: String,
        guestComponent: String? = null
    ): Intent {
        @Suppress("DEPRECATION")
        val original = stubIntent.getParcelableExtra<Intent>(EXTRA_GUEST_INTENT)
        val intent = original?.let { Intent(it) } ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        intent.component = ComponentName(record.sourcePackage, guestComponent ?: guestClass)
        intent.setPackage(record.sourcePackage)
        intent.removeExtra(EXTRA_GUEST_ID)
        intent.removeExtra(EXTRA_GUEST_CLASS)
        intent.removeExtra(EXTRA_GUEST_COMPONENT)
        intent.removeExtra(EXTRA_GUEST_INTENT)
        intent.removeExtra(EXTRA_GUEST_PROCESS)
        return intent
    }

    fun activityProcessName(className: String): String? {
        val resolved = resolveActivityClass(className) ?: return null
        return record.activities[resolved]?.processName?.takeIf { it.isNotBlank() }
    }

    fun resolveActivityClass(className: String?): String? {
        if (className == null) return null
        return record.activities[className]?.targetActivity?.takeIf { it.isNotBlank() }
            ?: className
    }

    fun isMainGuestProcess(processName: String?): Boolean =
        processName == null || processName == record.sourcePackage ||
            processName == "${record.sourcePackage}:main"

    fun targetSlotForActivity(className: String): Int {
        val targetProcess = activityProcessName(className) ?: record.sourcePackage
        val currentIsMain = isMainGuestProcess(guestProcessName)
        return when {
            isMainGuestProcess(targetProcess) -> if (currentIsMain) slot else 0
            !currentIsMain && targetProcess == guestProcessName -> slot
            currentIsMain -> if (slot == 0) 1 else 0
            // Only one extra process slot exists today; keep additional subprocesses local.
            else -> slot
        }
    }

    fun stubIntentFor(
        original: Intent,
        guestClass: String,
        fromActivity: Boolean,
        targetSlot: Int = slot
    ): Intent {
        // Use the standard stub for every launch. Distinct guest classes must never share an
        // onNewIntent target; task/launch-mode emulation is layered on top later.
        val stubClass = "$STUB_PACKAGE.StubStandard$targetSlot"
        val resolvedClass = resolveActivityClass(guestClass) ?: guestClass
        val targetProcess = activityProcessName(resolvedClass) ?: record.sourcePackage
        val stub = Intent(original).setClassName(hostPackage, stubClass)
        stub.putExtra(EXTRA_GUEST_ID, record.id)
        stub.putExtra(EXTRA_GUEST_CLASS, resolvedClass)
        if (resolvedClass != guestClass) {
            stub.putExtra(EXTRA_GUEST_COMPONENT, guestClass)
        }
        stub.putExtra(EXTRA_GUEST_INTENT, original)
        stub.putExtra(EXTRA_GUEST_PROCESS, targetProcess)
        if (!fromActivity) {
            stub.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return stub
    }

    fun isGuestTarget(intent: Intent): Boolean {
        val component = intent.component
        if (component != null) {
            if (component.packageName == record.sourcePackage) return true
            if (record.activities.containsKey(component.className)) return true
            if (component.className.startsWith(record.sourcePackage + ".")) return true
        }
        return intent.`package` == record.sourcePackage
    }

    @Synchronized
    fun installProvidersOnce(app: Application) {
        if (providersInstalled) return
        providersInstalled = true
        // Real Android installs providers with the Application as context; some SDK
        // providers (抖音 Aha/EC) cast getContext() to Application. baseContext is a
        // ContextImpl and caused ClassCastException + broken initialization.
        val context: Context = app
        val providers = record.providers
            .filter { it.className.isNotBlank() }
            .filter { it.processName == null || it.processName == record.sourcePackage || it.processName == "${record.sourcePackage}:main" }
            .sortedBy { it.initOrder }
        if (providers.isEmpty()) {
            XposedLog.log("Guest has no main-process providers")
            return
        }
        for (stored in providers) {
            try {
                val cls = Class.forName(stored.className, true, classLoader)
                val provider = cls.getDeclaredConstructor().newInstance() as ContentProvider
                val info = ProviderInfo().apply {
                    authority = stored.authority
                    name = stored.className
                    packageName = record.sourcePackage
                    applicationInfo = publicAppInfo()
                    exported = stored.exported
                    readPermission = stored.readPermission
                    writePermission = stored.writePermission
                    grantUriPermissions = stored.grantUriPermissions
                    processName = record.sourcePackage
                    initOrder = stored.initOrder
                    multiprocess = stored.multiprocess
                }
                provider.attachInfo(context, info)
                val transportMethod = ContentProvider::class.java.getDeclaredMethod("getIContentProvider")
                transportMethod.isAccessible = true
                val transport = transportMethod.invoke(provider)
                if (transport != null) {
                    providerTransports[stored.authority] = transport
                    guestProviders[stored.authority] = provider
                    XposedLog.log("Guest provider ready: ${stored.authority} -> ${stored.className}")
                } else {
                    XposedLog.log("Guest provider has no transport: ${stored.authority}")
                }
            } catch (t: Throwable) {
                XposedLog.log("Guest provider failed: ${stored.authority} ${stored.className}: $t")
                XposedLog.log(t)
            }
        }
    }

    /**
     * 部分 SDK（实测抖音 TokenObjectProvider）会在自己的 query 里再次经 ContentResolver
     * 查询同一 authority；本地 transport 直接回调同一 provider 时形成 lancet/twin 无限递归。
     * 同一线程对同一 provider 的重入 query 返回 null，打断递归，外层查询继续。
     */
    fun markReceiverRegistered(receiver: BroadcastReceiver, filter: android.content.IntentFilter?) {
        registeredReceivers.add(receiver)
        filter?.actionsIterator()?.forEach { action -> registeredActions.add(action) }
    }

    fun hasGuestAction(action: String?): Boolean {
        return action != null && registeredActions.contains(action)
    }

    fun isGuestReceiver(receiver: BroadcastReceiver?): Boolean {
        return receiver != null && registeredReceivers.contains(receiver)
    }

    fun unmarkReceiver(receiver: BroadcastReceiver?) {
        if (receiver != null) registeredReceivers.remove(receiver)
    }

    private class GuestServiceInstance(
        val service: android.app.Service,
        val className: String
    ) {
        var binder: IBinder? = null
        var startId: Int = 0
    }

    fun resolveServiceClass(intent: Intent?): String? {
        if (intent == null) return null
        val component = intent.component ?: return null
        val className = component.className ?: return null
        if (component.packageName == record.sourcePackage) return className
        if (record.services.any { it.name == className }) return className
        return null
    }

    fun serviceProcessName(className: String): String? =
        record.services.firstOrNull { it.name == className }?.processName?.takeIf { it.isNotBlank() }

    fun targetSlotForService(className: String): Int {
        val targetProcess = serviceProcessName(className) ?: record.sourcePackage
        val currentIsMain = isMainGuestProcess(guestProcessName)
        return when {
            isMainGuestProcess(targetProcess) -> if (currentIsMain) slot else 0
            !currentIsMain && targetProcess == guestProcessName -> slot
            currentIsMain -> if (slot == 0) 1 else 0
            else -> slot
        }
    }

    fun stubServiceIntentFor(original: Intent, guestClass: String, targetSlot: Int): Intent {
        val targetProcess = serviceProcessName(guestClass) ?: record.sourcePackage
        return Intent().setClassName(hostPackage, "$STUB_PACKAGE.StubGuestService$targetSlot")
            .apply {
                putExtra(EXTRA_GUEST_ID, record.id)
                putExtra(EXTRA_GUEST_PROCESS, targetProcess)
                putExtra(EXTRA_GUEST_SERVICE_CLASS, guestClass)
                putExtra(EXTRA_GUEST_INTENT, original)
            }
    }

    private fun getOrCreateService(className: String): GuestServiceInstance? {
        guestServices[className]?.let { return it }
        return try {
            val app = guestApplication ?: host
            val cls = Class.forName(className, true, classLoader)
            val service = cls.getDeclaredConstructor().newInstance() as android.app.Service
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val attach = android.app.Service::class.java.getDeclaredMethod(
                "attach",
                Context::class.java,
                activityThreadClass,
                String::class.java,
                IBinder::class.java,
                Application::class.java,
                Any::class.java
            )
            attach.isAccessible = true
            attach.invoke(service, app, activityThread, className, null, app, null)
            service.onCreate()
            GuestServiceInstance(service, className).also {
                guestServices[className] = it
                XposedLog.log("Guest service created: $className")
                // 诊断：服务声明的进程与实际创建它的进程往往不一致。
                // 声明了独立进程的 AIDL 服务如果在这里被就地创建，客户端跨进程拿到
                // 的 binder 描述符会与期望不符（Binder invocation to an incorrect interface）。
                val declared = serviceProcessName(className)
                XposedLog.log(
                    "Guest service 进程归属: class=$className declared=$declared " +
                        "actual=${Application.getProcessName()} guestProcess=$guestProcessName"
                )
            }
        } catch (t: Throwable) {
            XposedLog.log("Guest service failed: $className $t")
            XposedLog.log(t)
            null
        }
    }

    fun startGuestService(intent: Intent?): ComponentName? {
        val className = resolveServiceClass(intent) ?: return null
        val instance = getOrCreateService(className) ?: return null
        return try {
            instance.startId += 1
            instance.service.onStartCommand(intent, 0, instance.startId)
            ComponentName(record.sourcePackage, className)
        } catch (t: Throwable) {
            XposedLog.log("Guest service onStartCommand failed: $className $t")
            XposedLog.log(t)
            null
        }
    }

    fun bindGuestService(intent: Intent?, connection: ServiceConnection?): Boolean {
        if (connection == null) return false
        val className = resolveServiceClass(intent) ?: return false
        val instance = getOrCreateService(className) ?: return false
        return try {
            val binder = instance.service.onBind(intent)
            val component = ComponentName(record.sourcePackage, className)
            instance.binder = binder
            serviceBindings[connection] = component
            if (binder != null) {
                // 诊断：binder 的接口描述符决定了客户端 asInterface 能否正确转型。
                // 声明了独立进程的服务被就地创建时，描述符会与期望不符。
                val descriptor = runCatching {
                    binder.javaClass.getMethod("getInterfaceDescriptor")
                        .invoke(binder) as? String
                }.getOrNull()
                XposedLog.log(
                    "Guest service onBind: class=$className binder=${binder.javaClass.name} " +
                        "descriptor=$descriptor declaredProc=${serviceProcessName(className)} " +
                        "actualProc=${Application.getProcessName()}"
                )
                connection.onServiceConnected(component, binder)
            }
            true
        } catch (t: Throwable) {
            XposedLog.log("Guest service onBind failed: $className $t")
            XposedLog.log(t)
            false
        }
    }

    /**
     * 解除绑定。
     *
     * 真机语义：unbindService 只会触发 Service.onUnbind（若返回 true 则随后 onRebind），
     * onServiceDisconnected 只在服务进程异常崩溃时回调。
     * 之前这里误调 onServiceDisconnected，很多 App 依赖"解绑即断连"的假象做资源清理，
     * 结果提前触发断连逻辑；同时真正的 onUnbind 没被调用，App 的引用计数永远不归零。
     */
    fun unbindGuestService(connection: ServiceConnection?) {
        if (connection == null) return
        val component = serviceBindings.remove(connection) ?: return
        val className = component.className
        val instance = guestServices[className]
        // Service.onUnbind / onRebind 是 protected 方法，只能反射调用。
        try {
            val onUnbind = android.app.Service::class.java.getDeclaredMethod(
                "onUnbind", Intent::class.java
            ).apply { isAccessible = true }
            val unbound = onUnbind.invoke(instance?.service, null) as? Boolean ?: false
            if (unbound) {
                val onRebind = android.app.Service::class.java.getDeclaredMethod(
                    "onRebind", Intent::class.java
                ).apply { isAccessible = true }
                onRebind.invoke(instance?.service, null)
            }
        } catch (t: Throwable) {
            XposedLog.log("Guest service onUnbind failed: $className $t")
        }
    }

    fun stopGuestService(intent: Intent?): Boolean {
        val className = resolveServiceClass(intent) ?: return false
        val instance = guestServices.remove(className) ?: return true
        try {
            instance.service.onDestroy()
        } catch (t: Throwable) {
            XposedLog.log(t)
        } finally {
            cancelForegroundNotification(className)
            notifyServiceStopped(className)
        }
        return true
    }

    fun startGuestServiceByClass(
        className: String,
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val instance = getOrCreateService(className) ?: return android.app.Service.START_NOT_STICKY
        return try {
            instance.startId = startId
            instance.service.onStartCommand(intent, flags, startId)
        } catch (t: Throwable) {
            XposedLog.log("Guest service onStartCommand failed: $className $t")
            XposedLog.log(t)
            android.app.Service.START_NOT_STICKY
        }
    }

    fun bindGuestServiceByClass(className: String, intent: Intent?): IBinder? {
        val instance = getOrCreateService(className) ?: return null
        return try {
            instance.service.onBind(intent).also { instance.binder = it }
        } catch (t: Throwable) {
            XposedLog.log("Guest service onBind failed: $className $t")
            XposedLog.log(t)
            null
        }
    }

    fun stopGuestServiceByClass(className: String) {
        val instance = guestServices.remove(className) ?: return
        try {
            instance.service.onDestroy()
        } catch (t: Throwable) {
            XposedLog.log(t)
        } finally {
            cancelForegroundNotification(className)
            notifyServiceStopped(className)
        }
    }

    fun setServiceStopCallback(className: String, callback: () -> Unit) {
        serviceStopCallbacks[className] = callback
    }

    private fun notifyServiceStopped(className: String) {
        serviceStopCallbacks.remove(className)?.invoke()
    }

    fun isGuestService(service: android.app.Service?): Boolean =
        guestServiceName(service) != null

    fun guestServiceName(service: android.app.Service?): String? {
        if (service == null) return null
        return guestServices.entries.firstOrNull { it.value.service === service }?.key
    }

    fun virtualizeStartForeground(
        service: android.app.Service?,
        id: Int,
        notification: Notification?
    ): Boolean {
        val className = guestServiceName(service) ?: return false
        val target = notification ?: return false
        try {
            val iconRes = host.applicationInfo.icon.takeIf { it != 0 }
                ?: android.R.drawable.sym_def_app_icon
            setField(target, "mSmallIcon", Icon.createWithResource(host, iconRes))
        } catch (t: Throwable) {
            XposedLog.log("foreground icon rewrite failed: $t")
        }
        val manager = host.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var channel = target.channelId
            if (channel.isNullOrBlank()) {
                channel = "vt_guest_default"
                setField(target, "mChannelId", channel)
            }
            if (manager.getNotificationChannel(channel) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channel,
                        "VT Guest",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        return try {
            manager.notify(foregroundTag(className), id, target)
            foregroundNotificationIds[className] = id
            XposedLog.log("Guest startForeground virtualized: $className id=$id")
            true
        } catch (t: Throwable) {
            XposedLog.log("Guest startForeground failed: $className $t")
            XposedLog.log(t)
            false
        }
    }

    fun virtualizeStopForeground(service: android.app.Service?, remove: Boolean) {
        val className = guestServiceName(service) ?: return
        if (!remove) {
            XposedLog.log("Guest stopForeground keeps notification: $className")
            return
        }
        val id = foregroundNotificationIds.remove(className) ?: return
        cancelForeground(className, id)
        XposedLog.log("Guest stopForeground removed: $className")
    }

    fun cancelForegroundNotification(className: String) {
        val id = foregroundNotificationIds.remove(className) ?: return
        cancelForeground(className, id)
    }

    fun cancelAllForegroundNotifications() {
        foregroundNotificationIds.keys.toList().forEach { cancelForegroundNotification(it) }
    }

    fun requestStopGuestService(service: android.app.Service?) {
        val className = guestServiceName(service) ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            stopGuestServiceByName(className)
        }
    }

    private fun stopGuestServiceByName(className: String) {
        val instance = guestServices.remove(className) ?: return
        try {
            instance.service.onDestroy()
        } catch (t: Throwable) {
            XposedLog.log(t)
        } finally {
            cancelForegroundNotification(className)
            notifyServiceStopped(className)
        }
    }

    private fun foregroundTag(className: String): String =
        "vt:${record.sourcePackage}:$className"

    private fun cancelForeground(className: String, id: Int) {
        try {
            host.getSystemService(NotificationManager::class.java)
                .cancel(foregroundTag(className), id)
        } catch (t: Throwable) {
            XposedLog.log("foreground cancel failed: $t")
        }
    }

    @Synchronized
    fun installStaticReceiversOnce(app: Application) {
        if (staticReceiversInstalled) return
        staticReceiversInstalled = true
        var count = 0
        for (stored in record.receivers) {
            if (stored.processName != null && stored.processName != record.sourcePackage &&
                stored.processName != "${record.sourcePackage}:main"
            ) {
                continue
            }
            if (stored.actions.isEmpty()) continue
            try {
                val cls = Class.forName(stored.name, true, classLoader)
                val receiver = cls.getDeclaredConstructor().newInstance() as BroadcastReceiver
                for (action in stored.actions) {
                    val filter = android.content.IntentFilter(action)
                    stored.categories.forEach { filter.addCategory(it) }
                    Hooker.beginBypass()
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            val flag = if (stored.exported || action.startsWith("android.")) {
                                Context.RECEIVER_EXPORTED
                            } else {
                                Context.RECEIVER_NOT_EXPORTED
                            }
                            currentRegister(receiver, filter, flag)
                        } else {
                            @Suppress("DEPRECATION")
                            host.registerReceiver(receiver, filter)
                        }
                        markReceiverRegistered(receiver, filter)
                        count++
                    } finally {
                        Hooker.endBypass()
                    }
                }
                XposedLog.log("Static receiver installed: ${stored.name} ${stored.actions}")
            } catch (t: Throwable) {
                XposedLog.log("Static receiver failed: ${stored.name}: $t")
            }
        }
        XposedLog.log("Static receivers installed: $count")
    }

    private fun currentRegister(receiver: BroadcastReceiver, filter: android.content.IntentFilter, flag: Int) {
        host.registerReceiver(receiver, filter, flag)
    }

    fun transportForAuthority(authority: String?): Any? {
        if (authority.isNullOrBlank()) return null
        val plain = authority.substringAfterLast('/').substringBefore('@')
        return providerTransports[plain] ?: providerTransports[authority]
    }

    fun hasGuestProvider(authority: String?): Boolean = transportForAuthority(authority) != null

    @Synchronized
    fun ensureApplication() {
        if (guestApplication != null) return
        installBytedanceProcessShims()
        runCatching {
            android.os.Process::class.java
                .getDeclaredMethod("setArgV0", String::class.java)
                .apply { isAccessible = true }
                .invoke(null, guestProcessName)
            XposedLog.log("Guest process name set to $guestProcessName")
            // ps/proc readers also look at comm; keep it consistent with the guest
            // process instead of io.vt.framework:gN.
            runCatching {
                File("/proc/self/comm")
                    .writeText(guestProcessName.take(15))
            }
        }.onFailure { XposedLog.log("setArgV0 failed: $it") }
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
                ?: return
            val instrumentation = activityThreadClass.getMethod("getInstrumentation")
                .invoke(activityThread) as? android.app.Instrumentation ?: return
            val loadedApkClass = Class.forName("android.app.LoadedApk")
            val app = try {
                val method = loadedApkClass.getDeclaredMethod(
                    "makeApplication",
                    Boolean::class.javaPrimitiveType,
                    android.app.Instrumentation::class.java
                )
                method.isAccessible = true
                method.invoke(loadedApk, false, instrumentation)
            } catch (_: NoSuchMethodException) {
                val method = loadedApkClass.getDeclaredMethod(
                    "makeApplicationInner",
                    Boolean::class.javaPrimitiveType,
                    android.app.Instrumentation::class.java,
                    Boolean::class.javaPrimitiveType
                )
                method.isAccessible = true
                method.invoke(loadedApk, false, instrumentation, false)
            } as? Application
            if (app != null) {
                guestApplication = app
                XposedLog.log(
                    "Guest Application ensured before Activity: ${app.javaClass.name}, " +
                        "process=$guestProcessName, appProcess=" + Application.getProcessName() +
                        ", osProcess=" + android.os.Process.myPid()
                )
                runCatching {
                    if (record.sourcePackage != "com.phoenix.read") return@runCatching
                    io.vt.xposed.VtNative.ensureLoaded()
                    val logDir = File(host.filesDir, "logs").apply { mkdirs() }
                    val crashLog = File(logDir, "native-crash.log")
                    val installed = io.vt.xposed.VtNative.installCrashHandler(
                        crashLog.absolutePath
                    )
                    XposedLog.log("native crash handler installed=$installed")
                    val debuggable = (host.applicationInfo.flags and
                        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    if (debuggable) {
                        val traced = io.vt.xposed.VtNative.installNativeTrace(
                            File(logDir, "native-trace.log").absolutePath
                        )
                        XposedLog.log("native trace installed=$traced")
                    }
                    val realApk = record.installedSourceDir?.takeIf { it.isNotBlank() }
                        ?: record.baseApk().orEmpty()
                    val shim = io.vt.xposed.VtNative.installEnvShim(
                        host.packageName, record.sourcePackage, realApk
                    )
                    XposedLog.log("env shim installed=$shim apk=$realApk")
                }.onFailure { XposedLog.log("native crash handler failed: $it") }
                if (!rpcCompatInitialized) {
                    rpcCompatInitialized = true
                    initializeBytedanceRpc(app)
                }
                if (!accountCompatInitialized) {
                    accountCompatInitialized = true
                    initializeBytedanceAccount(app)
                    initializeBytedanceHttp(app)
                    initializeBytedancePlayer()
                }
                io.vt.container.GuestBootstrap.installCrashProbe(host)
            }
        } catch (t: Throwable) {
            XposedLog.log("ensureApplication failed")
            XposedLog.log(t)
        }
    }


    private fun initializeBytedanceRpc(app: Application) {
        if (record.sourcePackage != "com.dragon.read" &&
            record.sourcePackage != "com.phoenix.read"
        ) {
            return
        }
        runCatching {
            val cls = Class.forName("com.dragon.read.app.launch.task.g4", true, classLoader)
            val field = cls.getDeclaredField("a").apply { isAccessible = true }
            if (field.getBoolean(null)) {
                XposedLog.log("Bytedance RPC already initialized")
                return
            }
            val method = cls.getDeclaredMethod("c", Application::class.java)
            method.isAccessible = true
            method.invoke(null, app)
            XposedLog.log("Bytedance RPC init invoked for ${record.sourcePackage}")
        }.onFailure {
            XposedLog.log("Bytedance RPC init failed: $it")
            XposedLog.log(it)
        }
    }

    private fun installBytedanceProcessShims() {
        if (record.sourcePackage != "com.dragon.read" &&
            record.sourcePackage != "com.phoenix.read"
        ) {
            return
        }
        runCatching {
            val cls = Class.forName("com.ss.android.common.util.ToolUtils", true, classLoader)
            val count = XposedBridge.hookAllMethods(
                cls, "isMainProcess",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        param.result = isMainGuestProcess(guestProcessName)
                    }
                }
            ).size
            XposedLog.log("Bytedance isMainProcess shims=$count")
        }.onFailure { XposedLog.log("Bytedance process shim failed: $it") }
    }

    private fun initializeBytedanceAccount(app: Application) {
        if (record.sourcePackage != "com.dragon.read" &&
            record.sourcePackage != "com.phoenix.read"
        ) {
            return
        }
        runCatching {
            val cls = Class.forName("com.dragon.read.app.launch.task.p5", true, classLoader)
            val instance = cls.getDeclaredConstructor().newInstance()
            val method = cls.getMethod("b", Application::class.java)
            method.invoke(instance, app)
            XposedLog.log("Bytedance account init invoked for ${record.sourcePackage}")
        }.onFailure {
            XposedLog.log("Bytedance account init failed: $it")
            XposedLog.log(it)
        }
    }

    private fun initializeBytedanceHttp(app: Application) {
        if (record.sourcePackage != "com.dragon.read" &&
            record.sourcePackage != "com.phoenix.read"
        ) {
            return
        }
        runCatching {
            val httpClass = Class.forName("com.dragon.read.base.http.k", true, classLoader)
            val initialized = httpClass.getDeclaredField("b").apply { isAccessible = true }
                .getBoolean(null)
            if (initialized) {
                XposedLog.log("Bytedance HTTP already initialized")
                return
            }
            val taskClass = Class.forName("com.dragon.read.app.launch.task.h1", true, classLoader)
            val instance = taskClass.getDeclaredConstructor().newInstance()
            val method = taskClass.getMethod("e", Application::class.java)
            // 必须在**主线程**执行：字节的 HTTP 初始化里会调用
            // LiveData.observeForever，而 observeForeover 明确要求主线程，
            // 在后台线程调用会抛
            //   IllegalStateException: Cannot invoke observeForever on a background thread
            // 这个异常发生在类静态初始化里，会让 com.dragon.read.base.http.e.<clinit>
            // 抛 ExceptionInInitializerError，进而让应用退到 SafeModeActivity
            // （页面停在安全模式，主界面起不来）。
            //
            // 所以这里用主线程 Handler 投递，而不是起后台线程。
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                runCatching {
                    method.invoke(instance, app)
                    XposedLog.log("Bytedance HTTP init done for ${record.sourcePackage}")
                }.onFailure {
                    XposedLog.log("Bytedance HTTP init failed: $it")
                    XposedLog.log(it)
                }
            }
        }.onFailure {
            XposedLog.log("Bytedance HTTP init setup failed: $it")
            XposedLog.log(it)
        }
    }

    private fun initializeBytedancePlayer() {
        if (record.sourcePackage != "com.dragon.read" &&
            record.sourcePackage != "com.phoenix.read"
        ) {
            return
        }
        runCatching {
            val loader = Class.forName("com.ss.ttm.player.TTPlayerLibLoader", true, classLoader)
            Thread({
                runCatching {
                    val loaded = loader.getMethod("loadLibrary").invoke(null) as? Boolean ?: false
                    XposedLog.log("TTPlayer libs preloaded=$loaded")
                }.onFailure { XposedLog.log("TTPlayer lib preload failed: $it") }
            }, "vt-player-init").start()
        }.onFailure { XposedLog.log("TTPlayer preload setup failed: $it") }
    }

    private fun installedModuleApk(packageName: String): String? {
        return runCatching {
            host.packageManager.getApplicationInfo(packageName, 0).sourceDir
        }.getOrNull()?.takeIf { File(it).isFile }
    }

    fun loadModulesOnce(app: Application?) {
        if (modulesLoaded) return
        modulesLoaded = true
        try {
            val moduleStore = ModuleStore.get(host)
            val specs = record.enabledModules.mapNotNull { modulePackage ->
                val module = moduleStore.find(modulePackage)
                val installedPath = module?.apkPath?.takeIf { File(it).isFile }
                    ?: installedModuleApk(modulePackage)
                if (installedPath == null) {
                    XposedLog.log("模块不可用: $modulePackage（未安装或 APK 不存在）")
                    null
                } else {
                    ModuleSpec(modulePackage, installedPath, module?.label ?: modulePackage)
                }
            }
            if (specs.isEmpty()) {
                XposedLog.log("${record.label} 未启用任何 Xposed 模块")
                return
            }
            val report = XposedRuntime.loadModules(
                specs,
                app?.classLoader ?: classLoader,
                appInfo,
                resources,
                record.sourcePackage
            )
            report.forEach { XposedLog.log("[模块] $it") }
        } catch (t: Throwable) {
            XposedLog.log("模块加载失败")
            XposedLog.log(t)
        }
    }

    fun sourcePackage(): String = record.sourcePackage

    private fun setField(target: Any, name: String, value: Any?) {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field: Field = clazz.getDeclaredField(name)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException("$name on ${target.javaClass.name}")
    }
}
