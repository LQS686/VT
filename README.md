# VT 框架

免 root、免修改安装包的 Xposed 模块容器（Android 9–16 原型）。

> 当前版本 **0.1.0**，是一个可运行的原型，不是太极/LSPosed 的完整替代品。
> 已在 Android 15（x86_64 模拟器）与 **Android 16 arm64 真机（HyperOS）** 上验证：
> **抖音 40.7.0** 可导入、进入推荐流并播放视频，Push/WsChannel 跨进程 Service 由容器 Stub 启动；
> **红果免费短剧 7.4.1.32** 可自然启动、浏览推荐流并播放短剧；**番茄小说 7.4.1.32** 可启动、
> 浏览书城推荐流与书籍详情，但打开章节正文仍会收到服务端 `ILLEGAL_ACCESS`（code 110）的
> “当前版本不安全”防拷页——这是服务端防盗版校验，无 root 容器无法可靠绕过。详见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)。
> **NewPipe 0.29.1** 主界面/设置页/PlayerService，**Telegram 13.1.0** 引导页/手机号页/Proxy Settings 均可用。
> 抖音短信登录的容器侧 `onActivityResult` 丢失已修复，滑块后的验证码页仍待真实手机号复测。

## 它解决什么

TaiChi 类工具通常需要 root，或者要在安装前重打包 APK。VT 采用另一种路线：

1. 把真机已安装应用的 base/split APK、原生库、原始签名、组件表导入到框架私有目录；
2. 用 **ActivityThread 级虚拟化** 在框架自己的进程中创建 Guest `LoadedApk`、`Application`、`Resources`、`ClassLoader` 和 `Context`；
3. 用 4 个占位 `Activity`（2 个进程槽 × 4 种 launchMode）承接系统任务栈，在 `performLaunchActivity` 中替换为真实 Guest Activity；
4. 在 Guest `Activity` 实例化与 `Application.onCreate` 之前加载用户勾选的 Xposed 模块；
5. 真机应用更新后，重新复制新 APK，保留容器数据目录与模块作用域。

原安装包始终不被修改；无需 root。

## 已实现（有真机/模拟器证据）

- **应用导入**：从已安装应用复制 base + split APK，复制原生库、图标、Activity 表和原始签名；隐藏系统应用和游戏。
- **应用更新**：按真机版本号检测更新；导入到同一数据目录，保留已启用模块；支持 `autoUpdate` 启动前更新。
- **Xposed 模块管理**：扫描已安装模块（`xposedmodule` 元数据或 `assets/xposed_init` / `META-INF/xposed/java_init.list`），支持按应用启用/停用、模块 APK 文件导入。
- **Guest 运行时**：
  - Guest `LoadedApk` / `Application` / `Resources` / `DexClassLoader` / `ContextImpl`；
  - `ActivityThread.performLaunchActivity` 意图、ActivityInfo、LoadedApk 替换；
  - 多 Activity、`startActivity` / `startActivityForResult`、返回栈；
  - **Guest ContentProvider**：导入后在本进程安装，`ActivityThread.acquireProvider` 本地路由到 Provider Transport；
  - **系统 Provider 宿主身份**：Patch Guest Context 的 ContentResolver package/attribution，Settings/PhoneWindow 等系统读取不再因 UID/包名校验崩溃；
  - **Guest Service**：`startService` / `startForegroundService` / `bindService` / `unbindService` / `stopService` 本地生命周期；
  - **广播**：动态 Receiver 通过宿主 Context 注册；导入时用内置 AXML 解析器读取 Manifest 静态 Receiver 的 action，并在启动时注册；已注册 action 的广播使用包定向投递；
  - **通知 / 语言 / 媒体会话**：Guest 的 `NotificationManager`、`LocaleManager`、`MediaSessionManager.createSession`
    统一重映射到宿主身份；`PendingIntent` 改用宿主创建（Activity 目标改走占位 Activity）。
  - **运行时权限**：Guest `requestPermissions` 按宿主真实授权自动回调（宿主已授权的返回 GRANTED，
    否则 DENIED），不弹 Guest 权限框、不触发包归属崩溃。
  - **前台服务通知**：Guest `startForeground` 以宿主身份发布**真实通知**（重写小图标/渠道，保留标题、
    文本和 PendingIntent 点击回跳 Guest）；`stopForeground` / `stopSelf` 取消通知并结束 Guest Service；
    停止容器时清理残留通知。注意：这不是 AMS 登记的前台服务，没有 oom 提升和 5 秒规则。
  - **真实应用验证**：NewPipe 0.29.1 主界面、抽屉、设置页、多 Activity、PlayerService 创建均通过；
    Telegram 13.1.0 启动页、手机号页、Proxy Settings、原生库加载与运行时权限拒绝回调均通过；
    抖音 40.7.0 在 Android 16 arm64 真机经真实 Splash alias 进入推荐流并播放视频，跨进程 Push/WsChannel
    Service 可启动；番茄小说 7.4.1.32 进入书城推荐流；红果免费短剧 7.4.1.32 进入推荐短剧流并全屏播放；
    示例模块在抖音内命中 `MainActivity.onCreate` 并显示 “VT MODULE ACTIVE” 浮层。
  - **Android 16 / arm64 原生兼容**：关闭 Dobby near-branch trampoline；Android 16 跳过 LSPlant 的
    `MarkVisiblyInitialized` 与 `RegisterNative` 内联 Hook；宿主管理进程不再初始化 LSPlant。
  - **真机身份与权限**：`Application.getProcessName`/`Process.myProcessName` 伪装、Application 先于 Activity、
    Provider 先于 onCreate、补充宿主普通权限、StorageManager/Environment 重定向、File null 路径兜底、
    Toast 窗口包名/Context 宿主化（避免 `Package ... not in UID ...`）。
  - **模块热更新兼容**：模块 APK 被重新安装/更新后，Guest 会通过 PackageManager 回退到当前 APK 路径，
    不再因模块路径失效而跳过加载。
  - **启动时序与 alias**：Guest Application 在首个 Activity 实例化之前创建（与真实进程启动一致）；
    activity-alias 保留组件语义、实例化时映射 `targetActivity`，应用仍能判断“从闪屏进入”并执行启动初始化。
  - **Activity 结果回调**：`startActivityForResult` 的 Activity 调用方不再被替换为宿主 Application，
    示例 v17 验证 `req=1001 res=-1` 与 `result-ok` 正常返回（修复登录/滑块流程的结果丢失）。
  - **字节系初始化兜底**：番茄小说/红果短剧的 RPC `g4.c`、TTAccount `p5.b`、HTTP/TTNet `h1.e` 在
    Guest Application 创建后按需执行，避免 `CronetEngine has not been initialized`。
  - **真机身份一致性（best-effort）**：使用真实安装 APK/原生库路径、codePath/resourcePath、安装/更新时间；
    进程 cmdline/comm 与 `getRunningAppProcesses` 返回 Guest 身份；从 Guest 的 PM/UID 包列表中隐藏容器包；
    宿主开启 cleartext 以兼容字节系 CDN。仍无法隐藏的数据目录/内核 UID/maps/Xposed 痕迹见兼容性文档。
  - **PackageManager 视图自洽（2026-10-26）**：示例应用内置 `PmAudit`（**27 项**交叉比对断言），
    对照真机语义逐项校验。修复前基线 `passed=10 failed=7`，逐项定位并修复了 7 处确定缺陷：
    宿主包隐藏此前因异常被 Hooker 回滚而**从未生效**；`getNameForUid` 未接管导致 UID 反查暴露宿主包名；
    已安装列表与直接查询返回两套 UID/dataDir；flags 语义错误导致签名被无条件返回且新版重载恒为 0；
    `ApplicationInfo` 多字段硬编码、`versionCode` 被截断。修复后容器/普通/真机已卸载三种场景
    均 `passed=17 failed=0`。详见 `docs/TESTING.md` 第 9.7 节。
  - **PM 查询路径盲区（2026-10-26）**：复核时用 `dexdump` 反汇编 `framework.jar` 逐个确认查询入口的
    最终实现路径，发现 `PackageManager.getPackageInfoAsUserCached`（静态）经 `PropertyInvalidatedCache`
    直连 PMS binder，**完全绕过 `ApplicationPackageManager`**——此前所有实例方法 hook 一个都拦不住它。
    后果是目标应用真机卸载后 Guest 启动即崩（`is package not installed?`），且该路径泄漏 PMS 真实
    UID/dataDir 与实例方法返回的容器视图矛盾。第二批又修掉 9 处同族缺陷，其中根因是
    `Hooker.java:152-174` 的语义：`after` 回调里 `param.result = X` 不清 `throwable`，
    原方法抛过异常时改写结果会被丢弃（需 `before` + `setResult`）。
    另有 `before` 回调不能靠 `throw` 短路：`Hooker.invoke()` 会**回滚并放行原方法**
    （`returnEarly=false`），目标应用真机已安装时严格语义直接失效 —— 该问题只在真机上暴露。
    断言扩到 **28 项**，Android 15 模拟器与 Android 16 真机（eb72c928）三场景均 `passed=28 failed=0`。
    详见 `docs/TESTING.md` 第 9.8 节。
  - **稳定性修复（2026-10-26 第四批）**：补齐媒体键 `ISessionManager$Stub$Proxy` 归属改写
    （修复前按暂停键会杀 Guest；Hook 已安装，播放场景待真机复测）；
    `stop` 在进程改名后用 PID 直接终止并等待退出；
    `clear` 确认进程退出后再删数据；静态 Receiver 在 `AppComponentFactory` 前用 Guest
    classloader 实例化，修复 `t41.c` ClassNotFound 导致的“VT 反复停止”；
    ContentProvider 按真实 Android 语义传 Application，修复抖音 Aha/EC
    `ContextImpl cannot be cast to Application`（红果/番茄 provider_failed=0；
    抖音该异常消失但仍在 Splash 后原生 SIGSEGV）。
  - **子进程 Activity**：导入时保留 Activity 的 `processName`；启动时按目标进程选择 `:g0`/`:g1` 占位
    Activity，子进程独立创建 Guest Application 并加载模块；停止容器会移除占位 Activity 任务并同时结束主/子进程。
  - **原生库**：导入时复制 .so，Guest DexClassLoader 原生库搜索路径可 `System.loadLibrary`；
  - 独立数据目录、缓存、oat、原生库目录；
  - 签名/包信息伪装（`getPackageInfo`、`checkSignatures`、`getLaunchIntentForPackage` 等）；
  - `:g0` / `:g1` 两个容器进程槽。
- **Xposed 运行时**：
  - 从源码构建的最新 LSPlant（支持 Android 5–17 的 ART Hook）+ Dobby 内联 Hook；
  - 兼容 legacy Xposed API 82：`XposedBridge`、`XposedHelpers`、`XC_MethodHook`、`IXposedHookLoadPackage`、`IXposedHookZygoteInit` 等；
  - 模块加载在 Guest `Application` 创建之前完成（先于启动 Activity 实例化，启动 Activity 的构造方法 Hook 也能命中）；
  - 实例方法 / 静态方法 / 构造方法 Hook（NewPipe 的 `MainActivity.<init>` 与 `onCreate` 均已验证）；
  - `MiniDebugInfo`（`.gnu_debugdata`）符号解析，解决 release ART 无 `.symtab` 的问题；
  - `XSharedPreferences` 只读镜像、模块日志。
- **工程化**：Compose UI、应用/模块/日志/设置四个页面、Room 未引入（JSON 存储，轻量）、示例应用、示例模块、Adb 调试控制 Activity（仅 debug）。

## 快速开始

### 环境

本仓库的构建环境已在 Windows 上验证：

| 组件 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK | Platform 35 / Build-Tools 35.0.0 |
| Android NDK | **30.0.16248370** |
| CMake | **3.30.5** |
| Ninja | **≥ 1.11**（重要：C++20 modules 需要） |
| Gradle | 8.9（wrapper 已包含） |

> NDK/CMake 版本不可随意降低：LSPlant 使用 C++23 modules，CMake 3.30 和 Ninja 1.11+ 是硬性要求。
> 如果 Android SDK 自带的 CMake 3.30.5 目录中的 `ninja.exe` 版本低于 1.11，请用 [Ninja 1.12.1](https://github.com/ninja-build/ninja/releases) 替换。

### 构建

```bash
# 1. 配置 local.properties
# sdk.dir=D:\\AndroidDev\\sdk

# 2a. 轻量测试包（R8 + ABI 分包，debug 签名）
./gradlew :app:assembleReleaseTest

# 2b. 或构建 debug 包
./gradlew :app:assembleDebug :sample-app:assembleDebug :sample-module:assembleDebug

# 3. 安装（至少安装框架与示例模块；示例应用安装后才能被导入）
adb install -r app/build/outputs/apk/releaseTest/app-x86_64-releaseTest.apk  # 按 ABI 选择
adb install -r sample-app/build/outputs/apk/debug/sample-app-debug.apk
adb install -r sample-module/build/outputs/apk/debug/sample-module-debug.apk
```

### 使用

1. 打开 VT → 右下角 **导入** → 选择应用（默认隐藏系统应用与游戏）。
2. 点击应用卡片 → 打开详情 → 勾选要生效的 Xposed 模块。
3. 点 **启动**。应用会在 `:g0`/`:g1` 进程内打开；模块日志见 **日志** 页。
4. 真机应用更新后，点 **检查更新** 或开启 **随真机更新**，VT 会复制新 APK 并保留容器数据与模块选择。
5. 详情页可 **停止** 容器进程、**清除数据**、**删除导入**。

### 自动化调试（仅 debug 构建）

`DebugControlActivity` 只在 `src/debug` 中，release 不包含：

```bash
adb shell am start -n io.vt.framework/.DebugControlActivity --es action import --es pkg io.vt.sampleapp
adb shell am start -n io.vt.framework/.DebugControlActivity --es action enable --es pkg io.vt.sampleapp --es module io.vt.samplemodule
adb shell am start -n io.vt.framework/.DebugControlActivity --es action update --es pkg io.vt.sampleapp
adb shell am start -n io.vt.framework/.DebugControlActivity --es action launch --es pkg io.vt.sampleapp
adb shell am start -n io.vt.framework/.DebugControlActivity --es action stop --es pkg io.vt.sampleapp
```

### UI / OCR 自动化（开发机）

真机/模拟器页面操作与冒烟测试可用 OCR + Activity 焦点识别（不依赖视频页会崩的
uiautomator）：

```bash
./scripts/ui.sh -d 127.0.0.1:16384 -p com.phoenix.read state
./scripts/ui.sh -d 127.0.0.1:16384 -p com.phoenix.read tap-text 全屏观看
./scripts/scenarios/hongguo-smoke.sh -d 127.0.0.1:16384 --fresh
```

依赖 `rapidocr_onnxruntime`；产物写入 `tmp_uitest/<case>/`（截图、logcat、VT 日志）。

## 工程结构

```
app/                 Compose 管理器（应用/模块/日志/设置/导入/更新）
container/           容器内核：导入器、GuestRecord/ModuleRecord、GuestRuntime、GuestSession、进程槽
xposed/              Xposed API 82 + LSPlant/Dobby JNI + MiniDebugInfo 符号解析
  src/main/cpp/third_party/lsplant/   源码构建的最新 LSPlant（LGPL-3.0）
  src/main/cpp/xz/                    xz-embedded（0BSD），解压 .gnu_debugdata
sample-app/          端到端测试 Guest（多 Activity、Application、数据计数）
sample-module/       示例 Xposed 模块（Hook getMessage / appTag）
docs/                架构、兼容性、测试证据
scripts/             adb/OCR UI 自动化与冒烟场景
```

## 关键限制（必读）

当前原型**尚不能保证主流强对抗应用可用**：

- **服务端风控（2026-10-26/27 对照确认）**：同一台 MuMu 上，官方直装红果 7.4.1.32
  （真实 UID）清数据后连续 3 次重启均正常；而 VT Guest 在“确认主/子进程全部退出 +
  `clear` 后数据目录无文件”的干净状态下，首个 `bookmall/tab` 请求仍被服务端以
  `ILLEGAL_ACCESS`（code 110）拒绝并弹出“当前版本不安全，请到正规应用市场下载”。
  这不是设备污染或旧数据残留，而是**容器环境（宿主 UID / 容器 dataDir / 原生 maps）
  被识别**。2026-10-27 新建干净 MuMu profile（PD2245）并加入 native maps/fd
  路径虚拟化后：红果启动/推荐流 110=0，连续 force-stop 重启仍 0；但点“全屏观看”时
  `bookmall/tab` 仍 2×110 + 网络错误；同 profile 官方直装播放正常。关闭该 native shim
  后 Guest 在启动约 1 秒内原生崩溃（ExitInfo `APP CRASH(NATIVE)` status=6），说明
  shim 是启动必需，110 仍有另一层信号（宿主 UID / host APK 签名 / dataDir / 服务端
  device risk）。免 root 容器无法完全消除；详见兼容性 3.6.6。
  MuMu 对番茄 7.4.1.32/抖音 36.4.0 的新 native 不兼容（原生崩溃），
  这两款必须在真实 arm64 设备验证（见兼容性 3.6.7）。

- **多进程 Guest**：Activity 可路由到第二个进程；跨进程 **Service** 已可通过宿主 StubService 启动/绑定
  （抖音 Push/WsChannel 已实际使用），但跨进程 Provider/Receiver 与 >2 进程槽仍未完成。
- **目标应用**：抖音 40.7.0 的真机主流程已验证，短信登录验证码页待真实手机号复测，反作弊/长稳仍需继续；
  红果短剧可浏览推荐流并播放短剧，但服务端会对容器环境的 `bookmall/tab` 返回
  `ILLEGAL_ACCESS` 并触发盗版 Toast（干净数据首请求即可复现，见上文与兼容性 3.6.5）；
  番茄小说首会话可浏览书城/书籍详情并阅读正文，但正文接口同样受服务端风控影响。
  MuMu 实测（2026-10-26）：番茄 7.1.9.32 书城/详情/第 1 章正文可读，连续 3 轮
  PID stop/relaunch 110=0，第 4 次启动出现 1 次 `bookmall/tab` 110（Toast 一闪）；
  抖音 36.4.0 官方能到隐私页，VT Guest 在 Splash 后原生 SIGSEGV
  （ExitInfo `SIGNALED status=11`），需真机/目标版本复测。
  这是无 root 容器无法可靠绕过的风控/防盗版链，账号/支付/子进程 Provider 亦未验证。
  启动回归（Guest `getOpPackageName` 与内核 UID 不一致触发 `ActivityTaskManager` 归属拒绝）已于
  2026-10-26 修复；停止/清数据已改为按 PID 终止并确认退出（见兼容性 3.6.5）。
  客户端侧的 PM 视图自相矛盾已系统性修复（28 项断言三场景全通过，模拟器与真机均已复跑），
  但服务端仍可从内核 UID、`/proc/self/maps`、容器 dataDir 路径等运行时痕迹判定环境。
- **测试环境**：真机为 HyperOS（厂商修改较多），上述结果不外推到所有 Android 16 设备。
- **跨应用广播**：动态/静态 Receiver 的 Guest 内部广播已支持；为保证 Guest 内部可达，已注册 action 的广播定向宿主包，向其他应用发送的隐式广播语义受限。
- **反容器/反 Hook**：签名校验、完整性校验、Native 反调试、Root/容器检测、风控 SDK 尚未对抗；抖音、番茄等应用预计会触发。
- **Binder 边角**：JobScheduler 目前是“返回成功的空实现”，通知渠道/`notify`/StorageManager/Environment
  已做宿主重定向；前台服务可以使用真实通知，但没有系统前台服务语义；账户、AlarmManager 精确闹钟等
  仍可能因 Host/Guest 身份差异失败。
- **模块能力**：资源替换（`IXposedHookInitPackageResources`）仅提供有限的字符串级兼容；`XSharedPreferences` 需要手动镜像；新式 libxposed API 未支持；被 AOT/JIT 内联的方法可能需要 deopt。
- **游戏**：按需求默认隐藏游戏；未做游戏兼容。
- **发布形态**：`releaseTest` 是 R8 最小化 + **debug 签名 + 导出 DebugControlActivity** 的测试包；正式发布需移除测试入口并换签名。

完整状态矩阵和后续计划见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) 与 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 安全与合规

- 仅导入你有权修改/调试的应用；不得用于绕过付费、规避风控或侵犯第三方权益。
- 本项目的容器/反检测能力仅用于安全研究、兼容性测试与模块开发。
- 本项目按 **GPL-3.0** 发布；第三方组件许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
