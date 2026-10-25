# VT 框架

免 root、免修改安装包的 Xposed 模块容器（Android 9–16 原型）。

> 当前版本 **0.1.0**，是一个可运行的原型，不是太极/LSPosed 的完整替代品。
> 已在 Android 15（x86_64 模拟器）与 **Android 16 arm64 真机（HyperOS）** 上验证：
> **抖音 40.7.0** 可导入、进入推荐流并播放视频，Push/WsChannel 跨进程 Service 由容器 Stub 启动；
> **番茄小说 / 红果免费短剧 7.4.1.32** 可自然启动、完成隐私同意并浏览推荐流（短剧可全屏播放），
> 但打开正文会收到服务端 `ILLEGAL_ACCESS`（code 110），提示“当前版本不安全”——这是服务端防盗版/风控，
> 无 root 容器无法可靠绕过。详见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)。
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
```

## 关键限制（必读）

当前原型**尚不能保证主流强对抗应用可用**：

- **多进程 Guest**：Activity 可路由到第二个进程；跨进程 **Service** 已可通过宿主 StubService 启动/绑定
  （抖音 Push/WsChannel 已实际使用），但跨进程 Provider/Receiver 与 >2 进程槽仍未完成。
- **目标应用**：抖音 40.7.0 的真机主流程已验证，短信登录验证码页待真实手机号复测，反作弊/长稳仍需继续；
  番茄小说/红果短剧可启动浏览，但**正文接口被服务端以 `ILLEGAL_ACCESS`（code 110）拒绝**，
  这是无 root 容器无法可靠绕过的防盗版/风控，账号/支付/长时间播放/子进程 Provider 亦未验证。
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
