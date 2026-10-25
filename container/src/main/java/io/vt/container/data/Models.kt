package io.vt.container.data

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
    var importOrigin: String = "installed"
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

data class UpdateStatus(
    val updateAvailable: Boolean,
    val installedVersionName: String?,
    val sourceVersionName: String?,
    val message: String
)
