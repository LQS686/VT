package io.vt.samplemodule

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "io.vt.sampleapp" -> hookSampleApp(lpparam)
            "org.schabi.newpipe" -> hookRealApp(lpparam)
            "com.ss.android.ugc.aweme" -> hookDouyin(lpparam)
            "com.phoenix.read", "com.dragon.read" -> hookDragonRead(lpparam)
        }
        // 真实应用的诊断探针（PM 视图 + 系统服务结构）。
        // 与示例应用不同，这里读的是第三方应用在容器里实际看到的视图。
        runCatching { PmProbe().handleLoadPackage(lpparam) }
        runCatching { SvcProbe().handleLoadPackage(lpparam) }
        runCatching { SafeModeProbe().handleLoadPackage(lpparam) }
        runCatching { BinderIfaceProbe().handleLoadPackage(lpparam) }
    }

    private fun hookSampleApp(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("VT 示例模块已进入 ${lpparam.packageName}@${lpparam.processName}, classLoader=${lpparam.classLoader}")
        XposedHelpers.findAndHookMethod(
            "io.vt.sampleapp.MainActivity",
            lpparam.classLoader,
            "getMessage",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    param.result = "【Xposed 已注入】${param.result}"
                }
            }
        )
        XposedHelpers.findAndHookMethod(
            "io.vt.sampleapp.SampleApp",
            lpparam.classLoader,
            "appTag",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    param.result = "${param.result} | hooked=true"
                }
            }
        )
    }

    private fun hookDouyin(lpparam: XC_LoadPackage.LoadPackageParam) {        XposedBridge.log(
            "VT 示例模块已进入抖音 ${lpparam.packageName}@${lpparam.processName}, " +
                "classLoader=${lpparam.classLoader}"
        )
        runCatching {
            val activityClass = XposedHelpers.findClass(
                "com.ss.android.ugc.aweme.main.MainActivity",
                lpparam.classLoader
            )
            val hooks = XposedBridge.hookAllMethods(
                activityClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        XposedBridge.log("VT 抖音模块 Hook 生效: MainActivity.onCreate")
                        val activity = param.thisObject as? android.app.Activity ?: return
                        runCatching {
                            activity.window.statusBarColor = android.graphics.Color.MAGENTA
                            val decor = activity.window.decorView as? android.view.ViewGroup
                                ?: return@runCatching
                            val badge = android.widget.TextView(activity).apply {
                                text = "VT MODULE ACTIVE"
                                setTextColor(android.graphics.Color.WHITE)
                                setBackgroundColor(android.graphics.Color.MAGENTA)
                                textSize = 16f
                                setPadding(28, 14, 28, 14)
                            }
                            val lp = android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                                topMargin = 200
                            }
                            decor.addView(badge, lp)
                        }.onFailure {
                            XposedBridge.log("VT 抖音模块浮层失败: $it")
                        }
                    }
                }
            )
            XposedBridge.log("VT 抖音模块 Hook 安装数=${hooks.size}")
        }.onFailure {
            XposedBridge.log("VT 抖音模块 Hook 安装失败: $it")
        }
    }

    private val seenNet = java.util.Collections.synchronizedSet(HashSet<String>())

    // 红果/番茄：记录返回 110（ILLEGAL_ACCESS）的接口与调用栈。
    private fun hookDragonRead(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val netReq = XposedHelpers.findClass("com.dragon.read.util.NetReqUtil", lpparam.classLoader)
            val hooks = XposedBridge.hookAllMethods(
                netReq,
                "doAfterDeserialization",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val url = param.args.getOrNull(0) as? String ?: return
                        val throwable = param.args.getOrNull(1) as? Throwable
                        val raw = param.args.getOrNull(2) as? Int ?: -1
                        val data = param.args.getOrNull(3)
                        val code = runCatching {
                            XposedHelpers.callStaticMethod(
                                netReq, "analyseCode", throwable, raw, data
                            ) as Int
                        }.getOrDefault(-9999)
                        if (code == 110) {
                            if (seenNet.add("110|$url")) {
                                val fields = StringBuilder()
                                var type: Class<*>? = data?.javaClass
                                while (type != null && type != Any::class.java) {
                                    for (field in type.declaredFields) {
                                        if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                                        runCatching {
                                            field.isAccessible = true
                                            val value = field.get(data)
                                            if (value is String || value is Number || value is Boolean) {
                                                fields.append(field.name).append('=').append(value).append(' ')
                                            }
                                        }
                                    }
                                    type = type.superclass
                                }
                                XposedBridge.log(
                                    "VT-NET code=110 raw=$raw url=$url " +
                                        "err=${throwable?.javaClass?.name}:${throwable?.message} " +
                                        "fields=${fields.toString().take(500)} " +
                                        "headers=${param.args.getOrNull(4)?.toString()?.take(500)} " +
                                        "uptime=${android.os.SystemClock.elapsedRealtime()}"
                                )
                                XposedBridge.log(
                                    "VT-NET stack:\n" + android.util.Log.getStackTraceString(Throwable())
                                )
                            }
                        } else if (code != 0 && code != -2002 && seenNet.add("$code|$url")) {
                            XposedBridge.log("VT-NET code=$code raw=$raw url=$url")
                        }
                    }
                }
            )
            XposedBridge.log("VT-NET NetReqUtil hooks=$hooks")
        }.onFailure { XposedBridge.log("VT-NET NetReqUtil hook failed: $it") }

        runCatching {
            val impl = XposedHelpers.findClass(
                "com.dragon.read.component.base.NsBaseNetworkDependImpl",
                lpparam.classLoader
            )
            val hooks = XposedBridge.hookAllMethods(
                impl,
                "assertIllegalAccess",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        // 110 响应已由 NetReqUtil 记录；跳过客户端的盗版提示/安全模式跳转，
                        // 不修改响应，也不伪造签名。
                        param.result = null
                        if (seenNet.add("assert|${System.currentTimeMillis() / 5000}")) {
                            XposedBridge.log("VT-NET assertIllegalAccess skipped")
                        }
                    }
                }
            )
            XposedBridge.log("VT-NET assertIllegalAccess hooks=$hooks")
        }.onFailure { XposedBridge.log("VT-NET assertIllegalAccess hook failed: $it") }

        hookCronetSecurity(lpparam)
        hookNetworkParams(lpparam)
        hookOkhttpSecurity(lpparam)
        hookTtnetSecurity(lpparam)
        hookPiracyToast(lpparam)
        hookUnsafeText(lpparam)
        hookRpcHeaderDump(lpparam)
    }

    private val seenUnsafeText = java.util.Collections.synchronizedSet(HashSet<String>())

    // 110 的自定义 Reader 错误 UI/Toast 可能绕过 ToastUtils，直接在 TextView 层拦文本。
    private fun hookUnsafeText(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val cls = Class.forName("android.widget.TextView")
            val hooks = XposedBridge.hookAllMethods(
                cls,
                "setText",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val text = param.args.getOrNull(0) as? CharSequence ?: return
                        val value = text.toString()
                        if (value.contains("当前版本不安全") || value.contains("安全校验失败")) {
                            param.args[0] = ""
                            if (seenUnsafeText.add(value)) {
                                XposedBridge.log("VT-TEXT suppressed: ${value.take(48)}")
                            }
                        }
                    }
                }
            )
            XposedBridge.log("VT-TEXT hooks=${hooks.size}")
        }.onFailure { XposedBridge.log("VT-TEXT hook failed: $it") }
    }

    private val seenHdr = java.util.Collections.synchronizedSet(HashSet<String>())

    // 只读对比：记录 bookmall RPC 在 Retrofit 构建时的头部，找出与成功请求的差异。
    private fun hookRpcHeaderDump(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.bytedance.retrofit2.client.Request\$Builder", lpparam.classLoader
            )
            val hooks = XposedBridge.hookAllMethods(
                cls,
                "build",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val builder = param.thisObject ?: return
                        val url = XposedHelpers.getObjectField(builder, "url") as? String
                            ?: return
                        if (!url.contains("reading.snssdk.com") || !url.contains("bookmall")) {
                            return
                        }
                        val headers = XposedHelpers.getObjectField(builder, "headers")
                            as? List<*> ?: return
                        val sb = StringBuilder()
                        for (h in headers) {
                            val name = runCatching {
                                XposedHelpers.callMethod(h, "getName") as? String
                            }.getOrNull() ?: continue
                            val value = runCatching {
                                XposedHelpers.callMethod(h, "getValue") as? String
                            }.getOrNull() ?: continue
                            sb.append(name).append('=').append(value.length).append(' ')
                        }
                        if (seenHdr.add("$url|$sb")) {
                            XposedBridge.log("VT-HDR $url $sb")
                        }
                    }
                }
            )
            XposedBridge.log("VT-HDR hooks=${hooks.size}")
        }.onFailure { XposedBridge.log("VT-HDR hook failed: $it") }
    }

    private fun hookPiracyToast(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.dragon.read.util.ToastUtils", lpparam.classLoader
            )
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    val id = param.args.filterIsInstance<Int>().firstOrNull() ?: return
                    // string/cs9: 当前版本不安全，请到正规应用市场下载
                    if (id == 0x7f06130f) {
                        param.result = null
                        XposedBridge.log("VT-TOAST suppressed 0x7f06130f 当前版本不安全")
                    }
                }
            }
            var installed = 0
            for (name in listOf("showCommonToastSafely", "showCommonToast")) {
                installed += XposedBridge.hookAllMethods(cls, name, hook).size
            }
            XposedBridge.log("VT-TOAST hooks=$installed")
        }.onFailure { XposedBridge.log("VT-TOAST hook failed: $it") }

        runCatching {
            val toastCls = Class.forName("android.widget.Toast")
            val hooks = XposedBridge.hookAllMethods(
                toastCls,
                "show",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam<*>) {
                        val toast = param.thisObject ?: return
                        val text = runCatching {
                            XposedHelpers.getObjectField(toast, "mText") as? CharSequence
                        }.getOrNull()?.toString().orEmpty()
                        val view = runCatching {
                            XposedHelpers.getObjectField(toast, "mNextView") as? android.view.View
                        }.getOrNull()
                        val texts = ArrayList<String>()
                        if (text.isNotEmpty()) texts.add(text)
                        texts.addAll(collectViewTexts(view))
                        val hit = texts.firstOrNull {
                            it.contains("不安全") || it.contains("安全校验失败")
                        }
                        if (hit != null) {
                            param.result = null
                            XposedBridge.log("VT-TOAST suppressed view: ${hit.take(48)}")
                        }
                    }
                }
            )
            XposedBridge.log("VT-TOAST text hooks=${hooks.size}")
        }.onFailure { XposedBridge.log("VT-TOAST text hook failed: $it") }
    }

    private fun collectViewTexts(view: android.view.View?): List<String> {
        if (view == null) return emptyList()
        val out = ArrayList<String>()
        if (view is android.widget.TextView) view.text?.toString()?.let { out.add(it) }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) out.addAll(collectViewTexts(view.getChildAt(i)))
        }
        return out
    }

    private val seenTtnet = java.util.Collections.synchronizedSet(HashSet<String>())

    // TTNet native 层真正回调的签名桥（CronetAppProviderManager 只是其中一条路径）。
    private fun hookTtnetSecurity(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.bytedance.ttnet.cronet.AbsCronetDependAdapter",
                lpparam.classLoader
            )
            val hooks = XposedBridge.hookAllMethods(
                cls,
                "onCallToAddSecurityFactor",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val url = param.args.getOrNull(0) as? String ?: return
                        if (!url.contains("snssdk.com")) return
                        val path = url.substringBefore('?')
                        val result = param.result as? Map<*, *>
                        val sb = StringBuilder()
                        if (result == null) {
                            sb.append("null")
                        } else {
                            for ((key, value) in result) {
                                val k = key?.toString().orEmpty()
                                val v = value?.toString().orEmpty()
                                sb.append(k).append('=').append(v.length)
                                if (v.isNotEmpty()) sb.append(':').append(v.take(10))
                                sb.append(' ')
                            }
                        }
                        if (seenTtnet.add("$path|$sb")) {
                            XposedBridge.log("VT-TTNET $path $sb")
                        }
                    }
                }
            )
            XposedBridge.log("VT-TTNET hooks=${hooks.size}")
        }.onFailure { XposedBridge.log("VT-TTNET hook failed: $it") }
    }

    private val seenOk3 = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun hookOkhttpSecurity(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.bytedance.frameworks.baselib.network.http.ok3.impl.OkHttp3SecurityFactorInterceptor",
                lpparam.classLoader
            )
            val hooks = XposedBridge.hookAllMethods(
                cls,
                "intercept",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val chain = param.args.getOrNull(0) ?: return
                        val request = runCatching {
                            XposedHelpers.callMethod(chain, "request")
                        }.getOrNull() ?: return
                        val url = runCatching {
                            XposedHelpers.callMethod(request, "url").toString()
                        }.getOrNull() ?: return
                        if (!url.contains("snssdk.com")) return
                        val path = url.substringBefore('?')
                        val headers = runCatching {
                            XposedHelpers.callMethod(request, "headers")
                        }.getOrNull() ?: return
                        val sb = StringBuilder()
                        val size = runCatching {
                            XposedHelpers.callMethod(headers, "size") as Int
                        }.getOrDefault(0)
                        for (i in 0 until size) {
                            val name = runCatching {
                                XposedHelpers.callMethod(headers, "name", i) as String
                            }.getOrNull().orEmpty()
                            if (!name.startsWith("x-") && !name.equals("cookie", true)) continue
                            val value = runCatching {
                                XposedHelpers.callMethod(headers, "value", i) as String
                            }.getOrNull().orEmpty()
                            sb.append(name).append('=').append(value.length)
                            if (name.startsWith("x-") && value.isNotEmpty()) {
                                sb.append(':').append(value.take(10))
                            }
                            sb.append(' ')
                        }
                        if (seenOk3.add("$path|$sb")) {
                            XposedBridge.log("VT-OK3 $path $sb")
                        }
                    }
                }
            )
            XposedBridge.log("VT-OK3 hooks=${hooks.size}")
        }.onFailure { XposedBridge.log("VT-OK3 hook failed: $it") }
    }

    private val seenSig = java.util.Collections.synchronizedSet(HashSet<String>())

    // 对比被 110 的接口与正常接口，实际注入的安全头是否不同（只记长度/短前缀）。
    private fun hookNetworkParams(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.bytedance.frameworks.baselib.network.http.NetworkParams",
                lpparam.classLoader
            )
            for (name in listOf("tryAddSecurityFactor", "tryAddOecSecurityFactor")) {
                val hooks = XposedBridge.hookAllMethods(
                    cls,
                    name,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            val url = param.args.getOrNull(0) as? String ?: return
                            if (!url.contains("snssdk.com")) return
                            val path = url.substringBefore('?')
                            val result = param.result as? Map<*, *>
                            val sb = StringBuilder()
                            if (result == null) {
                                sb.append("null")
                            } else {
                                for ((key, value) in result) {
                                    val k = key?.toString().orEmpty()
                                    val v = value?.toString().orEmpty()
                                    if (k.startsWith("x-", true) || k.contains("sign", true)) {
                                        sb.append(k).append('=').append(v.length)
                                        if (k.contains("argus", true)) {
                                            sb.append(':').append(v.take(16))
                                        }
                                        sb.append(' ')
                                    }
                                }
                                if (path.contains("reading.snssdk.com")) {
                                    sb.append("KEYS=")
                                        .append(result.keys.take(30).joinToString(","))
                                }
                            }
                            if (seenSig.add("$name|$path|$sb")) {
                                XposedBridge.log("VT-SIG $name $path $sb")
                            }
                        }
                    }
                )
                XposedBridge.log("VT-SIG $name hooks=${hooks.size}")
            }
        }.onFailure { XposedBridge.log("VT-SIG hook failed: $it") }
    }

    private val seenSec = java.util.Collections.synchronizedSet(HashSet<String>())

    // 只记录安全头的名称与长度，不落 token 内容。
    private fun hookCronetSecurity(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (className in listOf(
            "com.ttnet.org.chromium.net.impl.CronetUrlRequest",
            "com.ttnet.org.chromium.net.impl.CronetUrlRequestContext"
        )) {
            runCatching {
                val cls = XposedHelpers.findClass(className, lpparam.classLoader)
                val hooks = XposedBridge.hookAllMethods(
                    cls,
                    "addSecurityFactor",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam<*>) {
                            val url = param.args.getOrNull(0) as? String ?: return
                            val path = url.substringBefore('?')
                            if (!path.contains("bookmall") && !path.contains("reader") &&
                                !path.contains("user") && !path.contains("crypt")
                            ) {
                                return
                            }
                            val arr = param.result as? Array<*> ?: return
                            val sb = StringBuilder()
                            var i = 0
                            while (i + 1 < arr.size) {
                                val key = arr[i]?.toString().orEmpty()
                                val value = arr[i + 1]?.toString().orEmpty()
                                if (key.contains("argus", true) || key.contains("ladon", true) ||
                                    key.contains("sign", true) || key.contains("khronos", true) ||
                                    key.startsWith("x-")
                                ) {
                                    sb.append(key).append('=').append(value.length).append(' ')
                                }
                                i += 2
                            }
                            if (seenSec.add(path)) {
                                XposedBridge.log("VT-SEC $path $sb")
                            }
                        }
                    }
                )
                XposedBridge.log("VT-SEC $className hooks=$hooks")
            }.onFailure { XposedBridge.log("VT-SEC hook $className failed: $it") }
        }

        // 真正把 Java 侧请求交给 SecSDK 生成安全头的桥；成功后返回 x-argus 等。
        runCatching {
            val provider = XposedHelpers.findClass(
                "org.chromium.CronetAppProviderManager",
                lpparam.classLoader
            )
            val hooks = XposedBridge.hookAllMethods(
                provider,
                "onCallToAddSecurityFactor",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam<*>) {
                        val url = param.args.getOrNull(0) as? String ?: return
                        if (!url.contains("snssdk.com")) return
                        val path = url.substringBefore('?')
                        if (!path.contains("bookmall") && !path.contains("reader") &&
                            !path.contains("user") && !path.contains("crypt")
                        ) {
                            return
                        }
                        val result = param.result as? Map<*, *> ?: return
                        val sb = StringBuilder()
                        for ((key, value) in result) {
                            val k = key?.toString().orEmpty()
                            val v = value?.toString().orEmpty()
                            if (k.contains("argus", true) || k.contains("ladon", true) ||
                                k.contains("sign", true) || k.contains("khronos", true) ||
                                k.startsWith("x-")
                            ) {
                                sb.append(k).append('=').append(v.length).append(' ')
                            }
                        }
                        if (seenSec.add("p|$path")) {
                            XposedBridge.log("VT-SECP $path $sb")
                        }
                    }
                }
            )
            XposedBridge.log("VT-SECP CronetAppProviderManager hooks=$hooks")
        }.onFailure { XposedBridge.log("VT-SECP hook failed: $it") }
    }

    private fun hookRealApp(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log(
            "VT 示例模块已进入真实应用 ${lpparam.packageName}@${lpparam.processName}, " +
                "classLoader=${lpparam.classLoader}"
        )
        val activityClass = XposedHelpers.findClass(
            "org.schabi.newpipe.MainActivity",
            lpparam.classLoader
        )
        val hooks = XposedBridge.hookAllMethods(
            activityClass,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    XposedBridge.log("VT 真实应用 Hook 生效: NewPipe MainActivity.onCreate")
                }
            }
        )
        XposedBridge.log("VT 真实应用 Hook 安装数=${hooks.size}")
        val ctorHooks = XposedBridge.hookAllConstructors(
            activityClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    XposedBridge.log("VT 真实应用构造方法 Hook 生效: NewPipe MainActivity.<init>")
                }
            }
        )
        XposedBridge.log("VT 真实应用构造 Hook 安装数=${ctorHooks.size}")
    }
}
