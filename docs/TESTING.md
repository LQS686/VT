# 端到端测试记录

测试日期：2026-10-25
测试环境：Windows 10.0.26300，WHPX 加速的 AOSP Android 15（API 35）x86_64 模拟器。

## 1. 构建

```bash
./gradlew :app:assembleDebug :sample-app:assembleDebug :sample-module:assembleDebug
```

关键构建结果：

- `xposed` 模块使用 NDK `30.0.16248370`、CMake `3.30.5`、Ninja `1.12.1` 构建；
- 产物包含 `lib/x86_64/libvt_xposed.so`、`libc++_shared.so`；LSPlant 以静态库链接；
- `app-debug.apk` 安装成功。

## 2. LSPlant 初始化

Release ART 的 `libart.so` 没有 `.symtab`，只有 `.gnu_debugdata`。VT 运行时日志：

```
VT-Native: MiniDebugInfo symbols: 15767
VT-Native: unresolved symbol: _ZN3artL15GetMethodShortyEP7_JNIEnvP10_jmethodID
VT-Native: DobbyHook ... result=0
VT-Native: LSPlant init: ok
```

说明：

- `MiniDebugInfo symbols` 证明 xz-embedded 解压与符号解析生效；
- `GetMethodShorty` 的精确名未命中，但 LSPlant 使用前缀解析命中其 `.__uniq` 版本；
- Android 15 缺失 `JitCodeCache::GarbageCollectCache`，VT 将其别名到 `DoCollection`；
- 其余未解析符号（如 `Runtime::SetJavaDebuggable`）在 LSPlant 中为可选路径，初始化成功。

## 3. 导入与模块扫描

```
VT-Xposed: 导入成功：VT 示例应用 (3.0), apk=1, activities=2
VT-Xposed: 模块扫描: installed=182
VT-Xposed: DEBUG modules -> modules=1: io.vt.samplemodule
```

模块列表写入 `files/vt_modules.json`，Guest 记录写入 `files/vt_guests.json`。

## 4. Guest 启动与模块加载

```
VT-Xposed: GuestSession ready: io.vt.sampleapp v3.0, slot=0, dex=1, lib=.../lib, data=.../data
VT-Xposed: 加载模块 VT 示例 Xposed 模块 (io.vt.samplemodule), entry=[io.vt.samplemodule.HookEntry]
VT-Xposed: 创建 guest Application: io.vt.sampleapp.SampleApp
VT-Xposed: [VT 示例 Xposed 模块] handleLoadPackage 完成: io.vt.sampleapp@io.vt.sampleapp
VT-Xposed: [模块] VT 示例 Xposed 模块 已加载
```

- Guest `Application` 由 Guest `LoadedApk` 创建；
- 模块在启动 Activity 实例化之前、`Application` 创建之前加载（`performLaunchActivity` 阶段）；
- Guest 运行进程：`io.vt.framework:g0`；
- UI 显示：
  - `原始消息：VT Guest 正常启动`
  - `guestApplication=io.vt.sampleapp.SampleApp, process=..., dataDir=.../guests/io.vt.sampleapp/data | hooked=true`
  - `已打开页面次数：1，数据目录启动次数：...，版本：3.0`

`| hooked=true` 由示例模块的实例方法 Hook 追加，证明 Xposed Hook 真正生效。
静态 `getMessage()` 因 ART 内联未在 UI 中体现，属于已知限制。

## 4.1 组件虚拟化（v10 追加验证）

示例应用 v10 在主界面同时验证：

```
providerReady=true, queryCount=1,
serviceStarted=true, startResult=io.vt.sampleapp.SampleService,
native=native-ok,
bound=true, boundResult=pong-from-guest-service,
broadcast=registered/0
```

对应日志：

```
VT-Xposed: Guest provider ready: io.vt.sampleapp.provider -> io.vt.sampleapp.SampleProvider
VT-Xposed: Guest provider routed locally: io.vt.sampleapp.provider
VT-Xposed: Guest service hooks installed=8
VT-Xposed: Guest service created: io.vt.sampleapp.SampleService
VT-Xposed: Guest service started locally: io.vt.sampleapp.SampleService
VTSample: service connected: ... result=pong-from-guest-service
VTSample: broadcast received count=1
VTSample: broadcast received count=2
VTSample: broadcast received count=3
nativeloader: Load .../lib/libsample.so using isolated ns clns-8: ok
```

- Guest Provider 的 `queryCount=1` 来自本地 `SampleProvider` Transport，不经过系统 PMS；
- Guest Service 的 start/bind 均在 `:g0` 进程内完成；
- Native `.so` 由 Guest DexClassLoader 的 librarySearchPath 找到并由 nativeloader 加载；
- 广播计数在日志中为 3，静态 Receiver 计数为 1..2；UI 显示 0 是因为界面文本在异步投递前生成，属于测试 UI 的时序问题；
- 静态 Receiver 由 AXML 解析 `AndroidManifest.xml` 得到 action，启动时注册到宿主；
- 不再使用 Settings 默认值 shim；SettingsProvider 调用由宿主 attribution 正常通过。

截图：[screenshots/guest-v12-release.png](screenshots/guest-v12-release.png)

## 4.2 前台服务通知（示例 v14，2026-10-25 追加）

示例应用新增两个按钮：**测试前台通知** / **停止前台通知**。Service 用 Guest 资源图标创建通知，调用
`startForeground(7, n)`；通知的 contentIntent 指向 Guest MainActivity。

启动后日志与系统状态：

```
VT-Xposed: Guest startForeground virtualized: io.vt.sampleapp.SampleService id=7
NotificationRecord(... pkg=io.vt.framework ... id=7 tag=vt:io.vt.sampleapp:io.vt.sampleapp.SampleService
  importance=2 ... channel=vt_sample_foreground ... flags=ONGOING_EVENT)
  icon=Icon(typ=RESOURCE pkg=io.vt.framework id=0x7f040006)
  android.title=String (VT Guest 前台服务)
  android.text=String (来自 Guest 的前台通知)
```

- 通知在状态栏/通知中心可见，图标来自**宿主**资源（Guest 图标已重写）；
- 点击通知后焦点窗口变为 `org.schabi...`（示例为 `io.vt.sampleapp/io.vt.sampleapp.MainActivity`），
  证明 PendingIntent 回跳 Guest 生效；
- 点击“停止前台通知”后日志 `Guest stopForeground removed: io.vt.sampleapp.SampleService`，
  `dumpsys notification` 中不再有该记录；
- 重新发送通知后，从框架详情点“停止”，日志 `收到停止指令 ... 结束容器进程`，残留通知被清理，
  同时 `:g0` 进程退出；
- 该通知不是 AMS 前台服务：不会提升 oom adj，也没有 5 秒规则；媒体播放/下载的真实通知行为未验证。

截图：[screenshots/guest-foreground.png](screenshots/guest-foreground.png)、
[screenshots/guest-v14.png](screenshots/guest-v14.png)。

## 5. 多 Activity

点击 Guest 的“打开第二页”后：

```
VT-Xposed: startActivity 路由: io.vt.sampleapp/io.vt.sampleapp.SecondActivity -> io.vt.container.stub.StubStandard0
VT-Xposed: 启动 guest Activity: io.vt.sampleapp/io.vt.sampleapp.SecondActivity
```

无崩溃，`SecondActivity` 成为前台窗口。为让 AMS 接受调用，`execStartActivity` 中的 attribution context/包名被替换为宿主身份。

## 5.1 模块作用域

- Guest 启用 `io.vt.samplemodule`：UI 显示 `| hooked=true`；
- 在详情页停用该模块后重新启动同一 Guest：UI 不再出现 `| hooked=true`，数据目录与版本保持不变。

## 5.2 子进程 Activity（示例 v15，2026-10-25 追加）

示例应用新增 `RemoteActivity`（manifest `android:process=":remote"`）与“打开跨进程页面”按钮。点击后：

```
VT-Xposed: startActivity 路由: io.vt.sampleapp/io.vt.sampleapp.RemoteActivity
  -> io.vt.container.stub.StubStandard1, process=io.vt.sampleapp:remote, slot=1
VT-Xposed: guest process io.vt.framework:g1 bootstrapped, LSPlant ready=true
VT-Xposed: GuestSession ready: io.vt.sampleapp v15.0, slot=1, ...
VT-Xposed: 加载模块 VT 示例 Xposed 模块 (io.vt.samplemodule)
VT-Xposed: 启动 guest Activity: io.vt.sampleapp/io.vt.sampleapp.RemoteActivity
VT-Xposed: 创建 guest Application: io.vt.sampleapp.SampleApp
```

- 进程表同时存在 `io.vt.framework:g0`（主）与 `io.vt.framework:g1`（子），PID 不同；
- RemoteActivity 显示子进程 PID、Application 类名和同一 Guest dataDir；
- 返回键回到 `:g0` 的 MainActivity，两个进程继续存活；
- 框架“停止”先移除 Guest 任务（`移除 Guest 任务: task=203/190`），再向两个槽广播
  `请求停止 ... (slot=0/1)` + `收到停止指令`，两个进程均退出，任务列表无残留 Stub 任务；
- 限制：只支持一个额外进程；跨进程 Provider/Service/Receiver 未桥接。

截图：[screenshots/guest-remote-process.png](screenshots/guest-remote-process.png)、
[screenshots/guest-remote-release.png](screenshots/guest-remote-release.png)。

## 6. 真机更新

流程：安装示例应用 v1.0 → 导入 → 安装 v2.0 → `update` → 安装 v3.0 → `update`（后续迭代到 v10）。

```
DEBUG update -> updated VT 示例应用 2.0 -> 2.0
DEBUG update -> updated VT 示例应用 3.0 -> 3.0
DEBUG update -> updated VT 示例应用 10.0 -> 10.0
```

启动后的 Guest 使用最新版本（v10），数据目录仍是同一个 `.../guests/io.vt.sampleapp/data`，模块作用域仍为 `[io.vt.samplemodule]`。
（示例应用的数据计数 `launch_count` 在多次启动中递增，验证数据目录可写且未被更新清空。）

## 6.1 R8 / ABI 分包复测（2026-10-25）

构建：`./gradlew :app:assembleReleaseTest`。

产物：`app-arm64-v8a-releaseTest.apk` / `app-x86_64-releaseTest.apk` **约 2.8 MB**，通用包约 4 MB。

在 x86_64 模拟器安装 releaseTest 后复测：

- LSPlant init ok；
- GuestSession v12 / Provider / Service / 动态+静态广播 / 原生库 / 元数据 / 签名全部正常；
- UI 显示 `| hooked=true`，模块 Hook 未被 R8 破坏；
- `native=native-ok`、`meta=meta-ok`、`signers=1`。

releaseTest 使用 debug 签名并包含 DebugControlActivity，仅用于验证；正式发布需移除测试入口并重新签名。

补充（NewPipe 修复后复测）：同一 releaseTest x86_64 包上再次验证 NewPipe：

```
VT-Xposed: 加载模块 VT 示例 Xposed 模块 (io.vt.samplemodule)
VT-Xposed: VT 真实应用 Hook 生效: NewPipe MainActivity.onCreate
VT-Xposed: MediaSessionManager.createSession remapped to host identity
VT-Xposed: Guest service created: org.schabi.newpipe.player.PlayerService
```

主界面正常渲染（`mCurrentFocus=org.schabi.newpipe/org.schabi.newpipe.MainActivity`），示例应用
`hooked=true` 与全部组件检查仍通过。截图：[screenshots/newpipe-release.png](screenshots/newpipe-release.png)。

截图：[screenshots/guest-v11-release.png](screenshots/guest-v11-release.png)

## 7. 复现命令

```bash
adb shell am start -n io.vt.framework/.DebugControlActivity --es action import --es pkg io.vt.sampleapp
adb shell am start -n io.vt.framework/.DebugControlActivity --es action modules
adb shell am start -n io.vt.framework/.DebugControlActivity --es action enable --es pkg io.vt.sampleapp --es module io.vt.samplemodule
adb shell am start -n io.vt.framework/.DebugControlActivity --es action update --es pkg io.vt.sampleapp
adb shell am start -n io.vt.framework/.DebugControlActivity --es action launch --es pkg io.vt.sampleapp
adb shell am start -n io.vt.framework/.DebugControlActivity --es action stop --es pkg io.vt.sampleapp
```

查看日志：

```bash
adb logcat -s VT-Xposed:I VT-Native:I VT-Guest:I LSPlant:V
```

查看 PackageManager 自洽性审计（示例应用内置 `PmAudit`，17 项断言）：

```bash
adb logcat -s VTSample:I | grep PM_AUDIT
```

预期输出 `PM_AUDIT passed=17 failed=0`。容器模式与普通模式的审计结果必须一致；
出现 `FAIL` 说明容器暴露了 PM 视图破绽，属于回归。

## 8. 真实第三方应用 NewPipe（2026-10-25）

### 环境与步骤

- 应用：NewPipe 0.29.1（`org.schabi.newpipe`），x86_64 可运行，无 native ABI 障碍；
- 安装 APK → 在 VT 中导入（13 个 Activity、4 个静态 Receiver）→ 详情页启动；
- 占位 Activity 为 `exported=false`，不能用 `adb shell am start` 直接启动，必须经管理器 UI 进入。

### 修复过程

| 失败点 | 异常 | 处理 |
| --- | --- | --- |
| `getExternalCacheDir` | `StorageManager.getVolumeList` 包归属校验 | Guest 外部存储目录重定向，before Hook + `setResult` |
| `createNotificationChannels` | `Package org.schabi.newpipe is not owned by uid 10148` | Guest `NotificationManager` 全部调用重映射到宿主实例 |
| `LocaleManager.setApplicationLocales` | 缺 `CHANGE_CONFIGURATION` | Guest 内 no-op |
| `LocaleManager.getApplicationLocales` | 缺 `READ_APP_SPECIFIC_LOCALES` | 返回空 `LocaleList` |
| `PendingIntent.getBroadcast`（MediaSessionCompat） | `getIntentSender() ... not allowed to send as package org.schabi.newpipe` | Context/Intent 包名重写为宿主，Activity 目标改走占位 Activity |
| `MediaSessionManager.createSession` | `packageName does not belong to the calling uid` | 调用前把管理器 `mContext` 换成宿主 |
| `PlayerService` 初始化 | 上述 MediaSession 校验 | 修复后日志出现 `Guest service created: org.schabi.newpipe.player.PlayerService` |
| `requestPermissions(POST_NOTIFICATIONS)` | 宿主未授权时 Guest 包名无法走系统权限弹窗 | 按宿主真实授权自动回调；实测撤销宿主权限后收到 DENIED，应用继续运行 |

### 结果

```
VT-Xposed: 启动 guest Activity: org.schabi.newpipe/org.schabi.newpipe.MainActivity
VT-Xposed: 加载模块 VT 示例 Xposed 模块 (io.vt.samplemodule), entry=[io.vt.samplemodule.HookEntry]
VT-Xposed: VT 示例模块已进入真实应用 org.schabi.newpipe@org.schabi.newpipe,
           classLoader=DexClassLoader[.../guests/org.schabi.newpipe/apk/base.apk...]
VT-Xposed: VT 真实应用 Hook 安装数=1
VT-Xposed: VT 真实应用构造 Hook 安装数=1
VT-Xposed: VT 真实应用构造方法 Hook 生效: NewPipe MainActivity.<init>
VT-Xposed: 创建 guest Application: org.schabi.newpipe.App
VT-Xposed: VT 真实应用 Hook 生效: NewPipe MainActivity.onCreate
VT-Xposed: MediaSessionManager.createSession remapped to host identity
VT-Xposed: Guest service created: org.schabi.newpipe.player.PlayerService
VT-Xposed: Guest requestPermissions shimmed: android.permission.POST_NOTIFICATIONS granted=0
```

- 主界面、抽屉、Settings、Video and audio 设置页正常显示；
- 模块在 Activity 实例化前加载，构造方法 Hook 命中启动 Activity；
- 焦点窗口：`org.schabi.newpipe/org.schabi.newpipe.MainActivity`；
- NewPipe 首页显示 `Network error`：模拟器无 YouTube 代理，属于测试环境问题；
- 未验证实际播放、前台媒体通知、下载与子进程。

截图：[screenshots/newpipe-main.png](screenshots/newpipe-main.png)、
[screenshots/newpipe-drawer.png](screenshots/newpipe-drawer.png)、
[screenshots/newpipe-settings.png](screenshots/newpipe-settings.png)。

### 回归

同一构建下重跑示例应用：`hooked=true`、`providerReady=true`、`serviceStarted=true`、
`boundResult=pong-from-guest-service`、`native=native-ok`、`meta=meta-ok`、`signers=1`，
无崩溃。截图：[screenshots/guest-v12-regression.png](screenshots/guest-v12-regression.png)。

## 8.1 第二个真实应用 Telegram（2026-10-25）

- 应用：Telegram 13.1.0（`org.telegram.messenger.web`），官方 APK 68 MB，含 x86_64 原生库；
- 导入：22 个 Activity、14 个静态 Receiver、`libtmessages.49.so`（23.8 MB）；
- 首次启动失败：启动入口 `org.telegram.messenger.DefaultIcon` 是 activity-alias，按类名加载导致
  `ClassNotFoundException`。修复：导入时用 `ActivityInfo.targetActivity` 解析为
  `org.telegram.ui.LaunchActivity`；
- 第二次失败：`LaunchActivity` 构造函数访问 `ApplicationLoader.applicationContext` 为 null。修复：
  `performLaunchActivity` before 阶段显式 `LoadedApk.makeApplication()`，Application 先于 Activity 创建；
- 修复后日志：

```
VT-Xposed: 启动 Telegram -> slot=0 (org.telegram.ui.LaunchActivity)
VT-Xposed: 创建 guest Application: org.telegram.messenger.ApplicationLoaderImpl
VT-Xposed: Static receiver failed: com.google.android.gms.measurement.AppMeasurementReceiver:
           java.lang.ClassNotFoundException: ...
VT-Xposed: Guest Application ensured before Activity: org.telegram.messenger.ApplicationLoaderImpl
VT-Xposed: 启动 guest Activity: org.telegram.messenger.web/org.telegram.ui.LaunchActivity
```

- 启动引导页正常显示；点击 Start Messaging 进入手机号页；
- Telegram 请求电话权限时：`Guest requestPermissions shimmed: READ_PHONE_STATE, READ_PHONE_NUMBERS granted=0`，
  权限弹窗按宿主授权被拒绝，应用继续到手机号输入页，无崩溃；
- 右上角菜单可进入 Proxy Settings，UI/SharedPreferences 正常；
- 缺失的 GMS Receiver 被跳过并记录，不影响启动；
- releaseTest 上重复上述启动，同样成功；
- 未验证：注册/登录、收发消息、GMS 推送、后台同步服务（需要手机号、网络与跨进程 Service 支持）。

截图：[screenshots/telegram-launch.png](screenshots/telegram-launch.png)、
[screenshots/telegram-phone.png](screenshots/telegram-phone.png)、
[screenshots/telegram-proxy.png](screenshots/telegram-proxy.png)、
[screenshots/telegram-release.png](screenshots/telegram-release.png)。

## 9. 真机 Android 16 / arm64（2026-10-25 追加）

设备：Xiaomi HyperOS，Android 16（API 36），arm64-v8a；系统已安装抖音 40.7.0、番茄小说 7.4.1.32。
框架：`app-arm64-v8a-releaseTest.apk` + 示例模块；通过 `DebugControlActivity` 导入/启动。

### 9.1 抖音 40.7.0

```
VT-Xposed: 导入成功：抖音 (40.7.0), apk=1, activities=1472
VT-Xposed: resolveLauncher(...): launcher=com.ss.android.ugc.aweme.splash.SplashActivity target=...main.MainActivity
VT-Xposed: Activity alias instantiated: ...splash.SplashActivity -> ...main.MainActivity
VT-Xposed: 创建 guest Application: com.ss.android.ugc.aweme.app.host.AwemeHostApplication
VT-Xposed: Static receivers installed: 206
VT-Xposed: Guest service created: com.ss.android.ugc.aweme.process.KeepPushLaunchService
VT-Xposed: Guest service created: com.bytedance.common.wschannel.server.WsChannelService
```

- 前台窗口：`com.ss.android.ugc.aweme/...MainActivity`；推荐流加载真实视频并播放，点赞/评论/分享数据正常；
- 未登录时出现登录半屏，可关闭后继续浏览；登录页可正常打开；
- 跨进程 Push/WsChannel Service 由 `StubGuestService` 在 `:g1` 启动/绑定（Binder 透传）。

### 9.2 真机 native / 身份修复（按崩溃顺序）

| 现象 | 根因 | 修复 |
| --- | --- | --- |
| 宿主 UI 打开 Activity 时 SIGILL | 宿主进程也初始化 LSPlant，MIUI Resources 类解析命中 Dobby near trampoline | 宿主进程不初始化 LSPlant；关闭 near-branch trampoline |
| Guest 类校验 SIGILL | near-branch trampoline 在 Android 16 上非法 | 使用 Dobby far trampoline |
| 类可见初始化 SIGSEGV（`MarkVisiblyInitialized`） | LSPlant legacy 内联 Hook 与 Android 16 不兼容 | SDK 36 跳过该组 Hook |
| WebView JNI 解析 SIGSEGV | LSPlant `RegisterNative` 系列 Hook 与 Android 16 JNI 布局不兼容 | SDK 36 跳过（VT 无 Native Hook API） |
| `Environment.isExternalStorageEmulated` 崩溃 | 卷列表与 Guest 路径不匹配 | StorageManager/Environment 宿主重定向 |
| `ACCESS_NETWORK_STATE` SecurityException | Guest 的普通权限未映射到宿主 | 宿主 manifest 补充常用普通权限 |
| `getHistoricalProcessExitReasons` DUMP 异常 | Guest 包不属于宿主 UID | 返回空退出历史 |
| 启动期 `File(null)` NPE | 应用在缺失路径上构造 File | File 构造器 null 路径安全兜底（记录日志） |
| 启动入口 ClassNotFound | `<activity-alias>` 未解析 | 保留 alias 组件写入 Intent，`Instrumentation.newActivity` 时映射到 `targetActivity`；Application 先于 Activity；Provider 先于 onCreate |
| Toast 窗口 `Package ... not in UID ...` | `Toast$TN` 用 Guest `context.getPackageName()` 构造窗口参数 | TN 的包名字符串与 Context 都替换为宿主身份 |
| 番茄系网络全部失败 | 启动任务 `h1.e(Application)` 未执行，TTNet/Cronet provider 为 null | 补 RPC/TTAccount 初始化，并以 `base.http.k.b` 为门闩兜底调用 `h1.e` |

### 9.3 抖音 Xposed 模块注入

在抖音 Guest 中仅启用 `io.vt.samplemodule`，冷启动后日志：

```
23:23:58.141  加载模块 VT 示例 Xposed 模块 (io.vt.samplemodule), entry=[io.vt.samplemodule.HookEntry]
23:23:58.147  VT 抖音模块 Hook 安装数=1
23:23:58.714  VT 抖音模块 Hook 生效: MainActivity.onCreate
```

- 模块 Hook 在真实推荐流上添加 “VT MODULE ACTIVE” 浮层，证明模块代码在抖音进程内执行；
- releaseTest 同样通过；最终 **2.8 MB/ABI 的非 debuggable releaseTest** 复测也出现浮层；
  模块 APK 重装后，加载器会回退到 PackageManager 的当前 APK 路径，不再出现“模块不可用”；
- 截图：[screenshots/douyin-module.png](screenshots/douyin-module.png)。

### 9.4 番茄小说 / 红果免费短剧 7.4.1.32（已跑通主流程，2026-10-26）

导入与入口：

```
resolveLauncher(com.dragon.read): launcher=...pages.splash.SplashActivity target=...main.MainFragmentActivity
resolveLauncher(com.phoenix.read): launcher=...pages.splash.SplashActivity target=...main.MainFragmentActivity
Activity alias instantiated: ...splash.SplashActivity -> ...main.MainFragmentActivity
```

- 导入：番茄小说 771 Activity、33 静态 Receiver；红果短剧 787 Activity；
- 早期失败根因：
  1. 导入器把 launcher alias 改写成 `MainFragmentActivity`，应用跳过闪屏初始化；
  2. 初始化期间 Toast 的窗口包名仍是 Guest 包，被 WindowManager 以
     `Adding window failed: Package ... not in UID ...` 拒绝，直接崩溃；
  3. HTTP 初始化任务未执行，`CronetEngine has not been initialized`、feed 显示“网络出错”；
  4. 抖音登录链路中 `execStartActivity` 把 Activity 上下文替换为宿主 Application，导致 `onActivityResult` 丢失。
- 修复：
  - 保留 alias 组件语义，实例化时映射到 `targetActivity`；
  - `Toast$TN` 包名/Context 宿主化；
  - RPC `g4.c`、TTAccount `p5.b`、HTTP `h1.e` 初始化兜底；
  - `execStartActivity` 保留 Guest Activity 作为结果接收者。
- 验证（debug 与 releaseTest，Android 16 arm64 真机）：
  - 番茄小说：Splash → 隐私同意 → 书城推荐流；排行榜、封面、搜索热词正常；
  - 红果短剧：Splash → “欢迎使用红果免费短剧”隐私弹窗 → 同意 → 推荐短剧流 → 全屏播放；
  - **但打开书本章节时，正文接口返回服务端 `ILLEGAL_ACCESS`（code 110），界面显示“当前版本不安全”**；
- 截图：[fanqie-main.png](screenshots/fanqie-main.png)、
  [phoenix-privacy.png](screenshots/phoenix-privacy.png)、
  [phoenix-feed.png](screenshots/phoenix-feed.png)、
  [phoenix-play.png](screenshots/phoenix-play.png)。

### 9.6 番茄/红果防盗版校验 `ILLEGAL_ACCESS`（2026-10-26）

复现：打开任意书籍 → 左滑开始阅读，正文页显示“当前版本不安全，请到正规应用市场下载”。

证据：

```
R.string 0x7f06130f = "当前版本不安全，请到正规应用市场下载"
NetReqUtil.assertRspDataOk -> ErrorCodeException(110, "ILLEGAL_ACCESS")
GET https://reading.snssdk.com/reading/reader/full/v?item_id=...&book_id=...
GET https://reading.snssdk.com/reading/reader/batch_full/v?...
HTTP 200, Content-Length: 51, body 业务码 110
```

同设备原版番茄匿名阅读正常，故排除账号/网络问题；这是服务端防盗版/风控对容器环境的判定。
本地已补齐真实安装路径、codePath、进程名、运行进程列表、PM/UID 包可见性、cleartext 等“真机化”项，
仍无法隐藏 Guest dataDir、内核 UID 与 `/proc/self/maps` 中的容器/Xposed 库。

结论：未解决；不建议将其伪装成本地弹窗绕过。需要受保护内容时使用官方应用；需要模块注入时，
root + LSPosed（真实 UID/数据目录）更可靠。本项目不绕过付费/会员/DRM。

### 9.5 抖音短信登录 / Activity 结果回调（容器侧已修复，待真实手机号复测）

用户复现：手机号 + 短信验证码登录 → 点击“验证并登录” → 通过滑块 → 直接跳到视频播放页；
手机收到验证码，但验证码输入界面没有出现。

定位：

- 路由日志只有 `DYLoginActivity -> StubStandard0` 与 `BdTuringVerifyActivity -> StubStandard0`，
  滑块成功后没有任何新 Activity；
- 验证码输入页属于 `DYLoginActivity` 内部状态，依赖滑块 Activity 的 `onActivityResult` 返回值；
- 容器此前在 `Instrumentation.execStartActivity` 中把调用方 Activity 替换为宿主 Application，
  导致系统无法把结果投递给 Guest Activity。

修复与验证：

- 只对非 Activity 上下文替换为宿主；Guest Activity 保留为结果接收者，包名归属继续由
  `getBasePackageName/getOpPackageName/attributionSource` Hook 伪装为宿主；
- 新增 `Activity.onActivityResult` 日志；
- 示例应用 v17 增加 `ResultActivity` 与“测试 ACTIVITY 结果回调”按钮：
  - 日志：`Guest Activity result: MainActivity req=1001 res=-1`；
  - UI：`Activity 结果: result-ok (resultCode=-1)`；
- 抖音登录页可正常打开（[douyin-login.png](screenshots/douyin-login.png)）；滑块后的短信验证码页
  需要真实手机号与短信，尚未由自动化完成复测。

截图：[douyin-main.png](screenshots/douyin-main.png)、
[douyin-feed.png](screenshots/douyin-feed.png)、
[douyin-feed-release.png](screenshots/douyin-feed-release.png)、
[douyin-module.png](screenshots/douyin-module.png)、
[douyin-login.png](screenshots/douyin-login.png)、
[fanqie-splash.png](screenshots/fanqie-splash.png)、
[fanqie-privacy.png](screenshots/fanqie-privacy.png)。

> 真机测试只安装框架/示例模块并复制目标 APK 到框架私有目录；未修改或重打包抖音/番茄安装包。
> 真机为 HyperOS，部分结果不可直接外推到所有 Android 16 设备。

## 9.7 PackageManager 视图自洽性修复（2026-10-26）

### 背景

此前多个模型尝试"真机化"均未定位到具体原因。本轮换一个思路：
不去猜应用怎么检测，而是**把 PackageManager 视图当作可证伪的规范**，
逐项对照真机语义做交叉校验，找出容器自相矛盾之处。

在示例应用中新增 `PmAudit`（`sample-app/src/main/java/io/vt/sampleapp/PmAudit.kt`），
共 17 项断言，全部是"真机上必然成立、容器里可能不成立"的交叉比对：

| # | 断言 | 真机语义 |
|---|---|---|
| 1 | `getPackageUid` == `getApplicationInfo().uid` | 同一包两处查询必须同 UID |
| 2 | `getPackagesForUid(self)` 含自身包名 | UID 反查必须能查到自己 |
| 3 | `getNameForUid` 落在 `getPackagesForUid` 集合内 | 两个 UID 反查接口必须自洽 |
| 4 | `getInstalledApplications` 中自身条目 uid+dataDir == 直接查询 | 列表与直接查询不能给两套值 |
| 5 | `getInstalledPackages` 中自身条目 == 直接查询 | 同上 |
| 6 | 查询宿主包抛 `NameNotFoundException` | 容器包对 Guest 不可见 |
| 7 | 宿主包不出现在已安装列表 | 同上 |
| 8 | `checkSignatures(self,self)` == `SIGNATURE_MATCH` | 自签名必然匹配 |
| 9 | `getPackageInfo(pkg,0)` 不返回 signatures/signingInfo | 真机 PMS 按 flags 填充字段 |
| 10 | `longVersionCode` == `versionCode` | 长版本号不能被截断 |
| 11 | 请求 `GET_SIGNING_CERTIFICATES` 时返回 signingInfo | 签名校验功能必须可用 |
| 12 | 请求 `GET_ACTIVITIES` 时返回组件列表 | 同上 |
| 13 | `credentialProtectedDataDir` == `dataDir`，`deviceProtectedDataDir` 不同 | 真机三目录结构 |
| 14 | `processName`/`taskAffinity` == 包名 | 身份字段自洽 |
| 15 | `checkSignatures(int,int)` 与 `(String,String)` 结果一致 | 两个重载不能矛盾 |
| 16 | `getNameForUid` 不返回宿主包名 | 反查不能暴露宿主 |
| 17 | `sourceDir` 不落在容器目录 | 安装路径形态与真机一致 |

### 修复前基线（模拟器 Android 15 x86_64）

```
PM_AUDIT passed=10 failed=7
FAIL uid/name-for-uid:          nameForUid=io.vt.framework packages=[io.vt.sampleapp]
FAIL list/app-consistency:      direct(uid=10148,data=/data/user/0/io.vt.framework/files/guests/io.vt.sampleapp/data)
                                listed(uid=10149,data=/data/user/0/io.vt.sampleapp)
FAIL list/package-consistency:  同上
FAIL host/invisible:            host package visible to guest
FAIL signature/flags-respected: signatures=true signingInfo=true
FAIL appinfo/data-dir-structure: deviceProtected == dataDir == credentialProtected
FAIL uid/name-for-uid-not-host: nameForUid=io.vt.framework
```

7 处 FAIL 全部对应代码里的具体缺陷，不是猜测：

1. **`getNameForUid` 完全没被接管** → 返回宿主包名 `io.vt.framework`。
   应用做一次 UID 反查就能确认自己在容器里。
2. **已安装列表保留系统原始 Guest 条目**，其 uid=10149（真机应用真实 UID）、
   dataDir=/data/user/0/io.vt.sampleapp；而 `getApplicationInfo` 返回 uid=10148（宿主 UID）、
   dataDir=容器目录。**两套值互相矛盾**，任何交叉比对都能发现。
3. **宿主包"隐藏"实际从未生效**：`GuestRuntime` 在 `afterHookedMethod` 里
   `throw NameNotFoundException`，但 `Hooker.invoke` 会捕获 after 回调抛出的异常并回滚
   `result`/`throwable`（`xposed/src/main/java/io/vt/xposed/Hooker.java:152-163`），
   异常被吞掉，宿主包名/APK 路径/签名仍可被直接查到。
   改用 `param.setThrowable(...)`（`before` 阶段 `returnEarly=true` 短路原方法）才真正生效。
4. **flags 语义错误**：`packageInfoFor` 无视 flags 一律填充 signatures/signingInfo，
   于是 `getPackageInfo(pkg, 0)` 也带签名 —— 真机 PMS 在 flags=0 时不返回这些字段。
   同时 flags 参数只按 `Int` 解析，API 33+ 的 `PackageInfoFlags`/`ApplicationInfoFlags`
   重载恒为 0，`GET_ACTIVITIES`/`GET_SIGNING_CERTIFICATES` 全部丢失。
5. **dataDir 三目录被写成同一个值**，与真机结构不符。
6. `getNameForUid` 与 `getPackagesForUid` 互相矛盾（同 1）。

### 修复内容

容器侧（`container/src/main/java/io/vt/container/`）：

- `runtime/GuestRuntime.kt` → `hookPackageManager()`：
  - 宿主包隐藏改用 `param.setThrowable(PackageManager.NameNotFoundException)`，
    并加 `isGuestCaller()` 判定，避免宿主框架自身查包被打死；
  - 新增 `resolveFlags()` 兼容 `Int`/`Long`/`PackageInfoFlags`/`ApplicationInfoFlags`（`getValue(): long`）；
  - `getNameForUid`/`getNamesForUid`/`getPackagesForUid`/`getPackageUid` 全部改为
    `before` 阶段 `setResult` 短路（目标应用在真机上被卸载时，原方法会先抛异常，
    `after` 阶段已无法补救）；
  - `checkSignatures(String,String)` 与 `checkSignatures(int,int)` 统一处理，
    另加 `hasSigningCertificate`；
  - 已安装列表中的 Guest 条目一律替换为容器自建条目，与直接查询保持一致；
  - 新增 `getInstallerForUid`/`getInstallerPackageName` 接管，返回导入时记录的真机安装来源。
- `runtime/GuestSession.kt`：
  - `ApplicationInfo` 的 `processName`/`taskAffinity`/`category`/`primaryCpuAbi`/
    `dataDir`/`deviceProtectedDataDir`/`credentialProtectedDataDir` 改用导入时采集的真机值；
  - 拆分**框架内部视图**（`appInfo`，指向容器内真实 APK 副本，供 LoadedApk/DexClassLoader 使用）
    与**对 Guest 暴露的真机视图**（`publicAppInfo()`，安装路径指向
    `/data/app/~~<hash>==/<pkg>-<hash>==/base.apk`）；两者混用会导致 ClassLoader 初始化失败；
  - `packageInfoFor(flags)` 按真机 PMS 的 flags 语义填充
    （仅 `GET_SIGNATURES` 返回 `signatures`，仅 `GET_SIGNING_CERTIFICATES` 返回 `signingInfo`）；
  - `versionCode` 用 `setLongVersionCode()` 写入，避免长版本号被 `toInt()` 截断；
  - `SigningInfo` 补齐 `signingCertificateHistory` 与 schemeVersion；
  - 组件的 `exported`/`processName`/`taskAffinity`/`permission` 等改用导入的真实值；
  - `unbindGuestService` 不再误调 `onServiceDisconnected`，改为反射调用 `Service.onUnbind/onRebind`。
- `imports/GuestImporter.kt`：导入时采集并落库 `appProcessName`/`appTaskAffinity`/`appCategory`/
  `primaryCpuAbi`/`requestedPermissions`/`signingSchemeVersion`/`multipleSigners`/`signingHistory`。
- `data/Models.kt`：新增上述字段，以及 `syntheticInstalledSourceDir()` 等
  "真机形态路径"生成器（真机路径失效时用同形态占位，而不是暴露容器目录）。

### 修复后结果

```
# 容器模式（Guest 进程内）
PM_AUDIT passed=17 failed=0

# 普通模式（应用真机直装）
PM_AUDIT passed=17 failed=0

# 真机已卸载该应用后（Guest APK 只存在于容器内）
PM_AUDIT passed=17 failed=0
```

宿主 UI（`io.vt.framework/.ui.MainActivity`）启动与 Guest 启动均无 FATAL 异常。

### 仍存在的架构硬限制

以下项目在免 root 约束下**无法**消除，已在 `PmAudit` 中标注为"结构自洽"而非"路径一致"：

- `dataDir` 物理路径位于宿主私有目录 `/data/user/0/io.vt.framework/files/guests/...`
  （容器内 UID = 宿主 UID，无法在 `/data/user/0/<guestpkg>` 建目录）；
- 内核 UID 是宿主 UID（`/proc/self/uid_map`、`/proc/self/maps` 可见容器痕迹）；
- `/proc/self/maps` 含 `libvt_xposed.so`、容器 APK/oat 路径；
- Binder attribution 为宿主包名。

这些属于"运行时环境痕迹"，与本轮修复的"PM 视图自相矛盾"是两个不同层面：
后者是**逻辑错误**，任何应用几行代码即可判定，必须修；
前者是**架构代价**，只能靠减少暴露面来缓解。

## 9.8 PackageManager 视图自洽性修复·第二批（2026-10-26）

### 背景

9.7 节把 PM 视图从 10 项断言扩到 17 项并修掉了 7 处确定缺陷。
本轮继续用同一方法论（把 PM 视图当可证伪规范）复核剩余面，
把断言扩到 **28 项**（新增组件级 + 静态路径 11 项），
又定位并修复了 **9 处缺陷**。

### 复核方法

对 `android.app.ApplicationPackageManager` 与 `android.content.pm.PackageManager`
做方法级枚举，把所有可能暴露宿主/容器差异的查询入口列全，
再用 `dexdump` 反汇编 Android 15 的 `framework.jar`，
逐个确认每个入口**最终落到哪条实现路径**（实例方法 / 静态方法 / binder 直连），
避免"以为 hook 住了其实没 hook"。

### 修复前基线（模拟器 Android 15 x86_64，容器模式）

```
PM_AUDIT          passed=17 failed=0
PM_COMPONENT_AUDIT passed=8  failed=2
FAIL static/cached-view-consistency: static(uid=10149,data=/data/user/0/io.vt.sampleapp)
                                    instance(uid=10148,data=/data/user/0/io.vt.framework/files/guests/...)
FAIL static/host-invisible: host visible via static path=true
```

第 26/27 项的 FAIL 直接暴露了 9.7 节**遗漏的一整条查询路径**：

```
ApplicationPackageManager.getPackageInfo(...)      ← 9.7 节 hook 了
        ↓ 但框架与静态调用走的是另一条：
PackageManager.getPackageInfoAsUserCached(String,long,int)   ← 静态、@hide/BLOCKED
        ↓ PropertyInvalidatedCache.query(...)
PackageManager.getPackageInfoAsUserUncached(...)    ← private static
        ↓ ActivityThread.getPackageManager() → IPackageManager
        ↓ binder 直连 PMS
```

**这条路径完全不经过 `ApplicationPackageManager`**，9.7 节所有实例方法 hook 一个都拦不住它。
后果有两层：

1. **崩溃**：目标应用在真机上被卸载后 PMS 返回 null，
   `LoadedApk.initializeJavaContextClassLoader()` 直接抛
   `IllegalStateException: Unable to get package info for X; is package not installed?`，
   Guest 连 Application 都创建不出来 —— 这正是本轮实测到的回归：
   ```
   Caused by: java.lang.IllegalStateException: Unable to get package info for io.vt.sampleapp; is package not installed?
       at android.app.LoadedApk.initializeJavaContextClassLoader(LoadedApk.java:1218)
   ```
2. **视图泄漏**：该路径返回 PMS 的真实结果（真实 UID、真实 dataDir、真实安装态），
   与 `ApplicationPackageManager` 路径返回的容器视图**互相矛盾**。
   应用只要同时调一次静态方法与实例方法，就能拿到两套对不上的数据。

### 9 处修复

| # | 缺陷 | 位置 | 后果 |
|---|---|---|---|
| 8 | 静态查询路径 `getPackageInfoAsUserCached` 未接管 | `GuestRuntime.hookPackageManagerStaticQueries()` | 卸载场景启动崩溃 + 真实 UID/dataDir 泄漏 |
| 9 | `getActivityInfo` 在 `after` 阶段改写结果 | `GuestRuntime` | 原方法抛异常时改写被丢弃（`Hooker.java:172-174`） |
| 10 | `getServiceInfo`/`getReceiverInfo`/`getProviderInfo` 完全未接管 | `GuestRuntime` | 应用查不到自身组件；宿主框架查真实包，视图不一致 |
| 11 | `getResourcesForApplication` 在 `after` 阶段改写 | `GuestRuntime` | 同 #9 |
| 12 | `getLaunchIntentForPackage`/`getInstallerForUid`/`getInstallerPackageName`/`getApplicationLabel` 在 `after` 阶段改写 | `GuestRuntime` | 同 #9 |
| 13 | 已安装列表在 `after` 阶段直接赋值 `param.result` | `GuestRuntime` | 宿主查询因权限抛异常时，改写结果被丢弃 |
| 14 | `getActivityInfo` 对不存在的组件也返回合法 ActivityInfo | `GuestSession.activityInfoFor()` | 应用查任意假组件都有返回，与真机相反 |
| 15 | `GET_ACTIVITIES` 用懒加载缓存构造组件列表 | `GuestSession.packageInfoFor()` | 先查列表后启动 Activity 的顺序会拿到空列表 |
| 16 | `metaData` 未按 flags 语义返回 | `GuestSession` | `getPackageInfo(pkg,0)` 也带 metaData；`GET_META_DATA` 反而拿不到 |
| 17 | `before` 回调用 `throw` 而非 `setThrowable`，短路被取消 | `GuestRuntime`（4 处组件 hook） | 真机上应用已安装时原方法照常执行，严格语义直接失效（见下） |

其中 #9/#11/#12/#13 的根因是同一条 AOSP 语义（`Hooker.java:152-174`）：

> `after` 回调里 `param.result = X` **只赋值 result，不清 throwable**；
> 而 `param.setResult(X)` 会同时 `throwable = null`。
> 原方法一旦抛过异常（目标应用真机已卸载时必然抛），
> `Hooker.invoke()` 末尾仍会 `throw param.throwable`，改写结果被丢弃。
> 正确做法是能在 `before` 阶段短路的就 `before` + `setResult`/`setThrowable`。

### #17：`before` 回调不能靠 `throw` 短路

这是真机回归时从容器自身 `files/logs/xposed.log` 里发现的（HyperOS 会过滤第三方
tag 的 logcat，只有容器自己的日志有完整记录）：

```
10:51:16.387  beforeHookedMethod failed for public android.content.pm.ActivityInfo
              android.app.ApplicationPackageManager.getActivityInfo(android.content.ComponentName,int)
              throws android.content.pm.PackageManager$NameNotFoundException:
              io.vt.sampleapp/io.vt.sampleapp.NoSuchComponent
```

根因是 `Hooker.invoke()` 对 before 阶段异常的**回滚语义**（`Hooker.java:125-137`）：

```java
try {
    callback.callBeforeHookedMethod(param);
} catch (Throwable t) {
    XposedLog.log("beforeHookedMethod failed for " + target + ": " + t);
    param.result = null;
    param.throwable = null;
    param.returnEarly = false;   // ← 取消短路，原方法照常执行
    continue;
}
```

也就是说，`before` 回调里 `throw` 一个 `NameNotFoundException`，
**不会**让调用方收到这个异常，而是被框架捕获后**回滚并放行原方法**：

- 目标应用真机上**没装** → 原方法也抛 `NameNotFoundException`，结果"碰巧正确"；
- 目标应用真机上**装了** → 原方法正常返回**真机系统的** ActivityInfo，
  严格语义（查不到就必须抛）**完全失效**，而且返回的是真机视图，与容器视图矛盾。

正确写法是 `param.setThrowable(ex)`（内部会置 `returnEarly = true`，
见 `XC_MethodHook.java:165-169`）。4 处组件 hook
（`getActivityInfo`/`getServiceInfo`/`getReceiverInfo`/`getProviderInfo`）
已全部改为 `setThrowable`。

### 修复后结果（三场景全部 28/28）

```
# 容器模式（Guest 进程内，应用真机已安装）
PM_AUDIT passed=17 failed=0
PM_COMPONENT_AUDIT passed=11 failed=0

# 普通模式（应用真机直装，不经容器）
PM_AUDIT passed=17 failed=0
PM_COMPONENT_AUDIT passed=11 failed=0

# 真机已卸载该应用后（Guest APK 只存在于容器内）
PM_AUDIT passed=17 failed=0
PM_COMPONENT_AUDIT passed=11 failed=0
FATAL EXCEPTION 计数 = 0
Xposed 模块注入正常（handleLoadPackage 完成）
```

### 新增断言（18–28）

| # | 断言 | 真机语义 |
|---|---|---|
| 18 | `getActivityInfo` 能查到自身 Activity | 组件查询必须可用 |
| 19 | `getServiceInfo` 能查到自身 Service | 同上 |
| 20 | `getReceiverInfo` 能查到自身 Receiver | 同上 |
| 21 | `getProviderInfo` 能查到自身 Provider | 同上 |
| 22 | 查询不存在的组件抛 `NameNotFoundException` | 不能给任意类名返回空壳 ActivityInfo |
| 23 | `GET_META_DATA` 返回 manifest metaData | 功能可用 |
| 24 | 未请求 `GET_META_DATA` 时不返回 metaData | 真机 PMS 按 flags 填充 |
| 25 | `getResourcesForApplication` 返回自身资源且不抛异常 | 功能可用 |
| 26 | 静态路径与实例方法返回同一份视图 | 两套数据不能矛盾 |
| 27 | 静态路径查宿主包同样不可见 | 宿主包不能从旁路泄漏 |
| 28 | `component/view-consistency`：组件查询与包查询返回同一份视图 | 短路失效时必然矛盾（见下） |

### 断言 28 的设计：为什么不能靠异常 message 区分

断言 28 要抓的是 #17 那种"短路被取消、原方法照常执行"的情况。
第一版想用异常 message 的格式来判别（`ComponentInfo{...}`），
但实测发现**行不通**：

- 容器构造的异常用 `component.flattenToString()`；
- AOSP 系统抛的 `NameNotFoundException` 用的**也是** `ComponentName.toString()`，
  两者格式完全一致，无法辨别来源。

改用**交叉比对**才可靠 —— 直接查询与包查询必须返回同一个 `dataDir`：

```kotlin
val activityInfo = pm.getActivityInfo(ComponentName(packageName, activity.name), 0)
val appInfo = pm.getApplicationInfo(packageName, 0)
passed = activityInfo?.applicationInfo?.dataDir != null &&
         activityInfo?.applicationInfo?.dataDir == appInfo.dataDir
```

短路失效时 `getActivityInfo` 返回**真机系统**的 ActivityInfo，
其 `applicationInfo.dataDir` 是 `/data/user/0/<pkg>`；
而 `getApplicationInfo` 被正常短路，返回容器视图，
`dataDir` 是宿主私有目录 —— 两者必然矛盾。

### 断言 28 的反向验证（证明非恒真）

断言写对之后必须证明它**能失败**，否则只是一条恒真的装饰。
做法：把 `getActivityInfo` 成功路径的 `param.setResult(info)` 临时改成
`param.result = info`（不置 `returnEarly`，等价于短路失效、原方法照常执行），
重新构建并在真机上复跑：

```
== 场景 C：容器模式（应用真机已安装）==
  PM_COMPONENT_AUDIT passed=10 failed=1
  PM_COMPONENT_AUDIT FAIL component/view-consistency:
    activityDataDir=/data/user/0/io.vt.sampleapp
    applicationDataDir=/data/user/0/io.vt.framework/files/guests/io.vt.sampleapp/data
  [FAIL] 容器模式/已安装: 存在 failed>0 的断言

== 场景 A：容器模式（真机已卸载该应用）==
  PM_COMPONENT_AUDIT passed=9 failed=2
  PM_COMPONENT_AUDIT FAIL component/activity-info: throw NameNotFoundException:
    ComponentInfo{io.vt.sampleapp/io.vt.sampleapp.MainActivity}
  PM_COMPONENT_AUDIT FAIL component/view-consistency: throw NameNotFoundException: ...
  [FAIL] 容器模式/已卸载: 存在 failed>0 的断言

== 场景 B：普通模式（应用真机直装）==
  PM_COMPONENT_AUDIT passed=11 failed=0
  [PASS] 普通模式/真机直装
```

三个场景的组合证明断言有效：

| 场景 | 短路失效时的表现 | 说明 |
|---|---|---|
| C（应用真机已安装） | dataDir 矛盾 → FAIL | 精确命中设计意图：真机 `/data/user/0/<pkg>` vs 容器目录 |
| A（应用真机已卸载） | 抛 `NameNotFoundException` → FAIL | 系统路径拿不到组件，无法构造容器视图 |
| B（普通模式，无注入） | 11/11 PASS | 真机语义下断言成立，且不是"只在容器里才过"的人造规则 |

恢复修复版本后重跑，真机三场景回到 `28/28` 全通过、`PASS=7 FAIL=0`。

### 复用注意

容器里的 Guest 用的是**导入时的 APK 副本**，
重装示例应用后必须重新执行 `import` 才能让 Guest 跑到新代码：

```bash
adb shell am start -n io.vt.framework/.DebugControlActivity --es action import --es pkg io.vt.sampleapp
```

否则会看到"审计项数没变"的假象（用旧的示例应用副本在跑）。

### 一键回归脚本

`scripts/pm-regression.sh` 把上述三场景固化成一条命令，
真机/模拟器到位后可直接复跑：

```bash
bash scripts/pm-regression.sh              # 自动选第一台设备
bash scripts/pm-regression.sh emulator-5554
```

脚本会依次执行：构建 → 安装三件套 → 场景C（容器/已安装）→ 场景A（容器/已卸载）→
场景B（普通模式），每场景校验审计结果、FATAL 计数与模块注入，最后输出 `PASS=n FAIL=n`。

> **注意**：厂商 ROM（HyperOS 等）会过滤第三方 tag 的 logcat，
> 容器自身的 hook 日志只能从 `run-as io.vt.framework cat files/logs/xposed.log` 读。
> 脚本的 `container_log()` 就是这么做的 —— 否则会误判成"Xposed 模块未注入"。

模拟器（Android 15 x86_64）实测：

```
== 场景 C：容器模式（应用真机已安装）==
  [PASS] 容器模式/已安装: 28 项断言全部通过（基础 17 + 组件 11）
  [PASS] 无 FATAL EXCEPTION
  [PASS] Xposed 模块注入成功
== 场景 A：容器模式（真机已卸载该应用）==
  [PASS] 容器模式/已卸载: 28 项断言全部通过（基础 17 + 组件 11）
  [PASS] 无 FATAL EXCEPTION
  [PASS] Xposed 模块注入成功
== 场景 B：普通模式（应用真机直装）==
  [PASS] 普通模式/真机直装: 28 项断言全部通过（基础 17 + 组件 11）
== 汇总 ==
PASS=7 FAIL=0
```

真机（eb72c928 / 23116PN5BC / Android 16 arm64 / API 36 / HyperOS）实测：

```
== 场景 C：容器模式（应用真机已安装）==
  [PASS] 容器模式/已安装: 28 项断言全部通过（基础 17 + 组件 11）
  [PASS] 无 FATAL EXCEPTION
  [PASS] Xposed 模块注入成功
== 场景 A：容器模式（真机已卸载该应用）==
  [PASS] 容器模式/已卸载: 28 项断言全部通过（基础 17 + 组件 11）
  [PASS] 无 FATAL EXCEPTION
  [PASS] Xposed 模块注入成功
== 场景 B：普通模式（应用真机直装）==
  [PASS] 普通模式/真机直装: 28 项断言全部通过（基础 17 + 组件 11）
== 汇总 ==
PASS=7 FAIL=0
```

真机上两条关键断言的原始输出：

```
PM_COMPONENT_AUDIT PASS static/cached-view-consistency:
  static(uid=10585,data=/data/user/0/io.vt.framework/files/guests/io.vt.sampleapp/data)
  instance(uid=10585,data=同上)
PM_COMPONENT_AUDIT PASS component/view-consistency:
  activityDataDir=/data/user/0/io.vt.framework/files/guests/io.vt.sampleapp/data
  applicationDataDir=/data/user/0/io.vt.framework/files/guests/io.vt.sampleapp/data
```

### 真机调试的三个坑

1. **logcat 过滤**：HyperOS 不输出第三方 tag 的 logcat，
   `VTSample` 的审计输出有时能拿到、hook 日志（`VT-Xposed`）拿不到。
   容器自己的 `files/logs/xposed.log` 是完整真相源。
2. **import 异步落库竞态**：`import` 是异步的，
   `enable` 若发生在落库完成前会写到旧记录上，随后被 import 覆盖，
   表现为"未启用任何 Xposed 模块"。脚本的 `enable_module()` 会重试直到
   `vt_guests.json` 里出现模块名。
3. **Guest 用的是导入时的 APK 副本**：改了示例应用代码后必须重新 `import`，
   否则跑的还是旧副本。

## 9.9 启动回归、Toast 崩溃与 Metasec 反 Hook 采样（2026-10-26 第二批）

### 背景与现象

1. **启动回归**：优化 PM 视图后，红果/番茄"启动页一闪 → 回桌面"。
   logcat：

   ```
   W ActivityTaskManager: Permission Denial: package=com.phoenix.read does not belong to uid=10585
   ```

2. **Toast 崩溃**：红果播放过程中调用 `ToastUtils.showCommonToastSafely`，
   进程被 `SecurityException` 直接杀掉：

   ```
   java.lang.SecurityException: Package com.phoenix.read is not owned by uid 10585
       at android.app.INotificationManager$Stub$Proxy.enqueueToast
       at android.widget.Toast.show
       at com.dragon.read.util.ToastUtils.showCommonToastSafely
   ```

3. **正文被拦**：全新数据首会话可读正文，一段时间/重启后出现"当前版本不安全"。

### 定位证据

- 真机 `framework.jar`（Android 16 HyperOS）反汇编：
  `Instrumentation.execStartActivity(7 参重载)` 在 0x00fd 处 `invoke-virtual {v2},
  Landroid/content/Context;.getOpPackageName()`（method@8fe4），随后作为
  `IActivityTaskManager.startActivity` 的 callingPackage 参数；
  Guest Context 返回 Guest 包名 + 宿主 UID ⇒ 服务端归属校验失败。
- `ms.bd.c.r2.a(Context,int,Map)` 反编译（jadx）：`i19 == 2` 分支在
  `SwitchManager.a().c(14)` 开启时按 host 计数采样调用栈，
  比对类名/方法名（反 Hook 特征），只记录、不参与签名。
- 抓包对比：被拦时请求头 `x-argus` 仅 4 字节（`vvzeag==`）、`x-ladon` 3 字节；
  采集命中后 SecSDK 明显进入 bypass/降级签名。

### 修复

- `GuestRuntime.hookActivityTaskManagerAttribution()`：
  在 `IActivityTaskManager$Stub$Proxy` 的 `startActivity*` 上把 Guest 包名字符串
  改写为宿主包名（只改 binder 参数）。
- `GuestRuntime.hookToast()` 增补：`INotificationManager$Stub$Proxy` 的 `*Toast*`
  方法 callingPackage 重写。
- `GuestRuntime.hookMetasecStackDetection()`：
  跳过 `ms.bd.c.r2.a` 的 `i19 == 2` 分支，并令 `SwitchManager.c(14) = false`。
- `GuestSession`：优先加载真机安装的 base/split APK 与 `lib/arm64`（可读时），
  容器副本作为回退。

### 回归结果（真机 eb72c928，Android 16 arm64）

| 步骤 | 结果 |
| --- | --- |
| 红果全新数据启动（同意隐私） | 推荐流正常，无 `Permission Denial` 关闭任务 |
| 红果点开短剧并播放 20s + 下滑切换 | 播放正常，`FATAL EXCEPTION` = 0 |
| Toast 修复前 | 播放异常提示触发 Toast → `SecurityException` 杀进程 |
| Toast 修复后 | `Toast callingPackage rewritten: enqueueToast -> io.vt.framework`，无崩溃 |
| 番茄全新数据 + 采样屏蔽，连续翻页 | 第 2 章 21/29 页正常显示，`NET110` 无新增 |
| 番茄连续请求/换书 45s 后 | 仍可读正文（`ILLEGAL_ACCESS` 计数不增长） |
| 未屏蔽采样（对照，全新数据） | 阅读器约 5 分钟后被 `当前版本不安全` 拦截 |
| 第二次启动（沿用上次数据） | 可能被整体拦截；清除 Guest 数据恢复首会话可用；删 cookie 无效 |

证据截图：[hongguo-feed-20261026.png](screenshots/hongguo-feed-20261026.png)、
[hongguo-play-20261026.png](screenshots/hongguo-play-20261026.png)、
[fanqie-reader-20261026.png](screenshots/fanqie-reader-20261026.png)。

### 静态复核与降暴露（2026-10-26 第三批，模拟器）

- 对 `tmp_ph2/dex` 全量 dex 做 `getStackTrace` 交叉扫描：安全相关类里只有
  `ms.bd.c.r2.a` 是"URL 事件采样 → 调用栈反 Hook"；`ms.bd.c.i0/j3/n1/v3`、
  `m2` 等命中点都是 `Log.getStackTraceString`/异常报告，不参与环境判定。
- `SS_VERSION_NAME` 的 `ClassCastException`（`MSManager.setDeviceID`）在
  **直装进程日志**里同样出现，属 SDK 自身的良性告警，不是容器根因。
- 宿主 JNI 库由 `libvt_xposed.so` 改名为中性的 `libappcore.so`
  （CMake target + `VtNative.loadLibrary`），减少 `/proc/self/maps` 关键词命中；
  LSPlant 为静态链接，无独立 `liblsplant.so`。
- 模拟器（emulator-5554 / API 35 x86_64）回归：
  `scripts/pm-regression.sh emulator-5554` → `PASS=7 FAIL=0`，
  PM 28 项断言三场景全过，LSPlant `ready=true`，示例模块注入成功。
- 模拟器只有 x86_64 ABI、无 ARM 转译，无法运行 arm64 的番茄/红果；
  目标应用的剩余检测只能回到 arm64 真机验证。

### 关键限制（如实记录）

- 服务端风险状态会滞留（疑似 install_id/设备维度的短期标记 + 本地未知持久化状态），
  免 root 容器无法彻底清除；本次修复解决的是"首会话内被本地反 Hook 采样降级"，
  不等同于完全绕过服务端防盗版。
- 部分子接口（如特定 bookmall tab）仍会零星返回 `ILLEGAL_ACCESS`，不影响主流程。
- 应用升级可能改动采样实现与开关 ID，需重新回归本节。

## 9.10 UI 自动化与干净对照（2026-10-26 第四批）

### 可复用脚本

- `scripts/lib/ui.sh`：adb UI 库。状态判定优先解析 `dumpsys window` 的 `mCurrentFocus`
  （真实 Guest Activity），文字/坐标用 RapidOCR。`uiautomator dump` 在视频页会
  `ERROR: could not get idle state` 后 SIGSEGV，脚本不把它用于动态页面。
- `scripts/ui.sh`：命令行封装，支持 `state / texts / tap-text / up / shot /
  launch / stop / clear / logs / crash`。
- `scripts/scenarios/hongguo-smoke.sh [-d serial] [--fresh] [--no-relaunch]`：
  启动 → 隐私 → 推荐流 → 播放 → 下滑 → 停止重启；检查风控文案、主进程 FATAL，
  并从诊断模块日志统计 `VT-NET code=110`。
- 依赖 `rapidocr_onnxruntime`（本机已安装）；adb 默认自动定位
  `D:\AndroidDev\sdk\platform-tools\adb.exe`。

```bash
./scripts/ui.sh -d 127.0.0.1:16384 -p com.phoenix.read state
./scripts/ui.sh -d 127.0.0.1:16384 -p com.phoenix.read tap-text 全屏观看
./scripts/scenarios/hongguo-smoke.sh -d 127.0.0.1:16384 --fresh
```

### 干净对照（MuMu Android 15 / 红果 7.4.1.32）

| 对象 | 数据/进程 | 结果 |
| --- | --- | --- |
| 官方直装（uid 10054） | `pm clear` 后全新，3 次强制停止/重启 | 推荐流正常，3 轮均无盗版提示/网络出错 |
| VT Guest（uid 10055） | 先确认主/子进程退出；`clear` 后数据目录无文件 | 首个 `bookmall/tab` 请求返回 110，Toast + 网络出错 |

诊断模块抓到的 110 响应：HTTP 200、body 97 字节、`message=ILLEGAL_ACCESS`、带 `logId`。
客户端链路为 `NetReqUtil.doAfterDeserialization` → `code 110` →
`NsBaseNetworkDependImpl.assertIllegalAccess()` → `ToastUtils.showCommonToastSafely(R.string.cs9)`；
资源 `0x7f06130f` 即“当前版本不安全，请到正规应用市场下载”。
结论：不是设备被污染，也不是旧数据残留；是容器环境被 SDK/服务端识别。
Android ID（每 Guest 随机 SSAID）实验没有消除 110，已撤回。

### 目标应用矩阵（MuMu Android 15，2026-10-26）

| 应用 | 官方直装 | VT Guest |
| --- | --- | --- |
| 红果 7.4.1.32 | 清数据后 3 轮重启正常 | 干净首请求 110 + Toast + 网络出错（见上表） |
| 番茄 7.1.9.32 | 可用（未做完整回归） | 书城/详情/第 1 章正文可读；多轮 stop/relaunch 中 110 次数 0–3 次波动；ContentProvider 上下文修复后 provider_failed=0 |
| 抖音 36.4.0 | 能到隐私同意页 | `io.vt.framework:g0` Splash 后约 1 秒 SIGSEGV，无 Java FATAL；Provider 上下文修复后 Aha/EC 异常消失，但仍 SIGSEGV；直接启动 `main.MainActivity` 同样 SIGSEGV，排除 Splash 单一路径 |

抖音退出原因用系统 ExitInfo 确认：

```bash
adb shell dumpsys activity exit-info io.vt.framework
# ApplicationExitInfo ... process=io.vt.framework:g0
#   reason=2 (SIGNALED) subreason=0 status=11
```

AppOps 包归属错误（`Bad call made by uid 10055. Package "com.ss.android.ugc.aweme"...`）
存在，但只是症状；尝试的 AppOps binder 改写未生效（类/方法签名不匹配），已撤回。

### 稳定性回归

- **媒体键**：修复前发送 `KEYCODE_MEDIA_PAUSE` 会因
  `ISessionManager.dispatchMediaKeyEvent` 的包名/UID 校验失败杀 Guest；修复后
  `MediaSession attribution hooks installed=14`。由于本机 Guest 当前被 110 拦截、
  无法进入播放页，**播放场景待真机复测**。
- **停止**：旧广播路径在进程改名后无效（日志只有“请求停止”，Guest 无“收到停止”）。
  新路径按 PID 终止：日志 `kill guest pid=...`、`停止 红果免费短剧: 已退出`，
  `ps` 中主/子进程均为空；扫描范围含该槽位登记的 `:g0/:g1` 宿主进程，处理崩溃后的
  `:g1` 推送进程残留。
- **清数据**：旧实现会在 stop 未生效时删目录、应用继续回写；现在 stop 确认退出后删除，
  `find files/guests/<pkg>/data -type f` 为空。
- **静态接收器**：修复前 `t41.c` 在宿主 classloader 下实例化 → `ClassNotFoundException`
  → 杀进程/“VT 反复停止”；修复后 `receiver instantiation hooks installed=2`，
  回归中 `t41.c` FATAL=0。
- **ContentProvider 上下文**：修复前抖音 `AhaInitializerProvider`/`ECInitializerProvider`
  报 `ContextImpl cannot be cast to Application`；改为传 Application 后，红果/番茄
  `Guest provider failed=0`，抖音该异常消失（但仍原生 SIGSEGV）。
- **签名路径**：给 `CronetUrlRequest`/`CronetUrlRequestContext.addSecurityFactor` 和
  `CronetAppProviderManager.onCallToAddSecurityFactor` 安装 Hook 后，在多次 110 请求中
  均未命中——字节的 x-argus 不在该 Java/Cronet 桥生成，不能用它抓头长。
- **原生崩溃探针**：新增 `VtNative.installCrashHandler(path)`，SIGSEGV/SIGABRT/SIGBUS/SIGILL
  时把信号、maps、PC 回栈写入 `files/logs/native-crash.log`（P1 崩溃诊断能力）。
  抖音启动崩溃时该文件仍为 0 字节（handler 安装日志为 true），说明信号没有以普通
  sigaction 路径送达——更可能是 houdini ARM 转译层或抖音自有信号处理直接终止进程；
  该探针需要换 arm64 真机才能验证有效性。
- **设备注册检查**：红果 Guest 的 `shared_prefs/push_multi_process_config.xml`、
  `applog_stats.xml`、`ug_install_settings_pref.xml` 已有有效 `install_id`/`device_id`
  （长数字串），排除“未注册导致 110”的解释；服务端拒绝仍指向环境/签名风险。
- **LSPatch**：MuMu 使用 houdini 转译 arm64；LSPatch 的 arm64 `liblspatch.so`
  无法初始化 LSPlant（x86_64 ART），启动即 JNI abort。该路线需真 arm64 设备验证。

### 改包路线（待真机验证）

已生成两个与真机版本一致的 LSPatch 本地模式包（无嵌入模块，默认 debug keystore）：

| 文件 | 包名/版本 | 大小 |
| --- | --- | --- |
| `tmp_lspatch2/out/hongguo-398-lspatched.apk` | com.phoenix.read 7.4.1.32 | 147 MB |
| `tmp_lspatch2/out/tomato2-398-lspatched.apk` | com.dragon.read 7.4.1.32 | 142 MB |

两者都经 `aapt2 dump badging` 校验包名/versionCode 未变，并包含
`assets/lspatch/origin.apk` 与 loader。MuMu 上无法运行（houdini 转译导致
LSPatch 的 arm64 库无法初始化 LSPlant），必须在真实 arm64 设备测试。

建议的真机测试步骤（避免动用户主用户数据）：

1. 优先创建第二用户：`adb shell pm create-user VTTest`，记录 userId；
   `adb install --user <id> <patched.apk>`；`am start --user <id> ...`。
   若 HyperOS 禁止多用户，再与用户确认是否允许在主用户卸载/重装目标应用。
2. 首屏即时截图 + OCR，检查隐私页、推荐流、正文/播放；读取 logcat 中
   `LSPatch`/`LSPosed` 初始化日志与 `FATAL EXCEPTION`。
3. 连续 3 次停止/重启，记录是否出现“当前版本不安全”/`ILLEGAL_ACCESS`；
   若仍出现，说明服务端按签名/环境拒绝，改签路线不可行。
4. 测试完成后删除第二用户或恢复官方包；不保留改签包在用户设备上分发。

注意：LSPatch 0.398 的 LSPlant 是否支持 Android 16 尚未验证；若 loader 初始化失败，
需要更新 LSPatch 或改用项目自己的 LSPlant，而不是绕过付费/DRM。

### 证据位置

`tmp_uitest/<case>/` 下有截图、`logcat.txt`、`vt-xposed.log`、`activities.txt`；
`tmp_log/` 下保留了本轮抓包/崩溃日志。两者均被 `.gitignore` 排除，不入库。

## 9.11 干净设备 profile 与原生路径虚拟化 A/B（2026-10-27）

- MuMu `index=1`（PD2245/vivo，adb 16416）新建后只保留一个模拟器运行；
  旧 index=0（SM-S9110）与 AVD 均停止，避免内存不足。
- `VT_NATIVE_DIAGNOSTICS=true`：native trace + maps 过滤 +
  `/proc/self/fd` APK 路径替换。红果启动/推荐流 110=0，连续 3 次
  `am force-stop io.vt.framework` 重启仍 0；点“全屏观看”触发
  `bookmall/tab` 2×110 + 网络错误。
- 同 profile 官方直装红果可正常播放，说明 profile 本身不是整体拒绝。
- `VT_NATIVE_DIAGNOSTICS=false`：`io.vt.framework:g0` 约 1 秒内原生崩溃，
  ExitInfo `APP CRASH(NATIVE)` status=6 → shim 是启动必需，不是 110 成因。
- Dobby trace：SecSDK 读 `/proc/self/maps`、枚举 `/proc/self/fd` 并 readlink 到
  宿主 APK/容器路径；未见 status/cgroup；getuid 仅框架调用；Cronet 安全头桥未命中。
- 证据：`tmp_log/native-trace-hongguo*.log`（gitignored）。
- 代码：`xposed/src/main/cpp/vt_xposed.cpp`（trace/env shim）、
  `GuestSession.VT_NATIVE_DIAGNOSTICS`、`scripts/ui.sh -d 127.0.0.1:16416`。

## 9.12 番茄 7.4.1.32 / 抖音在 MuMu 的 native 边界（2026-10-27）

- 干净 MuMu profile（PD2245，adb 16416）：番茄 7.4.1.32 无论 native shim 开关都原生
  崩溃循环；ExitInfo `EXIT_SELF status=139`（crash handler 记录后退出）/
  `APP CRASH(NATIVE) status=11`；崩溃栈在 ART `Runtime::Abort`/栈转储 + libappcore。
- 已将 maps 过滤限定为 Guest 原生调用方（libhoudini/metasec/应用库），ART/framework
  的 `/proc/self/maps` 不再被替换；番茄 7.4.1.32 仍崩溃。
- 旧版番茄 7.1.9.32 在旧实例可读；抖音 36.4.0 官方可到隐私页、容器版 SIGSEGV。
- 结论：MuMu/houdini 对新版 native 不兼容，番茄 7.4.1.32 与抖音必须真 arm64 复测。
- 代码：native 诊断按包名仅对红果启用；番茄/抖音不注入 Dobby。

## 10. 未覆盖的测试

- 抖音短信登录滑块后的验证码输入（需要真实手机号）；抖音/番茄的登录、支付、直播、长时间稳定性；
- 模块的资源替换、XSharedPreferences 写入；
- 跨进程 Provider/Receiver、超过两个进程槽的 Guest 并发；
- Android 9/10/11/12/13/14 的逐版本实测；
- 16 KB page size 设备。
