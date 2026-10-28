package io.vt.sampleapp

import android.content.ComponentName
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * PackageManager 视图自洽性审计。
 *
 * 真机上，一个普通（非 sharedUid）应用观察到的 PackageManager 视图必须自洽：
 * 直接查询与列表查询返回同一个 uid/dataDir，uid 反查与包列表互相对应，
 * 自身签名一致，未安装/不可见的包查询抛 NameNotFoundException。
 *
 * 容器里只要有一处不自洽，宿主应用就能在几十行代码内判定"这不是真机环境"。
 * 这里逐项做交叉校验，既用于定位问题，也作为回归测试的断言集合。
 */
object PmAudit {
    private const val TAG = "VTSample"

    /** 容器宿主包名。Guest 视角里不应出现它。 */
    private const val HOST_PACKAGE = "io.vt.framework"

    data class Result(
        val name: String,
        val passed: Boolean,
        val detail: String
    ) {
        override fun toString(): String = "${if (passed) "PASS" else "FAIL"} $name: $detail"
    }

    fun run(pm: PackageManager, packageName: String): List<Result> {
        val results = ArrayList<Result>()

        // 1. getPackageUid 与 getApplicationInfo 的 uid 必须一致
        run {
            var detail = ""
            var passed = false
            try {
                val uid = pm.getPackageUid(packageName, 0)
                val appUid = pm.getApplicationInfo(packageName, 0).uid
                passed = uid == appUid
                detail = "getPackageUid=$uid getApplicationInfo.uid=$appUid"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("uid/self-consistency", passed, detail)
        }

        // 2. getPackagesForUid 必须包含自身包名
        run {
            var detail = ""
            var passed = false
            try {
                val uid = pm.getPackageUid(packageName, 0)
                val packages = pm.getPackagesForUid(uid) ?: emptyArray()
                passed = packages.contains(packageName)
                detail = "uid=$uid packages=${packages.toList()}"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("uid/packages-for-uid", passed, detail)
        }

        // 3. getNameForUid 必须落在 getPackagesForUid 集合内
        run {
            var detail = ""
            var passed = false
            try {
                val uid = pm.getPackageUid(packageName, 0)
                val packages = pm.getPackagesForUid(uid) ?: emptyArray()
                val name = pm.getNameForUid(uid)
                passed = packages.contains(name)
                detail = "nameForUid=$name packages=${packages.toList()}"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("uid/name-for-uid", passed, detail)
        }

        // 4. 已安装应用列表中的自身条目必须与直接查询一致（uid + dataDir）
        run {
            var detail = ""
            var passed = false
            try {
                val direct = pm.getApplicationInfo(packageName, 0)
                val listed = pm.getInstalledApplications(0)
                    .firstOrNull { it.packageName == packageName }
                if (listed == null) {
                    detail = "self not present in getInstalledApplications"
                } else {
                    passed = listed.uid == direct.uid && listed.dataDir == direct.dataDir
                    detail = "direct(uid=${direct.uid},data=${direct.dataDir}) " +
                        "listed(uid=${listed.uid},data=${listed.dataDir})"
                }
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("list/app-consistency", passed, detail)
        }

        // 5. 已安装包列表中的自身条目必须与直接查询一致
        run {
            var detail = ""
            var passed = false
            try {
                val direct = pm.getPackageInfo(packageName, 0)
                val listed = pm.getInstalledPackages(0)
                    .firstOrNull { it.packageName == packageName }
                if (listed == null) {
                    detail = "self not present in getInstalledPackages"
                } else {
                    val directUid = direct.applicationInfo?.uid
                    val listedUid = listed.applicationInfo?.uid
                    val directData = direct.applicationInfo?.dataDir
                    val listedData = listed.applicationInfo?.dataDir
                    passed = directUid == listedUid && directData == listedData
                    detail = "direct(uid=$directUid,data=$directData) " +
                        "listed(uid=$listedUid,data=$listedData)"
                }
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("list/package-consistency", passed, detail)
        }

        // 6. 宿主包对 Guest 不可见：直接查询抛 NameNotFoundException
        run {
            var detail = ""
            var passed = false
            try {
                pm.getPackageInfo(HOST_PACKAGE, 0)
                detail = "host package visible to guest"
            } catch (t: Throwable) {
                passed = t is PackageManager.NameNotFoundException
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("host/invisible", passed, detail)
        }

        // 7. 宿主包不出现在已安装列表中
        run {
            var detail = ""
            var passed = false
            try {
                val inPackages = pm.getInstalledPackages(0)
                    .any { it.packageName == HOST_PACKAGE }
                val inApps = pm.getInstalledApplications(0)
                    .any { it.packageName == HOST_PACKAGE }
                passed = !inPackages && !inApps
                detail = "inPackages=$inPackages inApplications=$inApps"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("host/not-in-list", passed, detail)
        }

        // 8. 自身签名自检必须为 SIGNATURE_MATCH
        run {
            var detail = ""
            var passed = false
            try {
                val result = pm.checkSignatures(packageName, packageName)
                passed = result == PackageManager.SIGNATURE_MATCH
                detail = "checkSignatures(self,self)=$result"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("signature/self-match", passed, detail)
        }

        // 9. 未请求签名标记时不得返回签名（真机 PMS 语义）
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getPackageInfo(packageName, 0)
                val hasDeprecated = @Suppress("DEPRECATION") info.signatures != null
                val hasSigning = info.signingInfo != null
                passed = !hasDeprecated && !hasSigning
                detail = "signatures=$hasDeprecated signingInfo=$hasSigning"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("signature/flags-respected", passed, detail)
        }

        // 10. longVersionCode 必须与 versionCode 一致
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getPackageInfo(packageName, 0)
                val long = info.longVersionCode
                passed = long == info.versionCode.toLong()
                detail = "versionCode=${info.versionCode} longVersionCode=$long"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("version/long-version-code", passed, detail)
        }

        // 11. 请求 GET_SIGNING_CERTIFICATES 时必须返回 signingInfo，
        //     否则 App 无法校验自身签名 —— 这是真实存在的功能故障。
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                @Suppress("DEPRECATION")
                val signing = info.signingInfo
                val hasSigner = signing?.apkContentsSigners?.isNotEmpty() == true
                passed = signing != null && hasSigner
                detail = "signingInfo=${signing != null} apkContentsSigners=$hasSigner"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("signature/signing-certificates", passed, detail)
        }

        // 12. GET_ACTIVITIES 必须返回组件列表
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                val count = info.activities?.size ?: 0
                passed = count > 0
                detail = "activities=$count"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("components/activities", passed, detail)
        }

        // 13. ApplicationInfo 的 dataDir / deviceProtectedDataDir / credentialProtectedDataDir
        //     必须符合真机语义：
        //     credentialProtectedDataDir == dataDir，且 deviceProtectedDataDir 与之不同。
        //     注：容器免 root，dataDir 物理路径必然位于宿主私有目录，
        //     这一项保证"结构自洽"，路径本身不可能与真机一致。
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getApplicationInfo(packageName, 0)
                val data = info.dataDir.orEmpty()
                val device = info.deviceProtectedDataDir.orEmpty()
                val credential = readHiddenString(info, "credentialProtectedDataDir")
                val allPresent = data.isNotEmpty() && device.isNotEmpty() && credential.isNotEmpty()
                passed = allPresent && credential == data && device != data
                detail = "dataDir=$data deviceProtected=$device credentialProtected=$credential"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("appinfo/data-dir-structure", passed, detail)
        }

        // 14. ApplicationInfo 的 processName / taskAffinity 必须与包名自洽
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getApplicationInfo(packageName, 0)
                val processOk = info.processName == packageName
                val affinityOk = info.taskAffinity == packageName
                passed = processOk && affinityOk
                detail = "processName=${info.processName} taskAffinity=${info.taskAffinity}"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("appinfo/identity-fields", passed, detail)
        }

        // 15. checkSignatures(int,int) 与 (String,String) 结果必须一致
        run {
            var detail = ""
            var passed = false
            try {
                val uid = pm.getPackageUid(packageName, 0)
                val byUid = pm.checkSignatures(uid, uid)
                val byName = pm.checkSignatures(packageName, packageName)
                passed = byUid == PackageManager.SIGNATURE_MATCH &&
                    byName == PackageManager.SIGNATURE_MATCH
                detail = "byUid=$byUid byName=$byName"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("signature/uid-overload-consistent", passed, detail)
        }

        // 16. getNameForUid 不得返回宿主包名
        run {
            var detail = ""
            var passed = false
            try {
                val uid = pm.getPackageUid(packageName, 0)
                val name = pm.getNameForUid(uid)
                passed = name == packageName
                detail = "nameForUid=$name expected=$packageName"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("uid/name-for-uid-not-host", passed, detail)
        }

        // 17. sourceDir / publicSourceDir 必须指向真机安装路径，不能是容器里的 APK 副本。
        //     注意真机 sourceDir 本身就以 base.apk 结尾，不能拿这个当判据；
        //     真正的破绽是路径落在宿主私有目录（/guests/）下。
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getApplicationInfo(packageName, 0)
                val src = info.sourceDir.orEmpty()
                val isContainerCopy = src.contains("/guests/") ||
                    src.contains("/io.vt.framework/")
                passed = src.isNotEmpty() && !isContainerCopy
                detail = "sourceDir=$src"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("appinfo/source-dir-real", passed, detail)
        }

        val failed = results.count { !it.passed }
        val summary = "PM_AUDIT passed=${results.size - failed} failed=$failed"
        Log.i(TAG, summary)
        results.forEach { Log.i(TAG, "PM_AUDIT $it") }
        return results
    }

    /**
     * 组件级查询审计。
     *
     * 真机上 getActivityInfo / getServiceInfo / getReceiverInfo / getProviderInfo
     * 都能查到自身组件，且查不到的组件必须抛 NameNotFoundException。
     *
     * 容器里如果只在 after 阶段改写结果，一旦目标应用在真机上被卸载，
     * 原方法先抛出的 NameNotFoundException 会让改写结果被丢弃（Hooker.invoke
     * 在 throwable != null 时重新抛出），应用于是表现为"组件不存在"。
     */
    fun runComponentAudit(pm: PackageManager, packageName: String): List<Result> {
        val results = ArrayList<Result>()

        // 18. getActivityInfo 必须能查到自身 Activity
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                val activity = info.activities?.firstOrNull { it.name != null }
                if (activity == null) {
                    detail = "no activities in PackageInfo"
                } else {
                    val queried = pm.getActivityInfo(
                        ComponentName(packageName, activity.name), 0
                    )
                    passed = queried != null && queried.packageName == packageName
                    detail = "activity=${activity.name} queried=${queried?.packageName}"
                }
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("component/activity-info", passed, detail)
        }

        // 19. getServiceInfo 必须能查到自身 Service
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SERVICES)
                val service = info.services?.firstOrNull { it.name != null }
                if (service == null) {
                    detail = "no services in PackageInfo"
                } else {
                    val queried = pm.getServiceInfo(
                        ComponentName(packageName, service.name), 0
                    )
                    passed = queried != null && queried.packageName == packageName
                    detail = "service=${service.name} queried=${queried?.packageName}"
                }
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("component/service-info", passed, detail)
        }

        // 20. getReceiverInfo 必须能查到自身 Receiver
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_RECEIVERS)
                val receiver = info.receivers?.firstOrNull { it.name != null }
                if (receiver == null) {
                    detail = "no receivers in PackageInfo"
                } else {
                    val queried = pm.getReceiverInfo(
                        ComponentName(packageName, receiver.name), 0
                    )
                    passed = queried != null && queried.packageName == packageName
                    detail = "receiver=${receiver.name} queried=${queried?.packageName}"
                }
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("component/receiver-info", passed, detail)
        }

        // 21. getProviderInfo 必须能查到自身 Provider（按 ComponentName）
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_PROVIDERS)
                val provider = info.providers?.firstOrNull { !it.authority.isNullOrBlank() }
                if (provider == null) {
                    detail = "no providers in PackageInfo"
                } else {
                    val queried = pm.getProviderInfo(
                        ComponentName(packageName, provider.name), 0
                    )
                    passed = queried != null && queried.packageName == packageName
                    detail = "authority=${provider.authority} queried=${queried?.packageName}"
                }
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("component/provider-info", passed, detail)
        }

        // 22. 查询不存在的组件必须抛 NameNotFoundException（不能返回空壳对象）
        run {
            var detail = ""
            var passed = false
            try {
                pm.getActivityInfo(
                    ComponentName(packageName, "$packageName.NoSuchComponent"), 0
                )
                detail = "no exception for missing component"
            } catch (t: Throwable) {
                passed = t is PackageManager.NameNotFoundException
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("component/missing-throws", passed, detail)
        }

        // 23. GET_META_DATA 必须返回 manifest metaData
        //     注：metaData 挂在 ApplicationInfo 上，真机 PMS 也是如此。
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val value = info.metaData?.getString("io.vt.sampleapp.key")
                passed = value == "meta-ok"
                detail = "metaData value=$value"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("meta/requested", passed, detail)
        }

        // 24. 未请求 GET_META_DATA 时不得返回 metaData（真机 PMS 语义）
        run {
            var detail = ""
            var passed = false
            try {
                val info = pm.getApplicationInfo(packageName, 0)
                passed = info.metaData == null
                detail = "metaData present=${info.metaData != null}"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("meta/flags-respected", passed, detail)
        }

        // 25. getResourcesForApplication 必须返回自身资源，且不抛异常
        run {
            var detail = ""
            var passed = false
            try {
                val res = pm.getResourcesForApplication(packageName)
                passed = res != null
                val id = res?.getIdentifier("app_tag", "id", packageName)
                detail = "resources=${res != null} app_tag id=$id"
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("resources/self", passed, detail)
        }

        // 26. PackageManager 的静态查询路径必须与实例方法返回同一份视图。
        //     PackageManager.getPackageInfoAsUserCached 是静态方法，内部走
        //     PropertyInvalidatedCache → IPackageManager binder，
        //     完全不经过 ApplicationPackageManager，是容器 PM 视图的一个盲区。
        //     框架用它做 LoadedApk 初始化判定，应用也可以直接调用来对比。
        run {
            var detail = ""
            var passed = false
            try {
                val method = findStaticCachedQueryMethod()
                if (method == null) {
                    detail = "getPackageInfoAsUserCached not present on this API level"
                    passed = true // 该方法不存在则本项无意义，不算失败
                } else {
                    val staticInfo = method.invoke(null, packageName, 0L, 0) as? PackageInfo
                    val instanceInfo = pm.getPackageInfo(packageName, 0)
                    val staticUid = staticInfo?.applicationInfo?.uid
                    val instanceUid = instanceInfo.applicationInfo?.uid
                    val staticData = staticInfo?.applicationInfo?.dataDir
                    val instanceData = instanceInfo.applicationInfo?.dataDir
                    passed = staticInfo != null &&
                        staticUid == instanceUid &&
                        staticData == instanceData
                    detail = "static(uid=$staticUid,data=$staticData) " +
                        "instance(uid=$instanceUid,data=$instanceData)"
                }
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("static/cached-view-consistency", passed, detail)
        }

        // 27. 静态路径查宿主包必须同样不可见（否则宿主包会从这条路径泄漏）
        run {
            var detail = ""
            var passed = false
            try {
                val method = findStaticCachedQueryMethod()
                if (method == null) {
                    detail = "getPackageInfoAsUserCached not present on this API level"
                    passed = true
                } else {
                    val staticInfo = method.invoke(null, HOST_PACKAGE, 0L, 0) as? PackageInfo
                    passed = staticInfo == null
                    detail = "host visible via static path=${staticInfo != null}"
                }
            } catch (t: Throwable) {
                // 抛 NameNotFoundException 同样符合"不可见"语义
                passed = t is PackageManager.NameNotFoundException ||
                    (t.cause is PackageManager.NameNotFoundException)
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("static/host-invisible", passed, detail)
        }

        // 28. 组件查询与包查询必须返回同一份视图。
        //
        //     这条断言专门抓"before 回调用 throw 而不是 setThrowable 导致短路被取消"
        //     （Hooker.java:127-133 会捕获并回滚 result/throwable、取消 returnEarly，
        //     于是原方法照常执行）。
        //
        //     短路失效时 getActivityInfo 会返回**真机系统**的 ActivityInfo，
        //     其 applicationInfo.dataDir 是 /data/user/0/<pkg>；
        //     而 getApplicationInfo 被正常短路，返回容器视图，
        //     dataDir 是宿主私有目录 —— 两者必然矛盾。
        //
        //     注意不能靠"异常 message 格式"区分：
        //     AOSP 系统抛的 NameNotFoundException 用的也是 ComponentName.toString()，
        //     与容器构造的格式一致，无法辨别来源。
        //
        //     非恒真性已用反向验证证明：把 getActivityInfo 成功路径的
        //     param.setResult(info) 改成 param.result = info（不置 returnEarly，
        //     等价于短路失效），真机上：
        //       场景 C（应用真机已安装）→ FAIL（dataDir 矛盾）
        //       场景 A（应用真机已卸载）→ FAIL（抛 NameNotFoundException）
        //       场景 B（普通模式）      → PASS（真机语义下成立）
        //     详见 docs/TESTING.md 第 9.8 节。
        run {
            var detail = ""
            var passed = false
            try {
                val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                val activity = pkgInfo.activities?.firstOrNull { it.name != null }
                if (activity == null) {
                    detail = "no activities in PackageInfo"
                } else {
                    val activityInfo = pm.getActivityInfo(
                        ComponentName(packageName, activity.name), 0
                    )
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val activityData = activityInfo?.applicationInfo?.dataDir
                    val appData = appInfo.dataDir
                    passed = activityData != null && activityData == appData
                    detail = "activityDataDir=$activityData applicationDataDir=$appData"
                }
            } catch (t: Throwable) {
                detail = "throw ${t.javaClass.simpleName}: ${t.message}"
            }
            results += Result("component/view-consistency", passed, detail)
        }

        val failed = results.count { !it.passed }
        val summary = "PM_COMPONENT_AUDIT passed=${results.size - failed} failed=$failed"
        Log.i(TAG, summary)
        results.forEach { Log.i(TAG, "PM_COMPONENT_AUDIT $it") }
        return results
    }

    /**
     * 查找 PackageManager.getPackageInfoAsUserCached(String, long, int)。
     *
     * 该方法是 @hide / hiddenapi BLOCKED，编译期 SDK 里没有，
     * 必须遍历 declaredMethods 反射获取，并 setAccessible 才能调用。
     */
    private fun findStaticCachedQueryMethod(): java.lang.reflect.Method? {
        var found: java.lang.reflect.Method? = null
        for (method in PackageManager::class.java.declaredMethods) {
            if (method.name != "getPackageInfoAsUserCached") continue
            val params = method.parameterTypes
            if (params.size != 3) continue
            if (params[0] != String::class.java) continue
            if (params[1] != Long::class.javaPrimitiveType) continue
            if (params[2] != Int::class.javaPrimitiveType) continue
            found = method
            break
        }
        return runCatching {
            found?.apply { isAccessible = true }
        }.getOrNull()
    }

    /** 读取 @hide 字段（如 ApplicationInfo.credentialProtectedDataDir）。 */
    private fun readHiddenString(target: Any, name: String): String {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target) as? String ?: ""
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            } catch (_: Throwable) {
                return ""
            }
        }
        return ""
    }
}
