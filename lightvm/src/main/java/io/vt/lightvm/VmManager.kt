package io.vt.lightvm

import android.content.Context
import android.util.Log
import com.hzy.libp7zip.P7ZipApi
import java.io.File
import java.io.FileOutputStream

/**
 * 轻量 VM 管理器：rootfs 安装（7z 原生解压，保留权限/符号链接）、启动前准备、APK 安装。
 *
 * rootfs 来自 twoyi 1.0.0（Android 8.1 arm64，user/test-keys，MPL-2.0），
 * 解压目标与两仪一致：dataDir/rootfs（7z 内根目录 rootfs/）。
 * 渲染/输入由 libtwoyi（io.twoyi.Renderer）驱动，proot loader 为 libloader.so。
 */
object VmManager {
    private const val TAG = "VtLightVm"
    private const val ROOTFS_ASSET = "rootfs.7z"
    private const val ADB_SERVER_PORT = 9563
    private const val VM_ADB_PORT = 22122

    /** 与两仪保持一致：rootfs 解压到 dataDir/rootfs。 */
    fun rootfsDir(context: Context): File = File(context.dataDir, "rootfs")

    fun isInstalled(context: Context): Boolean = File(rootfsDir(context), "init").exists()

    fun loaderPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libloader.so").absolutePath

    fun installRootfs(context: Context, onState: (String) -> Unit): Boolean = runCatching {
        val archive = File(context.filesDir, ROOTFS_ASSET)
        onState("复制 rootfs（约 200MB）…")
        context.assets.open(ROOTFS_ASSET).use { input ->
            FileOutputStream(archive).use { output -> input.copyTo(output, 1 shl 16) }
        }
        onState("7z 解压 rootfs…")
        val ret = P7ZipApi.executeCommand(
            "7z x -mmt=4 -aoa '${archive.absolutePath}' '-o${context.dataDir}'"
        )
        Log.i(TAG, "7z exit=$ret")
        if (ret != 0) {
            onState("7z 解压失败 exit=$ret")
            return false
        }
        markExecutable(rootfsDir(context))
        archive.delete()
        val ok = isInstalled(context)
        onState(if (ok) "rootfs 安装完成" else "rootfs 缺少 init")
        ok
    }.getOrElse {
        Log.e(TAG, "installRootfs failed", it)
        onState("安装失败: $it")
        false
    }

    private fun markExecutable(root: File) {
        val execDirs = listOf(
            "/system/bin/", "/system/xbin/", "/vendor/bin/", "/system_ext/bin/",
            "/odm/bin/", "/apex/"
        )
        root.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val path = file.path.replace('\\', '/')
            if (file.name == "init" || execDirs.any { path.contains(it) }) {
                file.setExecutable(true, false)
            }
        }
        File(root, "init").setExecutable(true, false)
    }

    fun prepareBoot(context: Context, onState: (String) -> Unit): Boolean {
        val root = rootfsDir(context)
        if (!File(root, "init").exists()) {
            onState("请先安装 rootfs")
            return false
        }
        File(root, "dev/input").mkdirs()
        File(root, "dev/socket").mkdirs()
        File(root, "dev/maps").mkdirs()
        File(context.dataDir, "socket").mkdirs()
        onState("准备完成，可打开渲染窗口")
        return true
    }

    fun installApk(context: Context, apk: File, onState: (String) -> Unit): Boolean = runCatching {
        val adb = File(context.applicationInfo.nativeLibraryDir, "libadb.so")
        val envDir = context.cacheDir.absolutePath
        fun adbProcess(vararg args: String): Process =
            ProcessBuilder(listOf(adb.absolutePath, "-P", "$ADB_SERVER_PORT") + args)
                .redirectErrorStream(true)
                .apply {
                    environment()["TMPDIR"] = envDir
                    environment()["HOME"] = envDir
                }
                .start()

        // adb server 常驻后台
        adbProcess("nodaemon", "server")
        Thread.sleep(800)
        val connectExit = adbProcess("connect", "localhost:$VM_ADB_PORT").waitFor()
        Log.i(TAG, "adb connect exit=$connectExit")
        val installExit = adbProcess(
            "-s", "localhost:$VM_ADB_PORT", "install", "-t", "-r", apk.absolutePath
        ).waitFor()
        onState("安装 exit=$installExit")
        installExit == 0
    }.getOrElse {
        Log.e(TAG, "installApk failed", it)
        onState("安装失败: $it")
        false
    }
}
