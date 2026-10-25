package io.vt.container.data

import android.content.Context
import android.content.pm.PackageManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import io.vt.xposed.XposedLog
import java.io.File
import java.util.zip.ZipFile

class ModuleStore private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: ModuleStore? = null

        fun get(context: Context): ModuleStore =
            instance ?: synchronized(this) {
                instance ?: ModuleStore(context.applicationContext).also { instance = it }
            }

        fun hasXposedEntry(apkPath: String?): Boolean {
            if (apkPath.isNullOrBlank()) return false
            return try {
                ZipFile(apkPath).use { zip ->
                    zip.getEntry("assets/xposed_init") != null ||
                        zip.getEntry("META-INF/xposed/java_init.list") != null
                }
            } catch (_: Throwable) {
                false
            }
        }
    }

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "vt_modules.json")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val _modules = MutableStateFlow<List<ModuleRecord>>(emptyList())
    val modules: StateFlow<List<ModuleRecord>> = _modules

    init {
        reload()
    }

    @Synchronized
    fun reload() {
        if (!file.isFile) return
        try {
            val type = object : TypeToken<List<ModuleRecord>>() {}.type
            _modules.value = gson.fromJson<List<ModuleRecord>>(file.readText(), type)
                ?.filter { it.packageName.isNotBlank() && it.apkPath.isNotBlank() }
                ?.sortedBy { it.label }
                ?: emptyList()
        } catch (_: Throwable) {
            _modules.value = emptyList()
        }
    }

    @Synchronized
    private fun persist(list: List<ModuleRecord>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(gson.toJson(list))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        _modules.value = list.sortedBy { it.label }
    }

    suspend fun refresh(): List<ModuleRecord> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        val result = mutableListOf<ModuleRecord>()
        val installed = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        XposedLog.log("模块扫描: installed=${installed.size}")
        for (pkg in installed) {
            val ai = pkg.applicationInfo ?: continue
            if (pkg.packageName == appContext.packageName) continue
            val meta = ai.metaData ?: continue
            val declared = meta.get("xposedmodule")?.toString()?.equals("true", true) == true
            val legacyEntry = hasXposedEntry(ai.sourceDir)
            if (!declared && !legacyEntry) continue
            val description = meta.get("xposeddescription")?.toString().orEmpty()
            result.add(
                ModuleRecord(
                    packageName = pkg.packageName,
                    apkPath = ai.sourceDir,
                    label = runCatching { pm.getApplicationLabel(ai).toString() }
                        .getOrDefault(pkg.packageName),
                    description = description,
                    versionName = pkg.versionName.orEmpty(),
                    versionCode = pkg.longVersionCode,
                    updatedAt = System.currentTimeMillis(),
                    origin = "installed"
                )
            )
        }
        val preserved = _modules.value.filter { it.origin == "file" }
        val merged = (result + preserved).distinctBy { it.packageName }
        persist(merged)
        merged
    }

    fun addFileModule(record: ModuleRecord) {
        val current = _modules.value.filterNot { it.packageName == record.packageName }
        persist(current + record)
    }

    fun find(packageName: String): ModuleRecord? =
        _modules.value.firstOrNull { it.packageName == packageName }
}
