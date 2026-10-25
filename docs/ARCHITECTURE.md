# 架构说明

## 1. 总体分层

```
┌───────────────────────────────────────────────────────────────┐
│ app（宿主 UI / 管理器）                                        │
│  Compose 应用列表、导入、模块作用域、日志、设置                 │
├───────────────────────────────────────────────────────────────┤
│ container（容器内核）                                          │
│  GuestImporter / GuestStore / ModuleStore / GuestLauncher      │
│  GuestRuntime（ActivityThread、PMS、Context 钩子）             │
│  GuestSession（LoadedApk/Application/Resources/ClassLoader）   │
├───────────────────────────────────────────────────────────────┤
│ xposed（Xposed 运行时）                                        │
│  de.robv.android.xposed API 82                                 │
│  XposedBridge → Hooker → LSPlant（源码）→ Dobby                │
│  MiniDebugInfo 符号解析（xz-embedded）                         │
├───────────────────────────────────────────────────────────────┤
│ Android Framework（系统进程 / ActivityThread / AMS / PMS）      │
└───────────────────────────────────────────────────────────────┘
```

宿主进程（`io.vt.framework`）只负责 UI 和管理；Guest 只能在 `:g0` / `:g1` 容器进程运行，避免容器/PMS Hook 污染宿主界面。

## 2. 导入模型

### 2.1 数据布局

```
files/
  vt_guests.json                      # GuestRecord 列表
  vt_modules.json                     # 已识别 Xposed 模块
  logs/xposed.log                     # 宿主与所有 guest 进程共享日志
  module_prefs/<module>/               # XSharedPreferences 镜像（只读）
  guests/<guestId>/
    apk/base.apk, split_*.apk         # 只读，DexClassLoader 要求
    lib/*.so                          # 设备 ABI 的原生库
    oat/                              # dex 优化输出
    data/                             # Guest dataDir（files/cache/db/prefs）
    icon.png
```

`GuestRecord` 保存：原始包名、版本、入口 Activity、APK/so 路径、原始签名（base64）、ActivityInfo 表、ApplicationInfo 字段、模块作用域、自动更新开关。JSON 存储避免引入 Room，减小体积。

### 2.2 更新

`GuestImporter.updateFromDevice()` 比较真机 `versionCode`：

1. 重新读取 `PackageInfo`，复制新 base/split APK（复制后 `setReadOnly()`）；
2. 重新复制原生库、图标、签名和组件表；
3. **不触碰 `data/`**；保留 `enabledModules` 与 `autoUpdate`；
4. Guest 下次启动时 `GuestSession` 使用新 APK 路径，数据目录不变。

## 3. 启动链路

### 3.1 宿主侧

`GuestLauncher` 检查两个进程槽的存活/占用情况，构造显式 Intent：

```
component = io.vt.framework/io.vt.container.stub.StubStandard<slot>
extras    = { vt.guest.id, vt.guest.class, ... }
flags     = NEW_TASK
```

`VtApplication` 在 `:g0` / `:g1` 中识别进程名，调用 `GuestBootstrap.bootstrap()`：

1. `XposedRuntime.init()`：加载 `libvt_xposed.so`，初始化 LSPlant；
2. `GuestRuntime.installHooks()`：安装容器钩子；
3. 等待系统投递 `LaunchActivityItem`。

### 3.2 `performLaunchActivity` 虚拟化

`GuestRuntime.hookPerformLaunchActivity()` 在方法执行前：

- 从 `ActivityClientRecord.intent` 读取 `vt.guest.*` extras；
- 创建/复用 `GuestSession`；
- 替换：
  - `record.packageInfo` → Guest `LoadedApk`
  - `record.activityInfo` → 由导入时保存的 `StoredActivityInfo` 重建
  - `record.intent` → 指向 Guest Activity 的 Intent
- 系统随后按正常流程：
  - 用 Guest `LoadedApk.getClassLoader()` 加载 Guest Activity；
  - 用 Guest `LoadedApk` 创建 Guest `ContextImpl`；
  - 调用 `makeApplication()` 创建 Guest `Application`；
  - 调用 `Activity.attach()` / `onCreate()`。

在进入上述流程之前，VT 还会：
- **显式创建 Guest Application**：反射调用 `LoadedApk.makeApplication()`/`makeApplicationInner()`，使 Application
  的 `attach/onCreate` 先于首个 Activity 构造执行。真实 Android 在进程启动时已创建 Application，很多应用在
  Activity 构造函数中直接访问 `Application` 静态单例（Telegram 即如此）；此后 backup 中的 `makeApplication`
  会复用已创建的实例；
- **解析 activity-alias**：启动入口可能声明为 `<activity-alias>`（如 Telegram 的 `DefaultIcon`）。导入时写入
  `StoredActivityInfo.targetActivity`，启动时解析成真实 Activity 类；
- **提前加载模块**：在 Activity 构造前完成模块 Hook 安装。

### 3.3 GuestSession 构造

- `DexClassLoader`：base+split APK，优化目录 `oat/`，原生库目录 `lib/`，父加载器为 **BootClassLoader**，避免 Guest 依赖解析到宿主同名库；
- `Resources`：`AssetManager.addAssetPath()` 逐个加入 base/split，使用宿主 DisplayMetrics/Configuration；
- `ApplicationInfo`：改写 dataDir/nativeLibraryDir/sourceDir/split 数组，UID 使用宿主 UID；
- `LoadedApk`：反射 `ActivityThread.getPackageInfoNoCheck()`（失败时尝试 `LoadedApk` 构造器），再写入 `mClassLoader` / `mResources`；
- Guest `PackageInfo`：原始签名、版本、ApplicationInfo、Activity 表。

### 3.4 模块加载时机

`performLaunchActivity` before 钩子在创建 Guest Activity 之前调用 `GuestSession.loadModulesOnce(null)`，因此：
模块的 `handleLoadPackage` 先于 **启动 Activity 的构造方法** 与 Guest `Application` 创建执行，构造方法 Hook 可以命中首个 Activity；
Guest 的 `LoadedApk`/`ApplicationInfo` 已在 `ensureSession()` 中准备好，模块可用 Guest ClassLoader 查找类。
`Instrumentation.callApplicationOnCreate()` 仍保留一次 `loadModulesOnce(app)` 调用作为兜底（已加载则直接返回）。

## 4. 容器钩子清单

| Hook | 作用 |
| --- | --- |
| `ActivityThread.performLaunchActivity` | 替换记录、LoadedApk、ActivityInfo、Intent；保留 activity-alias 组件语义；提前创建 Guest Application |
| `Instrumentation.execStartActivity` | 把 Guest Activity 路由到宿主 Stub（按目标进程选择 :g0/:g1）；Activity 调用方保留为结果接收者，仅非 Activity 上下文替换为宿主 |
| `Instrumentation.newActivity` | alias 入口实例化时映射到 `targetActivity` |
| `Activity.onActivityResult` | 记录 Guest 结果回调，验证登录/滑块等流程 |
| `Instrumentation.newApplication` | 捕获 Guest Application |
| `Instrumentation.callApplicationOnCreate` | 在 onCreate 前加载模块 |
| `Activity.onNewIntent` | 还原 nested Guest Intent |
| `ActivityThread.currentPackageName/currentProcessName/currentApplication` | Guest 身份 |
| `ActivityThread.acquireProvider` | Guest Provider 返回本地 Transport；系统 Provider 用宿主 Context 请求 |
| `ContextImpl.startService/startForegroundService/bindService/unbindService/stopService` | Guest Service 本地生命周期 |
| `ContextImpl.registerReceiver/unregisterReceiver` | Receiver 注册到宿主 Context |
| `ContextImpl.sendBroadcast*` | Guest action 的广播经宿主发送并包定向 |
| `Service.startForeground/stopForeground/stopSelf*` | Guest 前台通知：宿主身份发布/取消真实通知；stopSelf 延迟结束本地 Service |
| `ContextImpl.getOpPackageName/getBasePackageName/getAttributionSource` | Binder attribution 尽可能使用宿主 |
| `ContextImpl.getContentResolver` | 尝试返回宿主 resolver（仍有限制） |
| `ApplicationPackageManager.getPackageInfo/getApplicationInfo/getActivityInfo/checkSignatures/...` | Guest 包信息与原始签名伪装 |
| `ContextImpl.getContentResolver` + `mPackageName`/`mAttributionSource` patch | 系统 Provider 使用宿主 attribution；Guest 自有 Provider 仍本地路由 |
| `ContextImpl.getExternalCacheDir/getExternalFilesDir/getObbDir/...` | Guest 外部存储重定向到私有 `external/` 目录 |
| `NotificationManager.*` | Guest 通知管理器的实例方法转发到宿主 NotificationManager，包名参数替换为宿主 |
| `Toast$TN.<init>` | TN 的包名与 Context 替换为宿主，Toast 窗口不再被 WindowManager 拒绝 |
| `LocaleManager.get/setApplicationLocales` | Guest 内返回空 LocaleList / no-op，规避系统权限校验 |
| `PendingIntent.getActivity/getActivities/getBroadcast/getService/getForegroundService` | 用宿主 Context 创建；Activity 目标改写为占位 Activity + 原始 Intent |
| `MediaSessionManager.createSession` | 调用前把管理器 `mContext` 替换为宿主，使 SessionManagerService 校验通过 |
| `JobScheduler` / `JobSchedulerImpl` | 返回成功的空实现，WorkManager 启动链路可通过 |
| `Activity.requestPermissions` / `shouldShowRequestPermissionRationale` | 按宿主真实授权回调 Guest，避免 guest 包名的权限弹窗校验 |

## 4.1 组件虚拟化（Provider / Service / Broadcast / Native）

- **Provider**：导入时保存 `<provider>` 的 authority/class/permission；`callApplicationOnCreate` 之后用 Guest ClassLoader 实例化，`attachInfo()` 触发 `onCreate`，把 `IContentProvider` Transport 缓存到 authority 表。`ActivityThread.acquireProvider` 先查表命中则直接返回本地 Transport（不再经过 AMS）；未命中的系统 Provider 用宿主 Context 请求，并对 Guest Context 的 `ContentResolver.mPackageName`/`ContextImpl.mAttributionSource` 做宿主化，修复 SettingsProvider 的包-UID 校验。
- **Service**：不经过 AMS，直接实例化 Guest Service，按 Android 15 的 `Service.attach(Context, ActivityThread, String, IBinder, Application, Object)` 反射挂载 Guest Application；`startService` 调用 `onStartCommand`，`bindService` 调用 `onBind` 并同步回调 `ServiceConnection.onServiceConnected`，`stopService/unbindService` 维护生命周期。Service 与主进程同 UID/同进程，跨进程语义未实现。`startForeground` 由宿主 NotificationManager 发布真实通知（详见 4.3），`stopSelf` 延迟排队结束本地实例。
- **Broadcast**：Guest `registerReceiver` 改为在宿主 Context 注册同一 Receiver 对象；`sendBroadcast*` 由宿主发送。对 Guest 已注册的 action 自动 `setPackage(host)`，否则系统在 enqueue 阶段会因 Guest 包未运行而直接 finish（已实测）。
- **静态 Receiver**：导入时用内置 AXML 解析器读取 base APK 的二进制 `AndroidManifest.xml`，提取 `<receiver>` 的 action/category/exported/process；App onCreate 后实例化并注册到宿主 Context。声明在子进程的 Receiver 暂跳过。
- **Native**：导入时从 `nativeLibraryDir` 或 APK 的 `lib/<abi>/` 复制 `.so`；`DexClassLoader` 的 librarySearchPath 指向 Guest lib 目录，`System.loadLibrary` 可由 nativeloader 创建 isolated namespace 加载（已用 JNI 示例验证）。

## 4.2 系统服务身份兼容（NewPipe 实测补充）

真实应用启动路径会连续触发多处“包必须属于调用 UID”的校验，而 Guest 包并未安装。VT 的处理原则是：
**能本地虚拟化的组件保持 Guest 语义；必须经过系统服务的调用改用宿主身份或显式空实现。**

- **外部存储**：`getExternalCacheDir/getExternalFilesDir/...` 在 before Hook 中直接返回
  `<guest dataDir>/external/...`，避开 `StorageManager.getVolumeList`；
- **通知**：Guest `NotificationManager` 的所有实例方法转发到宿主管理器，`notifyAsPackage` 等包名参数替换为宿主；
  通知渠道因此注册在宿主包下，跨 Guest 可能共用同名 channel；
- **语言**：`LocaleManager.setApplicationLocales` 在 Guest 内 no-op，`getApplicationLocales` 返回空列表；
- **PendingIntent**：创建时 Context 替换为宿主，避免 `getIntentSender` 拒绝；Activity 目标改写为宿主占位 Activity
  并携带原始 Intent，广播/Service 目标保留 Guest 组件（系统投递时因包未安装会被丢弃）；
- **MediaSession**：`MediaSessionManager.createSession` 前把 `mContext` 换成宿主，Session 注册在宿主包下；
- **JobScheduler**：`JobSchedulerImpl` 的 schedule/enqueue/cancel/查询返回成功空值；WorkManager 初始化不再中断，
  但任务不会真正执行。这是启动兼容层，不是完整调度实现；
- **日志**：`XposedLog` 按 4 MB 轮转为 `xposed.log.1`，避免长期运行写满磁盘；不再按每次
  `getContentResolver` 打印日志。

## 4.3 前台服务通知虚拟化

Guest Service 没有 AMS 登记的 ServiceRecord/token，直接调用 `Service.startForeground` 会因 token 为空失败。VT 在
`Service.startForeground` 的 before Hook 中拦截 Guest 实例：

1. 用 **宿主 NotificationManager** 发布通知，tag 使用 `vt:<guestPackage>:<serviceClass>`，id 保留 App 传入值；
2. 通过反射把 `Notification.mSmallIcon` 替换为宿主应用图标（`Icon.createWithResource(host, ...)`），
   因为 Guest 资源包没有安装，SystemUI 无法解析原始 icon；
3. 若通知没有 channel 或 channel 在宿主下不存在，则创建 `vt_guest_default`（low importance）；
4. Notification 的 contentIntent 已在创建时被 PendingIntent Hook 改写为宿主 PendingIntent，点击可回到 Guest Activity；
5. `foregroundNotificationIds[serviceClass]` 记录 id，供 `stopForeground`/`stopSelf`/容器停止时取消；
6. `stopForeground(false)` 保留通知，`stopForeground(true)` 取消；停止容器时 `ControlReceiver` 调用
   `cancelAllForegroundNotifications()` 后结束进程。

限制：系统只看到一个普通通知，没有前台服务 oom adj、5 秒启动窗口和媒体通知模板；通知图标/渠道跨 Guest 共用宿主身份。

## 4.4 Guest 多进程 Activity

导入时 `StoredActivityInfo.processName` 保留原始声明。启动 Guest Activity 时：

1. `GuestRuntime.hookStartActivity` 读取目标 Activity 的声明进程；
2. `GuestSession.targetSlotForActivity()` 选择占位进程槽：主进程 → 当前主槽；子进程 → 对端子槽
   （目前只有 `:g0`/`:g1`，因此最多一个额外子进程）；
3. `stubIntentFor()` 在 Intent 中写入 `vt.guest.process`，并指向 `StubStandard<slot>`；
4. 子进程启动时 `ensureSession` 用该 process 创建独立 `GuestSession(slot=1)`：独立 Guest Application、
   ClassLoader、模块加载与静态 Receiver/Provider 过滤；数据目录共享；
5. 返回栈仍由系统 ActivityTask 管理，子进程 Activity 可与主进程 Activity 同任务；
6. `GuestLauncher.stop` 先用 `getAppTasks()` 按 `vt.guest.id` 对占位任务执行 `finishAndRemoveTask()`，
   `ControlReceiver` 停止指令再广播到已分配及未分配槽，各进程核对 Guest id 后退出。

限制：没有跨进程 Provider/Service Binder 代理；只有两个槽；同一 Guest 的多个子进程暂不支持。

## 4.5 启动时序：Application 与 activity-alias

真实 Android 进程在 `handleBindApplication` 阶段创建 Application，之后才会启动 Activity；容器早期只在
`performLaunchActivity` 的 backup 中依赖系统 `makeApplication`，这会让 Application 晚于 Activity 构造函数。
Telegram 等应用在 Activity 构造函数里访问 `ApplicationLoader.applicationContext`，因此会 NPE。现在的时序：

1. `performLaunchActivity` before：创建/复用 GuestSession，加载模块；
2. `GuestSession.ensureApplication()`：通过 `LoadedApk.makeApplication()` 创建 Guest Application，触发
   `Application.attach/onCreate`、ContentProvider 安装、静态 Receiver 注册；
3. 系统 backup 继续 `Instrumentation.newActivity()`：Activity 构造时 Application 已存在；
4. backup 内的 `makeApplication` 复用同一实例；`Activity.attach/onCreate` 正常执行。

导入时若启动入口是 `<activity-alias>`（`ActivityInfo.targetActivity != null`），**不再把 alias 改写成目标类**。
应用经常根据启动组件的类名判断“是否从闪屏进入”，改写成目标类会跳过闪屏初始化（番茄系因此出现 HTTP/Cronet
未初始化）。现在的处理：

1. 导入记录保留 alias 作为 `launcherActivity`，同时在 `activities[alias].targetActivity` 保存真实类；
2. `stubIntentFor()` 写入 `vt.guest.class=<target>` 与 `vt.guest.component=<alias>`；
3. `performLaunchActivity` 把 Guest Intent 的 component 设为 alias，`ActivityInfo` 也用 alias 的属性；
4. 新增 `Instrumentation.newActivity` Hook：实例化时把 alias 类名换成 `targetActivity`，Activity 的
   `getIntent().getComponent()` 仍是 alias，与真实系统启动 alias 的行为一致；
5. 应用内部再次启动 alias 时同样保留 alias 语义（`hookStartActivity` 传入请求类而不是解析后的目标类）。

## 4.6 Android 16 / arm64 真机兼容

- **宿主进程不初始化 LSPlant**：只有 Guest 进程（`:g0`/`:g1`）加载 native Hook；管理 UI 进程保持干净，
  避免 MIUI 资源类解析命中 Dobby trampoline 导致 SIGILL。
- **Dobby far trampoline**：关闭 `dobby_enable_near_branch_trampoline()`，Android 16 arm64 的 near
  trampoline 会在类校验线程触发 `SIGILL`。
- **Android 16 跳过部分 LSPlant 内联 Hook**：SDK >= 36 时跳过 `MarkVisiblyInitialized` /
  `AdjustThreadVisibilityCounter` 与 `RegisterNative`/`UnregisterNative` 组。这些 Hook 在 Android 16
  的 ART/JNI 布局下会分别导致类可见初始化 SIGSEGV 与 WebView JNI 解析 SIGSEGV；VT 不提供 Native Hook
  API，因此跳过不影响现有模块能力。
- **进程与权限身份**：补充宿主普通权限（网络/蓝牙/唤醒等）；`Application.getProcessName` 与
  `Process.myProcessName` 返回 Guest 进程名；`getHistoricalProcessExitReasons` 对 Guest 返回空；
  `StorageManager`/`Environment` 的卷查询重定向到宿主；File 构造器的 null 路径做兜底并记录日志。
- **启动时序**：Application 先于 Activity；Provider 在 Application.onCreate 前安装；activity-alias 保留组件语义、
  实例化时映射目标类（抖音/番茄/Telegram 均按真实 launcher 启动）。
- **Activity 结果回调**：`Instrumentation.execStartActivity` 仅把 **非 Activity** 上下文替换为宿主；Guest Activity
  继续作为 `onActivityResult` 的接收者。包名归属由 `getBasePackageName/getOpPackageName/attributionSource`
  伪装承载，不能靠替换 Activity 实现，否则登录/滑块类流程收不到结果。
- **Toast**：`Toast$TN` 构造器的包名字符串与 Context 都替换为宿主；否则 `ToastPresenter` 用 Guest 包名设置
  `WindowManager.LayoutParams.packageName`，WindowManager 以 UID 不匹配拒绝并杀死进程。
- **番茄系初始化**：在 Guest Application 创建后补做 RPC `g4.c(Application)`、TTAccount `p5.b(Application)`；
  `base.http.k.b` 为 false 时在后台调用 `h1.e(Application)` 初始化 TTNet/Cronet。正常从 Splash 启动时应用也会
  自己执行这些任务，兜底只用于启动任务被跳过或版本差异。
- **跨进程 Service**：`ContextImpl.startService/bindService` 对声明在其他进程的 Guest Service 构造宿主
  `StubGuestService<slot>` Intent，由目标进程 `StubGuestService` 创建 GuestSession/Application 并转发
  start/bind；Binder 直接透传，示例与抖音 Push/WsChannel 已使用。

## 4.7 真机身份一致性（best-effort）

目标是让应用看到的“自身安装/进程/包信息”自洽，而不是仅替换包名：

1. 导入时保存真实安装路径（`sourceDir`、split、`nativeLibraryDir`）、安装/更新时间、安装器；
   `ApplicationInfo` 与 `Context.getPackageCodePath/getPackageResourcePath` 返回真实路径；
2. `setArgV0` + 写 `/proc/self/comm` 让 `ps`/`/proc` 看到 Guest 进程名；
3. `getRunningAppProcesses` 把宿主进程条目替换为 Guest 进程；
4. Guest 的 `PackageManager` 视图中隐藏 `io.vt.framework`；查询宿主包抛 `NameNotFoundException`；
   `getPackagesForUid(myUid)`/`getNamesForUid` 只返回 Guest 包；
5. `Toast$TN` 使用宿主身份，宿主允许 cleartext（字节系部分 CDN 走 HTTP）。

仍无法自洽的部分：Guest 数据目录位于宿主私有目录、内核 UID 是宿主 UID、`/proc/self/maps` 包含
`libvt_xposed.so` 与容器路径、LSPlant 修改了 ART entrypoint。这些是免 root 容器的结构性痕迹。

## 4.8 边界：服务端防盗版

番茄/红果正文接口（`reading/reader/full/v`、`batch_full/v`）返回 HTTP 200 + 业务码 110
`ILLEGAL_ACCESS`，应用映射为“当前版本不安全”。原版应用同设备匿名可读，说明是服务端对容器环境的
防盗版/风控判定，不是本地可关闭的弹窗。VT 不会伪造内容授权/绕过付费或 DRM；需要受保护内容时应使用
官方应用，模块注入场景建议使用 root + LSPosed 以保留真实 UID/数据目录。

## 5. Xposed 运行时

### 5.1 为什么用源码构建 LSPlant

Maven 上的 `org.lsposed.lsplant:lsplant:6.4` 停留在 2024 年，在 Android 15 的 ART 上实例方法 Hook 的 backup 方法形态不兼容（启动 Guest Activity 时出现 `null receiver`）。仓库内置 `.ref` 之外的 `xposed/src/main/cpp/third_party/lsplant` 为 2026 年 master 源码，使用 NDK 30 + CMake 3.30 + Ninja 1.12 构建，解决了该问题。

### 5.2 Hook 分发

```
XposedBridge.hookMethod(Member, XC_MethodHook)
  → Hooker（每个 Member 一个）
  → VtNative.hook() → lsplant::Hook()
  → LSPlant 生成 VTHooker_ 类（hook + backup）并替换 ART entrypoint
  → 方法被调用 → Hooker.callback(Object[])
  → before callbacks（优先级降序）
  → 调用 backup（实例方法带 receiver）
  → after callbacks（逆序）
  → 返回/抛出
```

模块侧回调是 legacy `XC_MethodHook`；`XposedHelpers` 原样兼容 API 82 的反射工具。

### 5.3 符号解析

Release ART 的 `libart.so` 通常没有 `.symtab`，只有压缩的 `.gnu_debugdata`（MiniDebugInfo）。VT：

1. 通过 `dl_iterate_phdr` 找到 libart 文件与 load bias；
2. 用内置 **xz-embedded** 解压 `.gnu_debugdata`；
3. 解析 mini ELF 的 `.symtab`，并合并 `.dynsym`；
4. 为 LSPlant 提供精确/前缀符号解析；
5. 对 Android 15 移除的 `JitCodeCache::GarbageCollectCache` 别名到 `DoCollection`；
6. 用 Dobby 完成 ART 内部函数的 inline hook。

没有使用系统 `liblzma.so`：Android 7+ 的应用命名空间不允许直接加载它。

## 6. 进程与并发

- 每个 `:gN` 进程一次只承载一个 Guest；`GuestRuntime.session` 是进程级单例。
- 槽位选择：`ActivityManager.getRunningAppProcesses()` 检查 `io.vt.framework:g0/g1`；同 Guest 复用，不同 Guest 占用另一个槽；两槽都忙则拒绝。
- 停止：显式广播到对应槽的 `ControlReceiverN`，Receiver 结束该进程；宿主清理槽位记录。
- Guest 内切换 App（不同 import）会拒绝并记录，避免一个进程混载两个 Guest 的 ClassLoader/模块。

## 7. 安全边界与已知缺陷

- Guest 与宿主同 UID，Guest 可访问宿主的私有文件；本原型默认信任导入的应用。
- Binder attribution 只做了部分宿主化；Guest 自带 Provider/Service、跨进程、部分系统服务仍会失败。
- 未做反调试/反容器；不能视为生产级隔离方案。
- 资源 Hook、XSharedPreferences 写入、libxposed v2 API 未完成。
