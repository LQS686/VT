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

## 10. 未覆盖的测试

- 抖音短信登录滑块后的验证码输入（需要真实手机号）；抖音/番茄的登录、支付、直播、长时间稳定性；
- 模块的资源替换、XSharedPreferences 写入；
- 跨进程 Provider/Receiver、超过两个进程槽的 Guest 并发；
- Android 9/10/11/12/13/14 的逐版本实测；
- 16 KB page size 设备。
