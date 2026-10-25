package io.vt.container.imports

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import io.vt.container.data.ModuleRecord
import io.vt.container.data.ModuleStore
import io.vt.xposed.XposedLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ModuleImporter(
    private val context: Context,
    private val store: ModuleStore
) {
    suspend fun importApk(uri: Uri): ModuleRecord? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "modules").apply { mkdirs() }
            val temp = File(dir, "module_${System.currentTimeMillis()}.apk")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            } ?: return@withContext null

            val flags = PackageManager.GET_META_DATA
            val info = context.packageManager.getPackageArchiveInfo(temp.absolutePath, flags)
                ?: run {
                    temp.delete()
                    return@withContext null
                }
            val appInfo = info.applicationInfo ?: run {
                temp.delete()
                return@withContext null
            }
            val declared = appInfo.metaData?.get("xposedmodule")?.toString()
                ?.equals("true", true) == true
            if (!declared && !ModuleStore.hasXposedEntry(temp.absolutePath)) {
                XposedLog.log("导入模块失败：APK 未声明 Xposed 模块")
                temp.delete()
                return@withContext null
            }
            val description = appInfo.metaData?.get("xposeddescription")?.toString().orEmpty()
            val record = ModuleRecord(
                packageName = info.packageName,
                apkPath = temp.absolutePath,
                label = runCatching {
                    context.packageManager.getApplicationLabel(appInfo).toString()
                }.getOrDefault(info.packageName),
                description = description,
                versionName = info.versionName.orEmpty(),
                versionCode = info.longVersionCode,
                updatedAt = System.currentTimeMillis(),
                origin = "file"
            )
            store.addFileModule(record)
            XposedLog.log("模块文件导入成功: ${record.label} (${record.packageName})")
            record
        } catch (t: Throwable) {
            XposedLog.log("导入模块失败")
            XposedLog.log(t)
            null
        }
    }
}
