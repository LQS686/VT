package io.vt.container.data

import android.os.Build

data class StoredActivityInfo(
    var name: String = "",
    var theme: Int = 0,
    var launchMode: Int = 0,
    var configChanges: Int = 0,
    var screenOrientation: Int = -1,
    var softInputMode: Int = 0,
    var exported: Boolean = false,
    var processName: String? = null,
    var taskAffinity: String? = null,
    var flags: Int = 0,
    var parentActivityName: String? = null,
    var targetActivity: String? = null
)

data class StoredProviderInfo(
    var authority: String = "",
    var className: String = "",
    var exported: Boolean = false,
    var readPermission: String? = null,
    var writePermission: String? = null,
    var grantUriPermissions: Boolean = false,
    var processName: String? = null,
    var initOrder: Int = 0,
    var multiprocess: Boolean = false
)

data class StoredServiceInfo(
    var name: String = "",
    var processName: String? = null,
    var exported: Boolean = false,
    var permission: String? = null,
    var foregroundServiceType: Int = 0,
    var flags: Int = 0
)

data class StoredReceiverInfo(
    var name: String = "",
    var exported: Boolean = false,
    var processName: String? = null,
    var permission: String? = null,
    var actions: MutableList<String> = mutableListOf(),
    var categories: MutableList<String> = mutableListOf()
)

data class GuestRecord(
    var id: String = "",
    var sourcePackage: String = "",
    var label: String = "",
    var versionCode: Long = 0L,
    var versionName: String = "",
    var launcherActivity: String? = null,
    var apkFiles: MutableList<String> = mutableListOf(),
    var nativeLibDir: String? = null,
    var installedSourceDir: String? = null,
    var installedSplitSourceDirs: MutableList<String> = mutableListOf(),
    var installedNativeLibDir: String? = null,
    var installerPackage: String? = null,
    var firstInstallTime: Long = 0L,
    var lastUpdateTime: Long = 0L,
    var dataDir: String = "",
    var iconPath: String? = null,
    var signatures: MutableList<String> = mutableListOf(),
    var importedAt: Long = 0L,
    var updatedAt: Long = 0L,
    var enabledModules: MutableSet<String> = mutableSetOf(),
    var autoUpdate: Boolean = true,
    var appClass: String? = null,
    var applicationTheme: Int = 0,
    var applicationFlags: Int = 0,
    var targetSdk: Int = 0,
    var minSdk: Int = 0,
    var activities: MutableMap<String, StoredActivityInfo> = mutableMapOf(),
    var providers: MutableList<StoredProviderInfo> = mutableListOf(),
    var services: MutableList<StoredServiceInfo> = mutableListOf(),
    var receivers: MutableList<StoredReceiverInfo> = mutableListOf(),
    var metaData: MutableMap<String, String> = mutableMapOf(),
    var importOrigin: String = "installed",

    // 以下字段在导入时从真机 PackageManager 读取，用于让容器内的 Guest 视图
    // 与真机上该应用的视图保持一致（而不是框架自己编造的值）。
    var appProcessName: String? = null,
    var appTaskAffinity: String? = null,
    var appCategory: Int = 0,
    var primaryCpuAbi: String? = null,
    var requestedPermissions: MutableList<String> = mutableListOf(),
    var requestedPermissionsFlags: MutableList<Int> = mutableListOf(),
    var signingSchemeVersion: Int = 0,
    var multipleSigners: Boolean = false,
    var signingHistory: MutableList<String> = mutableListOf(),
    // 真机上 dataDir 与 credentialProtectedDataDir 是同一个路径（/data/user/0/<pkg>），
    // 只有 deviceProtectedDataDir 位于 /data/user_de/0/<pkg>。
    // 这里按真机语义记录，避免容器里凭空造出第三种结构。
    var deviceProtectedDataDir: String = "",
    var credentialProtectedDataDir: String = ""
) {
    fun baseApk(): String? = apkFiles.firstOrNull()
    fun splits(): List<String> = apkFiles.drop(1)
}

data class ModuleRecord(
    var packageName: String = "",
    var apkPath: String = "",
    var label: String = "",
    var description: String = "",
    var versionName: String = "",
    var versionCode: Long = 0L,
    var updatedAt: Long = 0L,
    var origin: String = "installed"
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isGame: Boolean,
    val isXposedModule: Boolean
)

sealed class LaunchOutcome {
    data object Success : LaunchOutcome()
    data class Failure(val message: String) : LaunchOutcome()
}

/**
 * 生成"真机形态"的安装路径。
 *
 * 真机上的 sourceDir 形如：
 *   /data/app/~~<base64ish>==/<pkg>-<base64ish>==/base.apk
 * 容器里 Guest 自己的 APK 副本位于宿主私有目录，一旦把这个路径暴露出去，
 * 应用一眼就能看出自己跑在容器里。
 *
 * 当目标应用在真机上不存在（APK 文件导入、或已卸载）时，
 * 用这个形态生成一个占位路径：文件并不存在，但结构与真机完全一致，
 * 比暴露容器目录安全得多。
 */
fun syntheticInstalledSourceDir(packageName: String): String {
    val suffix = base64ish(packageName, 8)
    return "/data/app/~~${base64ish(packageName, 16)}==/$packageName-$suffix==/base.apk"
}

fun syntheticInstalledSplitDir(packageName: String, splitName: String): String {
    val suffix = base64ish(packageName + splitName, 8)
    return "/data/app/~~${base64ish(packageName, 16)}==/$packageName-$suffix==/$splitName"
}

fun syntheticInstalledNativeLibDir(packageName: String): String {
    val suffix = base64ish(packageName, 8)
    return "/data/app/~~${base64ish(packageName, 16)}==/$packageName-$suffix==/lib/${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}"
}

private fun base64ish(seed: String, length: Int): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
    var hash = 1125899906842597L
    for (ch in seed) {
        hash = 31 * hash + ch.code
    }
    val sb = StringBuilder(length)
    repeat(length) {
        hash = hash * 6364136223846793005L + 1442695040888963407L
        sb.append(alphabet[((hash ushr 33) % alphabet.length).toInt()])
    }
    return sb.toString()
}

data class UpdateStatus(
    val updateAvailable: Boolean,
    val installedVersionName: String?,
    val sourceVersionName: String?,
    val message: String
)
