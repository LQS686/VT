# 兼容性状态

> 结论先行：VT 0.1 是**可运行原型**。它已经打通“免 root 导入 → 模块加载 → Guest 多 Activity”，
> 在 **NewPipe / Telegram** 上完成真实应用验证；在 **Android 16 arm64 真机**上，**抖音 40.7.0** 可进入推荐流、
> 播放视频并加载 Xposed 模块，**番茄小说 / 红果免费短剧 7.4.1.32** 可自然启动、完成隐私同意并浏览推荐流。
> 但两者打开正文时会收到服务端 `ILLEGAL_ACCESS`（code 110），界面提示“当前版本不安全”，
> 属于服务端防盗版/风控，当前无 root 容器无法可靠绕过；抖音短信登录的验证码页也仍待用真实手机号复测。
> 下表是实测或代码审查后的状态，不是宣传口径。

## 1. 需求对照

| 需求 | 状态 | 证据 / 说明 |
| --- | --- | --- |
| 免 root | ✅ | 全程使用普通应用 UID；无 su、无 Zygisk、无系统镜像修改 |
| 不修改原安装包 | ✅ | 只复制 base/split APK 到私有目录；真机原包不变 |
| 向应用加载 Xposed 模块 | ✅（legacy API） | 示例模块在示例应用与 NewPipe 中均实际命中 Hook；`handleLoadPackage` 早于 `Application.onCreate` |
| 在框架内打开导入的应用 | ✅ | Guest `LoadedApk/Application/Resources/Activity` 真实运行；NewPipe 主界面、抽屉、设置页可导航 |
| 随真机应用更新而更新 | ✅ | 实测示例应用 v1.0→v12.0；复制新 APK、版本记录更新、数据目录与模块选择保留 |
| 轻量化 | ✅ | R8 + ABI 分包 + 资源裁剪后约 **2.8 MB/ABI**（releaseTest），通用包约 4 MB |
| 兼容主流 Android 版本 | ⚠️ | minSdk 28；Android 15 x86_64 模拟器与 **Android 16 arm64 真机（HyperOS）** 均有验证；Android 9–14 未逐一实测 |
| 兼容主流 Xposed 模块 | ⚠️ | 兼容 legacy API 82 的常见反射 Hook；资源替换、新版 libxposed API、模块 prefs 写入不完整 |
| 真实第三方应用 | ✅（主进程） | NewPipe 0.29.1（主界面/设置/Service/模块注入）；Telegram 13.1.0（引导/手机号/代理设置/原生库/权限） |
| 兼容主流应用（抖音/番茄等） | ⚠️ 部分 | 抖音 40.7.0：推荐流/视频/模块/跨进程 Service；番茄小说/红果短剧：启动→浏览→短剧播放；**正文接口服务端拒绝 `ILLEGAL_ACCESS`（code 110）**。账号登录/支付/长稳未验证 |
| 不含游戏 | ✅ | 导入列表过滤 `CATEGORY_GAME` 与系统应用；未做游戏兼容 |

## 2. 已验证矩阵

| 项目 | 结果 |
| --- | --- |
| 设备/系统 | Android 15（API 35）x86_64 模拟器；**Android 16（API 36）arm64 真机（Xiaomi HyperOS）** |
| 框架 ABI | x86_64 模拟器实测；**arm64-v8a 真机实测（抖音/番茄导入）**；armeabi-v7a 构建但未实测 |
| 示例 Guest | `io.vt.sampleapp` v17，4 个 Activity、自定义 Application、Provider/Service/静态 Receiver/原生库/Activity 结果回调 |
| 示例模块 | `io.vt.samplemodule`，legacy `IXposedHookLoadPackage` |
| 实例方法 Hook | ✅ 示例应用 UI 显示 `| hooked=true` |
| 静态方法 Hook | ⚠️ 安装成功，但示例中的短静态方法被 ART 内联，UI 未体现；需要 deopt 调用方 |
| 多 Activity | ✅ 示例应用 `SecondActivity`；NewPipe 设置页、视频设置页 |
| Guest 子进程 Activity | ✅ 示例 v15 `:remote` → `:g1`；独立 Application/模块加载、同数据目录 |
| Guest 跨进程 Service | ✅ 宿主 StubService + Binder 透传；抖音 `KeepPushLaunchService`、`WsChannelService` 实际在 `:g1` 启动/绑定 |
| Android 16 arm64 真机 | ✅ 抖音 40.7.0 导入/推荐流/视频；native Hook 需跳过 near-branch trampoline、MarkVisiblyInitialized、RegisterNative |
| 抖音真机基本可用 | ✅ 匿名推荐流、视频播放、跨进程 Service、Xposed 模块注入（VT MODULE ACTIVE 浮层）；短信登录滑块后的验证码页待真实手机号复测 |
| 番茄小说 | ⚠️ 自然启动（Splash alias）→ 隐私同意 → 书城推荐流；打开正文返回 `ILLEGAL_ACCESS`（code 110） |
| 番茄免费短剧（红果） | ⚠️ 自然启动 → 隐私同意 → 推荐流 → 全屏播放；正文/部分内容接口可能返回 `ILLEGAL_ACCESS` |
| Application 启动时序 | ✅ Guest Application 在首个 Activity 构造前创建，匹配真实进程启动 |
| activity-alias 启动入口 | ✅ 保留 alias 组件语义（应用据此判断闪屏/初始化），实例化时映射到 `targetActivity`（抖音 `splash.SplashActivity`、番茄 `SplashActivity`、Telegram `DefaultIcon`） |
| Activity 结果回调 | ✅ 示例 v17：`startActivityForResult` → `Guest Activity result: req=1001 res=-1`，页面显示 `result-ok`；Guest Activity 不再被替换为宿主 Application |
| 更新 | ✅ 示例应用 v1.0 → v12.0；数据目录与模块作用域保持 |
| 模块作用域 | ✅ 开启/关闭只作用于单个 Guest；NewPipe 与示例应用互不影响 |
| Guest Provider | ✅ 本进程安装 + 本地 Transport 路由，示例 `providerReady=true, queryCount=1` |
| 系统 Provider | ✅ 宿主 attribution 后 Settings/PhoneWindow 正常 |
| Guest Service | ✅ 示例 Service start/bind；NewPipe `PlayerService` 在 `:g0` 内创建 |
| 原生库 | ✅ Guest `System.loadLibrary` 加载 `libsample.so`，JNI 返回 `native-ok` |
| 动态/静态广播 | ✅ 示例 `broadcast count=1..3`、`static receiver=1..2`；仅主进程 Receiver |
| 跨应用广播 | ⚠️ 已注册 action 被包定向，外部应用隐式广播语义受限 |
| 元数据/签名 | ✅ `meta=meta-ok`, `signers=1` |
| 通知渠道 | ✅ Guest `NotificationManager` 重映射到宿主，NewPipe 启动不再因 `createNotificationChannels` 崩溃 |
| Toast | ✅ `Toast$TN` 的包名与 Context 改用宿主身份，避免 `Adding window failed: Package ... not in UID ...` |
| 真机身份一致性 | ⚠️ 已伪造：真实安装 APK/原生库路径、codePath/resourcePath、首次安装/更新时间、进程 cmdline/comm、`getRunningAppProcesses`、从 PM/UID 列表中隐藏容器包；仍未伪造：guest dataDir、内核 UID、`/proc/self/maps` 中的容器/Xposed 库 |
| 明文 HTTP | ✅ 宿主允许 cleartext，修复番茄系 CDN `CLEARTEXT communication ... not permitted` |
| 服务端防盗版 | ❌ 番茄/红果正文接口返回 `ILLEGAL_ACCESS`（code 110），非本地可关闭的开关 |
| 运行时权限 | ✅ `requestPermissions` 按宿主真实授权自动回调；撤销宿主通知权限时 NewPipe 收到 DENIED 且不崩溃 |
| PendingIntent | ✅ Guest 创建改用宿主身份；Activity 目标改走占位 Activity |
| MediaSession | ✅ `MediaSessionManager.createSession` 使用宿主上下文，NewPipe PlayerService 初始化成功 |
| JobScheduler | ⚠️ 返回成功的空实现（不真正调度）；用于让 WorkManager 启动链路通过 |
| 前台服务/通知 | ✅ 真实通知展示、点击回跳 Guest、`stopForeground`/停止容器取消；无 AMS 前台服务语义/oom 提升 |
| R8/ABI 分包 | ✅ releaseTest 2.8 MB/ABI，示例应用全链路复测通过 |

## 3. 真实第三方应用：NewPipe 0.29.1

导入 `org.schabi.newpipe`（13 个 Activity、4 个静态 Receiver）后直接启动，依次修复了：

1. **外部存储**：`getExternalCacheDir/FilesDir` 等改为返回 Guest 私有 `external/` 目录，避免
   `StorageManager.getVolumeList` 的包归属校验；
2. **通知渠道**：Guest `NotificationManager` 的所有调用重映射到宿主实例，`createNotificationChannels` 不再抛
   `Package org.schabi.newpipe is not owned by uid ...`；
3. **应用语言**：`LocaleManager.getApplicationLocales/setApplicationLocales` 在 Guest 内返回空 LocaleList / no-op；
4. **PendingIntent**：`getActivity/getActivities/getBroadcast/getService/getForegroundService` 把 Context 换成宿主，
   Intent 内的 Guest 包名改为宿主；对 Activity 目标使用占位 Activity + 原始 Intent；
5. **媒体会话**：`MediaSessionManager.createSession` 前把管理器的 `mContext` 换成宿主，使 SessionManagerService
   的所有权校验通过；
6. **JobScheduler**：Hook 实际实现类 `android.app.JobSchedulerImpl`，返回成功空结果，避免 WorkManager 初始化中断。

验证结果：

- NewPipe 主界面、抽屉、Settings、Video and audio 设置页均可正常显示与交互；
- `PlayerService` 创建成功（`Guest service created: org.schabi.newpipe.player.PlayerService`）；
- 示例模块加载并命中：`VT 真实应用 Hook 生效: NewPipe MainActivity.onCreate`；
- 窗口焦点显示为 `org.schabi.newpipe/org.schabi.newpipe.MainActivity`，说明 Guest 的包/窗口身份正常。

已知限制：

- 模拟器未配置 YouTube 可用的网络代理，NewPipe 首页显示 `Network error`；这是环境问题，不是容器崩溃；
- 未验证实际视频播放、前台媒体通知、下载（需要真实网络 + 更长运行时间）；
- NewPipe 主流程未依赖子进程；抖音/番茄的多进程仍未解决。

截图：[newpipe-main.png](screenshots/newpipe-main.png)、[newpipe-drawer.png](screenshots/newpipe-drawer.png)、
[newpipe-settings.png](screenshots/newpipe-settings.png)。

## 3.1 Guest 前台服务通知（示例应用 v14）

示例 Service 调用 `startForeground(7, notification)` 后的验证：

- 通知以宿主包发布：`dumpsys notification` 中 `pkg=io.vt.framework`、
  `tag=vt:io.vt.sampleapp:io.vt.sampleapp.SampleService`、`channel=vt_sample_foreground`；
- 小图标被改写为宿主资源（`Icon(typ=RESOURCE pkg=io.vt.framework ...)`），避免系统从 Guest 包加载失败；
- 标题/文本保留，点击通知通过 PendingIntent 打开 Guest `MainActivity`；
- `stopForeground(true)` + `stopSelf()` 后通知消失，Guest Service 结束；
- 框架“停止”容器时由 `ControlReceiver` 取消所有 Guest 前台通知；
- **限制**：没有 AMS 前台服务登记，无 oom 提升、无 5 秒规则、无真实 Service token；
  媒体播放/下载场景的网络与解码尚未验证。

截图：[guest-foreground.png](screenshots/guest-foreground.png)、[guest-v14.png](screenshots/guest-v14.png)。

## 3.2 Guest 子进程 Activity（示例 v15）

示例应用新增 `RemoteActivity`（`android:process=":remote"`），从主进程启动后的验证：

- 路由日志：`RemoteActivity -> StubStandard1, process=io.vt.sampleapp:remote, slot=1`；
- 系统创建 `io.vt.framework:g1`，其中独立启动 Guest Application、加载 Xposed 模块、建立 GuestSession
  （`slot=1`），并显示 RemoteActivity；主进程 `:g0` 继续存活；
- 页面显示子进程 pid、Application 类名、同一 Guest 数据目录；
- 返回键回到 `:g0` 的 MainActivity；
- 框架“停止”先通过 `finishAndRemoveTask()` 移除该 Guest 的所有占位任务，再向两个槽发送停止指令，
  `:g0` 与 `:g1` 均退出，不会留下重新占用进程槽的幽灵任务。

限制：只支持一个额外进程槽；跨进程 Service/Provider/Receiver 未做 Binder 桥接；同一 Guest 有两个以上子进程
或两个 Guest 同时使用子进程时可能复用冲突。

截图：[guest-remote-process.png](screenshots/guest-remote-process.png)、
[guest-remote-release.png](screenshots/guest-remote-release.png)。

## 3.3 第二个真实应用：Telegram 13.1.0

导入 `org.telegram.messenger.web`（22 个 Activity、14 个 Receiver、68 MB APK、25 MB 原生库）后验证：

- 首次启动失败：启动入口 `org.telegram.messenger.DefaultIcon` 是 **activity-alias**，容器按类名加载失败。
  导入器现在解析 `ActivityInfo.targetActivity`，记录真实入口 `org.telegram.ui.LaunchActivity`；
- 第二次失败：`LaunchActivity` 构造函数访问 `ApplicationLoader.applicationContext` 得到 null。真实 Android 在
  进程启动时先创建 Application，而容器此前在 Activity 构造之后才创建。现在 `performLaunchActivity` before
  阶段显式调用 `LoadedApk.makeApplication()`，Application 早于首个 Activity；
- 修复后启动成功：焦点窗口 `org.telegram.messenger.web/org.telegram.ui.LaunchActivity`，原生库
  `libtmessages.49.so` 正常加载，显示启动引导页与“Start Messaging”；
- 点击 Start Messaging 进入手机号页；Telegram 请求 `READ_PHONE_STATE`/`READ_PHONE_NUMBERS` 时，
  容器按宿主授权返回 DENIED 并回调，应用继续到手机号输入页，无崩溃；
- 右上角菜单可打开 Proxy Settings 页面，UI 与 SharedPreferences 正常；
- 缺失的 `com.google.android.gms.measurement.AppMeasurementReceiver` 静态 Receiver 被跳过并记录日志，
  不影响启动；
- 未验证注册/登录/收发消息（需要真实手机号与可用网络）；Telegram 的 GMS 推送与多进程服务仍未验证。

截图：[telegram-launch.png](screenshots/telegram-launch.png)、
[telegram-phone.png](screenshots/telegram-phone.png)、
[telegram-proxy.png](screenshots/telegram-proxy.png)、
[telegram-release.png](screenshots/telegram-release.png)。

## 3.4 抖音 40.7.0：Android 16 arm64 真机

环境：Xiaomi HyperOS，Android 16（API 36），arm64-v8a；框架 releaseTest + 示例模块。

- 导入：`com.ss.android.ugc.aweme` v40.7.0，**1472 个 Activity、67 个静态 Receiver**；
- 启动：Guest Application `AwemeHostApplication` 在 Activity 前创建；206 个静态 Receiver 注册；
- 界面：进入 `com.ss.android.ugc.aweme.main.MainActivity`，推荐流加载真实视频并播放，点赞/评论/分享数据正常；
  未登录时会弹出登录半屏，可关闭后继续浏览；
- 组件：`KeepPushLaunchService`、`WsChannelService` 等由宿主 StubService 在 `:g1` 启动/绑定，Binder 透传；
- **模块注入**：启用 `io.vt.samplemodule` 后，`handleLoadPackage` 在抖音 Guest 中执行，`MainActivity.onCreate`
  Hook 命中，活动窗口显示 “VT MODULE ACTIVE” 浮层（最终轻量 releaseTest 同样通过）；
- 模块 APK 重装后路径变化时，加载器会通过 PackageManager 获取当前 APK 路径并继续加载；
- 稳定性修复：
  1. `SIGILL` in `ClassLinker::DefineClass` → 关闭 Dobby near-branch trampoline，改用 far trampoline；
  2. 宿主 UI 进程初始化 LSPlant 后 MIUI 资源类解析崩溃 → 宿主进程不再初始化 LSPlant；
  3. `MarkVisiblyInitialized` 内联 Hook 在 Android 16 类初始化时 SIGSEGV → SDK 36 跳过；
  4. WebView JNI 解析 SIGSEGV → SDK 36 跳过 `RegisterNative` 系列 Hook（VT 不提供 Native Hook API）；
  5. 后台线程 `Environment.isExternalStorageEmulated` 崩溃 → Environment/StorageManager 宿主重定向；
  6. `SecurityException`：宿主补充 `ACCESS_NETWORK_STATE` 等普通权限；`getHistoricalProcessExitReasons`
     返回空历史；File 构造器 null 路径兜底；
  7. 进程名/Application 时序/Provider 时序/activity-alias 修复（详见架构文档）。
- **启动入口回归（2026-10-26）**：改用真实 launcher alias `com.ss.android.ugc.aweme.splash.SplashActivity`
  （target=`main.MainActivity`）后，releaseTest 仍正常进入推荐流并显示模块浮层；此前“走 Splash 就 native 崩溃”
  的行为未再复现。
- **短信登录**：容器曾把 `execStartActivity` 的 Guest Activity 上下文替换为宿主 Application，导致
  `onActivityResult` 丢失；滑块验证完成后登录页收不到结果，验证码输入界面不出现。现已保留 Guest Activity
  作为结果接收者，并用示例 v17 验证 `startActivityForResult` 往返正常；抖音登录页可正常打开，滑块后的
  验证码页仍需用真实手机号复测。
- 限制：真机为厂商修改较多的 HyperOS；短信登录验证码页待真实手机号复测，支付、直播、长时间播放与更多模块未验证；不可宣称“抖音完全兼容”。

截图：[douyin-main.png](screenshots/douyin-main.png)、[douyin-feed.png](screenshots/douyin-feed.png)、
[douyin-feed-release.png](screenshots/douyin-feed-release.png)、[douyin-module.png](screenshots/douyin-module.png)、
[douyin-login.png](screenshots/douyin-login.png)。

## 3.5 番茄小说 / 红果免费短剧 7.4.1.32：主流程已跑通

两者使用同一套字节系代码库（`com.dragon.read` / `com.phoenix.read`，版本均为 7.4.1.32）。

- 导入：番茄小说 771 个 Activity、33 个静态 Receiver；红果短剧 787 个 Activity；
- **入口保真**：真实启动组件是 `<activity-alias name="...splash.SplashActivity"
  targetActivity="...main.MainFragmentActivity">`。早期导入器把 alias 改写成目标类，应用因此跳过闪屏初始化，
  出现“主界面能显示但 HTTP/Cronet 未初始化”或 ANR。现在保留 alias 组件写入 Guest Intent，实例化时再映射到
  `targetActivity`，与真实系统行为一致；
- **启动初始化**：补齐字节系 `g4.c(Application)`（RPC）与 `p5.b(Application)`（TTAccount）；
  `MainUIPreviewTask/di5.i0` 中的 HTTP 初始化 `h1.e(Application)` 作为兜底（仅在 `base.http.k.b == false` 时执行），
  回填 TTNet/Cronet provider；
- **Toast 身份**：Cronet 初始化期间应用弹出 Toast，`Toast$TN` 用 `context.getPackageName()` 设置窗口包名，
  系统以 `Package com.xxx not in UID ...` 拒绝并崩溃；现在 TN 的包名与 Context 都用宿主身份；
- 验证结果（Android 16 arm64 真机，debug 与 releaseTest 均复测）：
  - 番茄小说：真实 Splash → 隐私同意 → 书城推荐流，排行榜/封面/搜索热词加载正常；
  - 红果短剧：真实 Splash → “欢迎使用红果免费短剧”隐私弹窗 → 同意 → 推荐短剧流（真实封面、标题、点赞数）
    → 全屏播放正常；
- 仍未验证/未通过：正文接口的服务端防盗版 `ILLEGAL_ACCESS`（见 3.6）、账号登录、支付/充值、
  长时间播放稳定性、声明在其他进程的 Provider/Receiver、DRM 视频。

截图：[fanqie-splash.png](screenshots/fanqie-splash.png)、[fanqie-privacy.png](screenshots/fanqie-privacy.png)、
[fanqie-main.png](screenshots/fanqie-main.png)、[phoenix-privacy.png](screenshots/phoenix-privacy.png)、
[phoenix-feed.png](screenshots/phoenix-feed.png)、[phoenix-play.png](screenshots/phoenix-play.png)。

## 3.6 番茄/红果“当前版本不安全”：服务端防盗版校验（未解决）

打开书本章节或部分正文时，界面显示“当前版本不安全，请到正规应用市场下载”。定位结论：

- 该文案来自 `R.string` `0x7f06130f`，由 `NetReqUtil.assertRspDataOk` 在响应错误码 **110** 时显示；
- 实际异常为 `ErrorCodeException(110, "ILLEGAL_ACCESS")`，构造栈位于 `NetReqUtil.assertRspDataOk`；
- 抓到的正文请求：
  - `https://reading.snssdk.com/reading/reader/full/v?item_id=...&book_id=...`
  - `https://reading.snssdk.com/reading/reader/batch_full/v?...`
- 服务端返回 **HTTP 200**，`Content-Length: 51`，JSON 业务码 110、错误字串 `ILLEGAL_ACCESS`；同一设备上**原版应用匿名可正常阅读**，因此不是账号/网络问题，而是容器环境被服务端风控识别；
- 请求携带了真实 `device_id/iid/channel/ROM/分辨率` 等参数，说明基础设备信息并非伪造失败；差异集中在服务端不可见的客户端完整性/风险信号。

已尝试的本地“真机化”修复（可降低普通检测，但不足以通过该服务端校验）：

- `ApplicationInfo.sourceDir/publicSourceDir/splitSourceDirs/nativeLibraryDir` 改为真实安装路径；`Context.getPackageCodePath/getPackageResourcePath` 同步；
- 首次安装/更新时间使用系统真实值；安装器来源读取真实值；
- 进程 `argv0` 与 `/proc/self/comm` 改为 Guest 包名/进程名；`getRunningAppProcesses` 返回 Guest 身份；
- 从 Guest 可见的 PackageManager/UID 包列表中隐藏 `io.vt.framework`；查询宿主包返回 `NameNotFoundException`；
- Toast 窗口宿主化；宿主开启 cleartext，修复 CDN 明文请求被 network security policy 拒绝。

仍无法隐藏的关键差异（无 root 的硬限制）：

| 差异 | 原因 | 影响 |
| --- | --- | --- |
| Guest `dataDir`/`filesDir` 在 `/data/user/0/io.vt.framework/...` | 无 root 无法把真实应用数据目录映射给宿主 UID | 路径/完整性检测 |
| 内核 UID 是宿主 UID，不是已安装应用的 UID | 同一进程无法切换 UID | 原生 `getuid()` 与包 UID 不一致 |
| `/proc/self/maps` 含 `libvt_xposed.so` 与容器路径 | LSPlant/容器以宿主 APK 加载 | 原生风控/反 Xposed 扫描 |
| 服务端风险 token | 由字节系 SecSDK 生成，无法从原版进程读取 | 正文接口拒绝 |

结论：**这是客户端环境检测 + 服务端防盗版/风控，不是可以简单 Hook 掉的本地弹窗**。APK 内可读到
`isXposedExist/isXposedExists`、`isVirtual`、`isRoot` 等检测字段；`SecurityUtils.findHookAppName`
会检查已知 Xposed/Magisk 包名（VT 不在其列表内），更底层的字节系 SecSDK/Mannor 仍在检查进程、路径与
Xposed 痕迹，最终由服务端返回 `ILLEGAL_ACCESS`。

### 同类项目对照

| 项目 | 无 root | 不改原 APK | 在真实 UID/数据目录运行 | 强对抗应用结果 |
| --- | --- | --- | --- | --- |
| VirtualXposed / VirtualApp | ✅ | ✅ | ❌ 宿主 UID + IO 重定向 | 常见虚拟环境检测/风控 |
| 太极免 root / 应用转生 | ✅ | ⚠️ 部分依赖转生/虚拟环境 | ❌ | 强校验应用仍可能拒绝 |
| LSPatch | ✅ | ❌ 重打包并重签名 | ✅ 真实 UID/数据目录 | 签名校验类应用可能拒绝 |
| LSPosed（root） | ❌ | ✅ | ✅ | 最可靠，但不是免 root |
| VT（本项目） | ✅ | ✅ | ❌ 宿主 UID + 容器 dataDir | 正文 `ILLEGAL_ACCESS` |

参考：
[VirtualXposed](https://github.com/android-hacker/VirtualXposed)、
[VirtualApp](https://github.com/asLody/VirtualApp)、
[LSPatch](https://github.com/LSPosed/LSPatch)、
[LSPosed](https://github.com/LSPosed/LSPosed)。

**结论：三条约束（免 root / 不改 APK / 真实进程环境）无法同时满足。** 若必须让番茄/红果正文通过校验，
必须放宽一条：使用官方应用（不改包、不必 root，但无模块），使用 LSPosed（真实进程/UID/数据目录，
但需 root），或使用 LSPatch/转生（免 root，但修改 APK 与签名）。VT 不会伪造内容授权、签名或 DRM；
需要对强对抗应用注入模块时，推荐的工程方向是 **可选 root + LSPosed 桥接**，而不是继续在容器里堆反检测。

## 4. 目标应用阻塞项

### 抖音（`com.ss.android.ugc.aweme`）

- **已验证基本流程**：Android 16 arm64 真机可导入、经真实 Splash alias 进入推荐流、播放视频，
  Push/WsChannel Service 可启动，模块 Hook 可显示浮层；
- **仍阻塞**：短信登录滑块后的验证码页（容器侧 ActivityResult 已修复，待真实手机号复测）、
  反调试/完整性校验、长时间稳定性；
- **多进程**：Activity 与部分 Service 已支持；跨进程 Provider/Receiver、>2 进程槽仍未完成。
- **跨应用广播**：Guest 内部动态/静态 Receiver 已支持；已注册 action 的广播定向宿主包，外部隐式广播语义受限。
- **静态 Receiver 进程**：只注册主进程 Receiver，声明在 `:push` 等子进程的 Receiver 被跳过。
- **签名/完整性/反容器**：字节系风控、Native 反调试、包完整性校验未处理。
- **前台服务/通知、JobScheduler**：通知已可真实展示与取消；JobScheduler 仍是成功空实现，
  AMS 前台服务语义（oom 提升、5 秒规则）未实现。
- **Native/ABI**：抖音几乎全 arm64；x86_64 模拟器无法运行其 native 库，本机未做真实设备验证。

### 番茄小说（`com.dragon.read`）/ 番茄免费短剧（`com.phoenix.read`）

- 启动、隐私同意、主界面/推荐流、短剧全屏播放已跑通；
- **主要阻塞：正文接口服务端返回 `ILLEGAL_ACCESS`（code 110），提示“当前版本不安全”**；
  这是服务端防盗版/风控，无 root 容器缺少真实 UID/dataDir、映射中包含容器/Xposed 库，无法可靠通过；
- 账号登录与支付链路、长时间稳定性、子进程 Provider/Receiver、DRM/版权视频同样未验证；
- 这两款应用大量使用 Tinker/MuteApplication、Lego 启动任务与多进程，版本升级后需重新回归。

> 在补齐 P0 能力并在真实 arm64 设备上完成兼容性测试前，本项目不会声称这些应用“完全可用”。

## 5. 模块兼容性

| 模块能力 | 状态 |
| --- | --- |
| `assets/xposed_init` / `META-INF/xposed/java_init.list` | ✅ |
| `IXposedHookLoadPackage` | ✅（示例、NewPipe、抖音） |
| 目标应用模块注入 | ✅ 抖音 `MainActivity.onCreate` + 可见浮层 |
| `IXposedHookZygoteInit.initZygote` | ✅（进程级，非系统级） |
| `XposedHelpers.findAndHookMethod` / 反射工具 | ✅（API 82 复制实现） |
| 实例方法 Hook | ✅（示例应用、NewPipe 均验证） |
| 静态方法 Hook | ✅机制可用；被内联的调用方需 deopt |
| 构造方法 Hook | ✅ NewPipe `MainActivity.<init>` 实测命中（模块在 Activity 实例化前加载） |
| `IXposedHookInitPackageResources` / `XResources` 替换 | ⚠️ 仅字符串级有限实现 |
| `XSharedPreferences` | ⚠️ 只读镜像；模块私有目录不可读 |
| 新版 libxposed API（`io.github.libxposed`） | ❌ 未实现 |
| 模块自身设置 Activity | ❌ 未在容器内运行（可在模块页提示） |
| Native Hook / JNI 模块 | ❌ 未提供 API |

## 6. Android / ABI

| Android | 状态 |
| --- | --- |
| 9 / 10 / 11 | 代码目标范围；未逐一实测 |
| 12 / 13 / 14 | 代码目标范围；未逐一实测 |
| 15 | ✅ 模拟器实测 |
| 16 | ✅ arm64 真机（HyperOS）：抖音、番茄小说、红果短剧的导入/启动/主流程 + 模块注入；需使用 Android 16 专用 Hook 配置 |
| 17 | ❌ 未验证；LSPlant 源码声称支持到 17 |

ABI：构建 `arm64-v8a`、`armeabi-v7a`、`x86_64`；x86_64 模拟器与 arm64 真机均有端到端验证，
armeabi-v7a 未实测。
