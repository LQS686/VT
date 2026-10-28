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
| 兼容主流应用（抖音/番茄等） | ⚠️ 部分 | 抖音 40.7.0：推荐流/视频/模块/跨进程 Service；红果短剧：首会话推荐流+短剧播放；番茄小说：首会话推荐流/书籍详情+**正文可读**（屏蔽 Metasec 反 Hook 采样后，见 3.6.4）。干净对照确认：官方直装同机正常，而 Guest 首请求即可能被服务端 `ILLEGAL_ACCESS`（110）拒绝并弹盗版 Toast（3.6.5）。账号登录/支付/长稳未验证 |
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
| 番茄小说 | ✅ 启动→隐私同意→书城推荐流→书籍详情→**正文阅读**（全新 Guest 数据 + Metasec 采样屏蔽，实测连续翻页/换书）；风险状态滞留时会被 `当前版本不安全` 整体拦截 |
| 番茄免费短剧（红果） | ✅ 启动→隐私同意→推荐流→短剧播放→切换下一部；`FATAL EXCEPTION`=0；部分子接口（如特定筛选 tab）仍会零星返回 `ILLEGAL_ACCESS` |
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
| Toast | ✅ `Toast$TN` 包名/Context 宿主化 + `INotificationManager$Stub$Proxy.*Toast*` callingPackage 重写；修复 Guest `ToastUtils` 触发的 `SecurityException` 进程崩溃（见 3.6.4） |
| 真机身份一致性 | ⚠️ 已伪造：真实安装 APK/原生库路径、codePath/resourcePath、首次安装/更新时间、进程 cmdline/comm、`getRunningAppProcesses`、从 PM/UID 列表中隐藏容器包；仍未伪造：guest dataDir、内核 UID、`/proc/self/maps` 中的容器/Xposed 库 |
| 明文 HTTP | ✅ 宿主允许 cleartext，修复番茄系 CDN `CLEARTEXT communication ... not permitted` |
| 服务端防盗版 | ⚠️ Metasec 的**本地反 Hook 采样**已屏蔽，正文可读；服务端风险状态仍会滞留在部分会话，`ILLEGAL_ACCESS` 无法从本地彻底消除 |
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

#### 3.6.1 PackageManager 视图自洽性缺陷（2026-10-26 已修复）

上面"隐藏 `io.vt.framework`"这一条，**在修复前实际上从未生效**。
排查方式不是猜测应用怎么检测，而是把 PackageManager 视图当作可证伪的规范，
用示例应用内置的 `PmAudit`（17 项交叉比对断言）逐项对照真机语义。

修复前基线 `PM_AUDIT passed=10 failed=7`，7 处 FAIL 全部对应代码里的确定缺陷：

| 缺陷 | 位置 | 后果 |
| --- | --- | --- |
| `getNameForUid` 未接管 | `GuestRuntime.hookPackageManager` | UID 反查直接返回宿主包名 `io.vt.framework` |
| 已安装列表保留系统原始 Guest 条目 | 同上 | 列表给真实 UID+真实 dataDir，直接查询给宿主 UID+容器 dataDir，**两套值互相矛盾** |
| 宿主包隐藏用 `after` 阶段 `throw` | 同上 + `Hooker.java:152-163` | 异常被 Hooker 吞掉并回滚，隐藏从未生效，宿主包名/APK 路径/签名仍可查 |
| flags 只按 `Int` 解析 | 同上 | API 33+ 的 `PackageInfoFlags` 重载恒为 0，`GET_ACTIVITIES`/`GET_SIGNING_CERTIFICATES` 丢失 |
| `packageInfoFor` 无视 flags 填签名 | `GuestSession` | `getPackageInfo(pkg,0)` 也返回签名，与真机 PMS 语义相反 |
| `ApplicationInfo` 多字段硬编码 | `GuestSession.buildApplicationInfo` | `processName`/`taskAffinity`/`category`/`primaryCpuAbi`/三目录结构与真机不符 |
| `versionCode` 被 `toInt()` 截断 | `GuestSession` | `longVersionCode` 与真机不一致 |

修复后：`PM_AUDIT passed=17 failed=0`，容器模式 / 普通模式 / 真机已卸载应用三种场景结果一致。
完整证据与修复清单见 `docs/TESTING.md` 第 9.7 节。

#### 3.6.2 PackageManager 查询路径盲区（2026-10-26 已修复）

3.6.1 修的是 `ApplicationPackageManager` **实例方法**上的 hook。
复核时用 `dexdump` 反汇编 `framework.jar` 逐个确认查询入口的最终实现路径，
发现**还有一整条路径绕过了所有实例方法 hook**：

```
PackageManager.getPackageInfoAsUserCached(String, long, int)   ← 静态、@hide/BLOCKED
        ↓ PropertyInvalidatedCache.query(...)
PackageManager.getPackageInfoAsUserUncached(...)                ← private static
        ↓ ActivityThread.getPackageManager() → IPackageManager
        ↓ binder 直连 PMS
```

后果有两层：

| 后果 | 说明 |
| --- | --- |
| **启动崩溃** | 目标应用在真机上被卸载后 PMS 返回 null，`LoadedApk.initializeJavaContextClassLoader()` 抛 `Unable to get package info for X; is package not installed?`，Guest 连 Application 都创建不出来 |
| **视图泄漏** | 该路径返回 PMS 真实结果（真实 UID、真实 dataDir），与实例方法返回的容器视图矛盾，应用同时调两条路径即可发现 |

同一轮还修掉 7 处同族缺陷，根因是 `Hooker.java:152-174` 的 AOSP 语义：
`after` 回调里 `param.result = X` **不清除 `throwable`**，
原方法一旦抛过异常，改写结果就会被 `Hooker.invoke()` 末尾的 `throw param.throwable` 丢弃。
涉及 `getActivityInfo`/`getServiceInfo`/`getReceiverInfo`/`getProviderInfo`/
`getResourcesForApplication`/`getLaunchIntentForPackage`/`getInstallerForUid`/
`getInstallerPackageName`/`getApplicationLabel`/已安装列表。

另外还修了：`getActivityInfo` 对不存在的组件也返回合法对象（真机应抛 `NameNotFoundException`）、
`GET_ACTIVITIES` 用懒加载缓存导致组件列表可能为空、`metaData` 未按 flags 语义返回。

#### 3.6.3 `before` 回调不能靠 `throw` 短路（真机实测发现）

`Hooker.invoke()` 对 before 阶段异常的语义是**回滚并放行原方法**
（`Hooker.java:125-137`）：

```java
} catch (Throwable t) {
    param.result = null;
    param.throwable = null;
    param.returnEarly = false;   // ← 取消短路，原方法照常执行
    continue;
}
```

因此在 before 回调里 `throw new NameNotFoundException(...)` 并**不会**让调用方收到该异常：

| 目标应用在真机的状态 | `throw` 的实际后果 |
| --- | --- |
| 未安装 | 原方法也抛 `NameNotFoundException`，结果碰巧正确 |
| **已安装** | 原方法正常返回**真机系统的**组件信息，严格语义完全失效，且与容器视图矛盾 |

正确写法是 `param.setThrowable(ex)`（内部置 `returnEarly = true`，
见 `XC_MethodHook.java:165-169`）；`param.setResult(x)` 同理。
4 处组件 hook 已全部改为 `setThrowable`。

这个问题在模拟器上**测不出来**（模拟器上目标应用通常没装，碰巧走"正确"分支），
是在 Android 16 真机（eb72c928）上从容器自身 `files/logs/xposed.log`
里看到 `beforeHookedMethod failed for ... getActivityInfo` 才定位到的。

修复后审计扩到 **28 项**（新增组件级 + 静态路径 11 项），
容器模式 / 普通模式 / 真机已卸载应用三种场景均 `passed=28 failed=0`，
`FATAL EXCEPTION` 计数 0。Android 15 模拟器与 Android 16 真机均已复跑通过。
完整证据见 `docs/TESTING.md` 第 9.8 节。

需要说明的是：**这些修复消除的是"逻辑破绽"（PM 视图自相矛盾），
不等于突破服务端风控**。服务端仍可从内核 UID、`/proc/self/maps`、
容器 dataDir 路径等运行时痕迹判定环境（见下表）。

#### 3.6.4 启动回归修复 + Metasec 反 Hook 采样屏蔽（2026-10-26 第二批）

本节记录 3.6 之后新增的实测结论，覆盖三个具体问题。

**（1）启动回归：Guest Activity 拉起下一个 Activity 被系统拒绝**

现象：优化 PackageManager 视图后，红果/番茄出现"启动页一闪、直接回桌面"。
日志（真机 HyperOS Android 16）：

```
W ActivityTaskManager: Permission Denial: package=com.phoenix.read
    does not belong to uid=10585
```

用 `dexdump` 反汇编真机 `framework.jar` 确认：
`Instrumentation.execStartActivity` 会把 `who.getOpPackageName()` 作为
`callingPackage` 经 `IActivityTaskManager.startActivity` 发给系统（method@8fe4）。
Guest 的 Context 必须对应用报告 Guest 包名（否则 PM 视图矛盾），
但内核 UID 是宿主，服务端归属校验必然失败，整个 `startActivity` 被丢弃。

修法：在 `IActivityTaskManager$Stub$Proxy` 的 `startActivity*` 上把这些
String 参数改写回宿主包名（只改 binder 参数，不改 Context 视图）。
修复后 `startActivity 路由` 日志与 Activity 栈恢复正常，见
`docs/TESTING.md` 第 9.9 节。

**（2）Toast 崩溃：Guest Toast 触发 NotificationManagerService 归属校验**

番茄/红果在播放失败等场景调用 `ToastUtils.showCommonToastSafely` 时，
`Toast.show()` 把 Guest 包名发给 `INotificationManager.enqueueToast`，
服务端 `checkCallerIsSameApp` 抛 `SecurityException`，**直接杀掉 Guest 进程**：

```
E AndroidRuntime: FATAL EXCEPTION: main
E AndroidRuntime: java.lang.SecurityException: Package com.phoenix.read is not owned by uid 10585
E AndroidRuntime:  at android.app.INotificationManager$Stub$Proxy.enqueueToast
E AndroidRuntime:  at android.widget.Toast.show
E AndroidRuntime:  at com.dragon.read.util.ToastUtils.showCommonToastSafely
```

原 `Toast$TN` 构造参数改写覆盖不到这条路径。
修法：在 `android.app.INotificationManager$Stub$Proxy` 的 `*Toast*` 方法上
改写 callingPackage（与 (1) 同一模式）。修复后实测 Toast 正常弹出、进程不再被杀。

**（3）Metasec 反 Hook 采样：正文被降级签名的直接原因**

关键线索：**全新数据的第一次运行，番茄正文可以正常阅读；运行一段时间/重启后
才出现"当前版本不安全"**。

反编译 `ms.bd.c.r2`（`MSB` 的父类）确认每次网络请求都会调用
`r2.a(context, 2, map)`（2 = URL 访问事件）：

```
if (i19 == 2) {
    if (SwitchManager.a().c(14)) {
        // 按 host 计数：第 4 次起每 10 次取当前线程调用栈，
        // 逐帧比对类名/方法名（反 Hook 特征），命中则记入可疑集合
    }
    return;      // 该分支不参与签名，只有检测副作用
}
// 其余分支：签名/初始化
```

容器所有 Hook 派发都会留下 `de.robv.android.xposed.XC_MethodHook` 栈帧，
采样必然命中；命中后 SecSDK 进入 bypass/降级签名
（抓包可见 `x-argus` 退化为 4 字节、`x-ladon` 3 字节），
服务端对 `reader/full`、`batch_full`、部分 `bookmall` 接口返回 `ILLEGAL_ACCESS`。

修法：只跳过 `i19 == 2` 这条纯检测分支，并把开关 14 置 false；签名路径不动。
实测效果（真机 eb72c928）：

| 场景 | 结果 |
| --- | --- |
| 未屏蔽采样（全新数据） | 番茄阅读器约 5 分钟后被 `当前版本不安全` 拦截 |
| 屏蔽采样（全新数据） | 番茄连续翻页 21/29 页、换书、多轮请求后仍可读；红果推荐流+短剧播放正常 |
| 屏蔽采样 + Toast 修复 | 红果连续播放/切换短剧无崩溃，`FATAL EXCEPTION` = 0 |

**仍存在的限制**：风险状态似乎会在一次会话后滞留（下一次启动可能直接被判风险，
清除 cookie 也无法恢复；清空 Guest 数据可恢复首次会话的可用状态）。
静态复核确认 Java 侧只有 `r2.a` 是反 Hook 采样（已屏蔽），其余 `getStackTrace`
命中点都是日志/异常报告；因此剩余触发更可能来自**原生 SecSDK 的环境采集**
（`/proc/self/maps` 中的宿主 APK/数据路径、内核 UID）或**服务端按 install_id/设备
的短期风险标记**，免 root 容器无法彻底消除。本轮已将宿主 JNI 库改名为
`libappcore.so` 以降低 maps 关键词命中，并在模拟器回归 `PASS=7 FAIL=0`。
**这不是"已完全绕过反盗版"**：部分子接口仍会零星返回 `ILLEGAL_ACCESS`，
账号绑定/长稳未验证。

**（4）真机安装路径优先加载**

`GuestSession` 现在优先用真机安装的 base/split APK 与 `lib/arm64` 目录
（可读时），容器副本仅作回退。这样 DexClassLoader 实际加载路径、
`ApplicationInfo.sourceDir/nativeLibraryDir` 与 PM 视图一致，
减少原生 SDK 的"加载路径 vs PackageManager 视图"矛盾。

仍无法隐藏的关键差异（无 root 的硬限制）：

| 差异 | 原因 | 影响 |
| --- | --- | --- |
| Guest `dataDir`/`filesDir` 在 `/data/user/0/io.vt.framework/...` | 无 root 无法把真实应用数据目录映射给宿主 UID | 路径/完整性检测 |
| 内核 UID 是宿主 UID，不是已安装应用的 UID | 同一进程无法切换 UID | 原生 `getuid()` 与包 UID 不一致 |
| `/proc/self/maps` 含 `libvt_xposed.so` 与容器路径 | LSPlant/容器以宿主 APK 加载 | 原生风控/反 Xposed 扫描 |
| 服务端风险 token | 由字节系 SecSDK 生成，无法从原版进程读取 | 正文接口拒绝 |

结论：**这是客户端环境检测 + 服务端防盗版/风控的组合**。APK 内可读到
`isXposedExist/isXposedExists`、`isVirtual`、`isRoot` 等检测字段；`SecurityUtils.findHookAppName`
会检查已知 Xposed/Magisk 包名（VT 不在其列表内）。
3.6.4 进一步确认：Metasec 还会在**每次网络请求**里采样调用线程栈做反 Hook 判定，
命中后 SecSDK 进入 bypass/降级签名，服务端据此返回 `ILLEGAL_ACCESS`；
该 Java 采样分支已屏蔽，正文即可正常阅读。但服务端风险状态仍可能滞留在某次会话之后
（清除 Guest 数据可恢复首会话可用），这部分无法从本地彻底消除。

#### 3.6.5 干净对照实验与稳定性修复（2026-10-26 第四批，MuMu Android 15）

为排除“设备被前次实验污染 / 旧数据残留”的解释，做了严格对照（同一 MuMu、同一网络、同一时段）：

| 对象 | UID | 数据状态 | 结果 |
| --- | --- | --- | --- |
| 官方直装 7.4.1.32 | u0_a54 | `pm clear` 后全新，连续 3 次强制停止/重启 | 推荐流正常，3 轮均无“当前版本不安全”、无“网络出错” |
| VT Guest | u0_a55 | 先确认主/子进程全部退出，`clear` 后数据目录只有空骨架、无任何文件 | 首个 `bookmall/tab` 请求即返回 110，顶部 Toast + 网络出错页 |

110 响应原文（诊断模块抓取）：`message=ILLEGAL_ACCESS`，带 `logId`，HTTP 200，body 97 字节
（示例 `logId=2026102623020411466C1D37916CB7D7DD`）。客户端链路：
`NetReqUtil.doAfterDeserialization` 计算出 code=110 → `NsBaseNetworkDependImpl.assertIllegalAccess()`
→ `ToastUtils.showCommonToastSafely(R.string.cs9)`；`resources.arsc` 中 `0x7f06130f` 即
“当前版本不安全，请到正规应用市场下载”。因此：

- 不是设备整体被风控：官方版同机正常；
- 不是 Guest 旧数据/残留进程：真正清空后首个请求仍被拒；
- 是容器进程环境（宿主 UID / 容器 dataDir / 原生 maps 等）被 SDK/服务端识别，
  `bookmall/tab` 被 `ILLEGAL_ACCESS` 拒绝；推荐流有时仍显示（不同接口策略不同），
  但盗版 Toast 会在冷启动出现。

**第四批修复的四个可复现缺陷**：

1. **媒体键崩溃**：`ISessionManager.dispatchMediaKeyEvent` 用 Guest 包名校验宿主 UID →
   `IllegalArgumentException` 杀进程（按一次暂停键即可复现）。已在
   `ISessionManager$Stub$Proxy` 的 String 参数上改写为宿主包名
   （`MediaSession attribution hooks installed=14`）。
2. **停止失效**：Guest 把进程名改成目标包名后，AMS 不再按 `:g0/:g1` 路由停止广播
   （实测广播已发，但 Guest 日志无“收到停止”，进程仍活）。`GuestLauncher.stop` 改为
   “广播清理 + 按 PID 直接终止（同一 UID）+ 等待退出”；PID 扫描同时覆盖该槽位登记的
   `:g0/:g1` 宿主进程，防止主进程崩溃后 `:g1` 推送进程残留。实测 `kill guest pid=...`
   后主/子进程全部消失。
3. **停止后清数据竞态**：旧实现 stop 无效时仍删除 dataDir，应用在运行中回写状态；
   现在确认进程退出后再删。实测 `clear` 后 `find data -type f` 为空。
4. **静态接收器崩溃**：`t41.c` 是 Guest 静态 Receiver，系统在宿主 classloader 下实例化 →
   `ClassNotFoundException` → 进程被杀、弹“VT 反复停止”。已在
   `AppComponentFactory.instantiateReceiver` 前用 Guest classloader 接管；类不存在时用
   空接收器丢弃，避免杀进程。回归中 `t41.c` FATAL=0。
5. **Guest ContentProvider 上下文错误（2026-10-26）**：`installProvidersOnce` 传的是
   `app.baseContext`（ContextImpl），而真实 Android 传 Application；抖音的
   `AhaInitializerProvider`/`ECInitializerProvider` 强制转 Application →
   `ClassCastException`，初始化被破坏。改为传 `Application` 后，红果/番茄
   `Guest provider failed=0`；抖音该异常消失，但仍在 Splash 后原生 SIGSEGV。
   签名路径诊断：`CronetUrlRequest.addSecurityFactor` /
   `CronetAppProviderManager.onCallToAddSecurityFactor` Hook 已安装但从未命中，
   字节请求的 x-argus 不在该 Java/Cronet 桥生成，无法用此方式证明降级。
   直接启动 `com.ss.android.ugc.aweme.main.MainActivity` 同样 SIGSEGV，
   排除“仅 Splash 路径”问题；MuMu 上暂无法进一步定位（原生探针未命中）。

**同批目标应用实测（MuMu Android 15，2026-10-26）**：

| 应用 | 官方直装 | VT Guest |
| --- | --- | --- |
| 番茄小说 7.1.9.32 | 未做完整回归（官方版可用） | 书城推荐流、书籍详情、第 1 章正文可读；连续 3 次 PID stop/relaunch 110=0、无崩溃；第 4 次启动出现 1 次 `bookmall/tab` 110（`ILLEGAL_ACCESS`，Toast 一闪而过），之后书城仍可用 → 110 是触发式/环境相关，不是永久首会话 |
| 抖音 36.4.0 | 能正常进入隐私同意页 | `io.vt.framework:g0` 在 Splash 后约 1 秒 **原生 SIGSEGV**（ExitInfo `reason=SIGNALED status=11`），无 Java FATAL；日志有 AppOps 包归属错误。AppOps binder 改写尝试未生效（类/方法签名不匹配），已撤回。真实设备 40.7.0 的旧成功记录仍需在目标版本复测 |

**未解决 / 已排除**：

- 每 Guest 独立 `ANDROID_ID`（随机 SSAID）实验**没有**消除 110，已撤回，不作为正式能力。
- LSPatch 改签在 MuMu 上不可测：原包只有 arm64 原生库，MuMu 用 houdini 转译，
  LSPatch 的 arm64 `liblspatch.so` 无法 hook x86_64 ART（LSPlant 初始化失败、
  `JNI DETECTED ERROR ... nativeLoad`）。已生成与真机版本一致的本地模式包
  （红果/番茄 7.4.1.32，无嵌入模块），见 TESTING 9.10；必须在真实 arm64 设备上验证，
  且改签后服务端是否接受仍未知。
- 原生崩溃探针已加入（`VtNative.installCrashHandler`，写 maps+PC 到
  `files/logs/native-crash.log`）；抖音在 MuMu 上的 SIGSEGV 未写入该文件
  （安装日志 true、文件 0 字节），疑似 houdini 转译层或应用自有信号处理直接终止进程，
  需真机验证探针有效性。
- **设备注册并非缺失**：红果 Guest 的 `shared_prefs/push_multi_process_config.xml`、
  `applog_stats.xml`、`ug_install_settings_pref.xml` 中已有有效 `install_id` 与
  `device_id`（长数字串），说明 110 不是“BDInstall 未注册/空 install_id”导致，
  而是环境/签名风险。
- 免 root 容器无法同时满足“真实 UID / 真实数据目录”；本次对照再次证明这一硬限制。

#### 3.6.6 干净设备 profile + 原生路径虚拟化 A/B（2026-10-27）

旧 MuMu 实例（SM-S9110）的 Guest 身份已被服务端标记。新建 MuMu index=1
（PD2245/vivo，adb 16416）后做了 A/B：

| 配置 | 结果 |
| --- | --- |
| 新 profile + native shim（maps 过滤 + `/proc/self/fd` APK 路径替换） | 红果启动/推荐流 **110=0**，连续 3 次 force-stop 重启仍 0；点“全屏观看”时 `bookmall/tab` 仍 **2×110** + 网络错误 |
| 同 profile 官方直装红果 | 推荐流 + 播放正常 |
| 关闭 shim（`VT_NATIVE_DIAGNOSTICS=false`） | `io.vt.framework:g0` 约 1 秒内原生崩溃，ExitInfo `APP CRASH(NATIVE)` status=6 |

原生 Dobby trace 证据：

- SecSDK 读取 `/proc/self/maps`、枚举 `/proc/self/fd` 并 readlink 到
  `/data/app/.../io.vt.framework.../base.apk` 与容器 guest 路径；
- 未见 `/proc/self/status|cgroup|mountinfo|attr` 读取；
- `getuid` 调用方只有 libart/libbinder/liblog；
- `CronetUrlRequest.addSecurityFactor` / `CronetAppProviderManager.onCallToAddSecurityFactor`
  仍未命中，x-argus 不走该 Java/Cronet 桥。

结论：
- 干净设备 profile 解决启动/推荐流；**shim 是启动必需**（关闭即原生崩溃）；
- `bookmall/tab` 的 110 是另一层信号，最可能来自宿主 UID / host APK 签名 /
  dataDir 或服务端 device risk；免 root 容器仍无法完全消除；
- 官方直装在同 profile 播放正常，说明不是 MuMu profile 整体被拒，而是 Guest 环境在敏感接口被识别。

#### 3.6.7 MuMu/houdini 对新版目标应用 native 的边界（2026-10-27）

- 番茄 7.4.1.32 在干净 MuMu profile 上（PD2245）无论 native maps/fd shim 开关，
  均出现原生崩溃循环：ExitInfo `EXIT_SELF status=139` / `APP CRASH(NATIVE) status=11`；
  崩溃栈落在 ART `Runtime::Abort`/栈转储与 `libappcore.so`。已把 maps 过滤限定为
  Guest 原生调用方（`libhoudini`/metasec/应用库），ART/framework 的 maps 读取不再被替换，
  仍不能救 7.4.1.32；旧版 7.1.9.32 可读。
- 抖音 36.4.0 容器版同样在 MuMu 上原生 SIGSEGV；官方直装可到隐私页。
- 结论：MuMu 只能验证红果的启动/推荐流（干净 profile + shim，110=0），
  番茄 7.4.1.32 与抖音必须在真实 arm64 设备验证；按应用启用 native 诊断
  （红果保留，番茄/抖音关闭）已落地。
- 进一步 A/B：只保留 maps 过滤、关闭 `/proc/self/fd` APK 路径替换时红果无法启动；
  按调用方限定 maps（ART/framework 跳过）会让红果 `EXIT_SELF status=139`，
  已回退为全量 maps 过滤。maps 与 fd 路径替换都是红果启动必需。
- 干净实例经过多次重装/清数据/A-B 后出现启动不稳定（VT-SAFEMODE/EXIT_SELF），
  需要重启实例或新建 clone；最佳已确认数据仍是干净 profile + 全量 shim 下
  红果 feed 110=0、3 次 force-stop 重启 0，bookmall/play 2×110。

### 同类项目对照

| 项目 | 无 root | 不改原 APK | 在真实 UID/数据目录运行 | 强对抗应用结果 |
| --- | --- | --- | --- | --- |
| VirtualXposed / VirtualApp | ✅ | ✅ | ❌ 宿主 UID + IO 重定向 | 常见虚拟环境检测/风控 |
| 太极免 root / 应用转生 | ✅ | ⚠️ 部分依赖转生/虚拟环境 | ❌ | 强校验应用仍可能拒绝 |
| LSPatch | ✅ | ❌ 重打包并重签名 | ✅ 真实 UID/数据目录 | 签名校验类应用可能拒绝 |
| LSPosed（root） | ❌ | ✅ | ✅ | 最可靠，但不是免 root |
| VT（本项目） | ✅ | ✅ | ❌ 宿主 UID + 容器 dataDir | 干净数据首请求即可能被 110 拒绝并弹盗版 Toast（3.6.5）；官方直装同机正常 |

参考：
[VirtualXposed](https://github.com/android-hacker/VirtualXposed)、
[VirtualApp](https://github.com/asLody/VirtualApp)、
[LSPatch](https://github.com/LSPosed/LSPatch)、
[LSPosed](https://github.com/LSPosed/LSPosed)。

**结论：三条约束（免 root / 不改 APK / 真实进程环境）无法同时满足。** 若必须让番茄/红果正文通过校验，
必须放宽一条：使用官方应用（不改包、不必 root，但无模块），使用 LSPosed（真实进程/UID/数据目录，
但需 root），或使用 LSPatch/转生（免 root，但修改 APK 与签名）。VT 不会伪造内容授权、签名或 DRM；
需要对强对抗应用注入模块时，推荐的工程方向是 **可选 root + LSPosed 桥接**，而不是继续在容器里堆反检测。

### 3.6.8 2026-10-27 控制命令竞态 / RPC 签名 / 110 Toast 抑制（新增实测）

- **同名官方进程混淆（已修）**：官方 `com.phoenix.read` 与 Guest 进程名相同，旧的
  `ui_guest_running` 只按包名匹配，会把官方进程当成 Guest（实测两者同时存在于 u0_a52/u0_a51）。
  `ui.sh` 现在用 `run-as io.vt.framework id -un` 得到宿主 UID，按 `ps -A -o USER,PID,NAME`
  同时匹配 UID 与进程名；红果 smoke 启动前会 `am force-stop com.phoenix.read` 停掉官方进程。
- **控制命令竞态（已修）**：`stop` 启动的 `DebugControlActivity` 正在 finish 时，紧随的 `launch`
  intent 会被投递到该实例后丢弃（日志只有一次 START，Guest 不启动）。修复：
  `DebugControlActivity` 延迟 900 ms finish 并允许 `onNewIntent` 取消；测试脚本
  `ui_guest_launch/stop/clear` 前等待控制 Activity 从 `dumpsys activity activities` 消失，
  Guest 进程 4 s 未出现时重试一次。修复后 smoke 13/14 通过（feed/播放/下滑/停止重启）。
- **bookmall 走 RPC/TTNet，不是普通 OkHttp**：`reading.snssdk.com/reading/bookapi/bookmall/tab/v`
  由 `com.bytedance.rpc.transport.ttnet` 发送；普通接口 `NetworkParams.tryAddSecurityFactor`
  能生成 `X-Ladon/X-Khronos/X-Argus/X-Gorgon/X-Helios/X-Medusa`（日志 `VT-SIG`）。
  Hook `CronetUrlRequest.addSecurityFactor`、`CronetAppProviderManager`、
  `AbsCronetDependAdapter` 均未命中 RPC 书城请求。
- **请求头对比（只读日志 `VT-HDR`/`VT-OK3`）**：RPC bookmall 在 Retrofit 构建期携带
  `Accept/X-Xs-From-Web/X-SS-REQ-TICKET/x-reading-request/x-vc-bdturing-sdk-version/
  Authorization/Openid/sdk-version/passport-sdk-version`，**未见 Cookie 与 X-Argus/X-Medusa**；
  普通 OkHttp 请求由 `OkHttp3SecurityFactorInterceptor` 补安全头。RPC 可能由 TTNet 原生层后置签名，
  不能仅凭构建期头部判定缺头；在 build 阶段补头实测只会让 110 变成 raw=-1，已撤回。
- **X-Argus 8 字符不是降级证据**：成功返回的 `privacy/setting/query` 同样是
  `X-Argus=8:p4vfag==`、`X-Medusa` 约 1.2 KB，说明短 X-Argus 是该版本的正常值，
  长签名在 X-Medusa；110 不能归因于 X-Argus 被降级。尝试在 Retrofit
  `Request.Builder.build()` 为 bookapi 补同一套安全头后，110 仍存在或变成传输层 `raw=-1`
  （`code=987654321/-1000`），无法证明补签有效；该改写已撤回，不把“添加安全头”当作已修复方案。
- **AppOps 归属 Hook 回退**：新增 44 个 `AppOpsManager` 参数改写后，Guest 启动在
  `GuestSession.initialize` 抛 `AbstractMethodError: AbstractCollection.size()`（连续两次），
  已整段移除；AppOps `SecurityException` 日志仍存在但不阻断主流程。
- **110 客户端提示抑制（兼容性兜底，非服务端修复）**：模块 Hook
  `ToastUtils.showCommonToast[Safely](int)` 与 `Toast.show()` 的文本，并跳过
  `NsBaseNetworkDependImpl.assertIllegalAccess()`（返回 void，原始实现会触发盗版提示/安全模式）。
  仅拦 `0x7f06130f`（`string/cs9`）或含“不安全/安全校验失败”的文本；日志
  `VT-TOAST suppressed` / `assertIllegalAccess skipped`。实测：播放页可正常打开并出画面，
  首启/重启不再出现“当前版本不安全”；`bookmall/tab` 仍 6×110 且“书城”内容可能缺失。
  这是避免误报提示的兼容层，不伪造授权/签名/DRM。
- **番茄 7.4.1.32 当前复测（2026-10-27）**：Guest 成功进入主界面（与官方同机一致，
  `MainFragmentActivity` + `:push` 子进程），书城 ranking 正常加载；匿名主界面上点书卡/分类/
  完整榜单/领取按钮在 Guest 上未跳转，官方同坐标点书卡也不跳转，因此不能直接归因于容器输入丢失；
  可能是匿名态入口、点击坐标或页面行为，需人工/登录态验证；阅读正文与登录态未验证。
- **番茄正文/交互实测（2026-10-27）**：`input tap` 过快时自定义书卡无响应；改用
  `input motionevent DOWN/UP`（约 0.3 s）可打开详情/`ReaderActivity`。官方同书按“下一章”
  能加载第 1 章正文；Guest 按“下一章”触发 `reading/reader/batch_full/v` 110
  `ILLEGAL_ACCESS`，正文不出现，且屏幕中央/上方实际显示“当前版本不安全，请到正规应用市场下载”。
  模块原先只拦 Toast，可见文本仍出现在 Reader 自定义 UI；新增 `TextView.setText` 文本级抑制后，
  日志 `VT-TEXT suppressed` 生效，最新一轮章节测试已无可见“当前版本不安全”。
  `assertIllegalAccess skipped` 已跳过客户端断言；但正文内容仍因 110 不可用。
  另把红果的原生 maps/fd shim 临时启用到番茄：main 仍可进，但章节请求卡在加载占位、
  无 110 返回，说明 shim 破坏了番茄网络路径；已撤回（番茄仅保留无 shim 配置）。
- **抖音容器 vs 官方（2026-10-27 模拟器复测）**：官方 36.4.0 在 MuMu 约 75 s 进入
  隐私弹窗+主界面（进程存活）；容器 Guest g0 仍在 1–2 s 内 SIGSEGV（`SEGV_ACCERR` 匿名执行内存）。
  已逐项 A/B：禁用示例模块、禁用 identity Hook、移除 `Process.myProcessName` native Hook、
  移走 v8/vmsdk/整个 plugins 目录、伪造 `ContextImpl.getDataDir`、对抖音改用 `PathClassLoader`——
  全部仍崩溃（dataDir 伪造还会立即 Java 崩溃）。官方 36.4.0 在 MuMu 接受隐私后进入视频 feed，
  可正常播放，确认模拟器本身可跑抖音。结论：容器 LSPlant/虚拟化环境与抖音 native 反篡改冲突，
  MuMu 上无可验证的容器侧修复。另抓 Metasec `SwitchManager`：抖音实际方法是
  `LIZLLL(I)Z`（不是红果/番茄的 `c`），已 Hook 成功但崩溃前**没有 switch 调用**，
  排除 switch 14。进一步验证旧版 30.0.0（签名与官方一致、MD5 校验通过）：
  容器 Guest g0 **同样 SIGSEGV**，说明反篡改与版本无关，是容器/LSPlant 环境冲突。
  **模拟器上抖音用官方（无模块）可全功能使用，带模块需真机**。
- **登录链路实测（2026-10-27）**：红果 Guest 的 `LoginActivity`/一键登录/协议弹窗均正常；
  点“同意并登录”会启动**官方抖音**的 `com.ss.android.ugc.aweme.account.business.login.DYLoginActivity`
  （手机号/密码/扫码）。无已登录抖音账号时 Guest `LoginActivity` 停在“登录中...”，
  再停官方抖音后界面恢复但无 token。官方 `TokenObjectProvider` 与
  `AuthTokenMultiProcessSharedProvider` 在 36.4.0 manifest 中均 `exported=false`，
  跨 UID 直读 token 不可行；登录只能依赖官方 `DYLoginActivity` 的 ActivityResult。
  需真实抖音账号登录后才能验证结果是否回到 Guest、以及正文 110 是否随账号态消失。
  番茄登录同属抖音账号体系，未复测。
- **官方同机匿名 A/B（2026-10-27）**：官方红果“听书”页正常加载书单/评分，官方番茄
  第 1 章正文正常，官方抖音 feed 正常；容器同机同网络下 bookmall/`reader/full` 110、
  抖音 g0 SIGSEGV。说明 110 是**容器环境特征**，不是账号或整机风控；登录不一定能消除，
  登录链路还受官方账号 Provider `exported=false` 限制。
- **红果旧版 6.9.9.32 容器测试（2026-10-27）**：从豌豆荚获取、MD5/证书与官方一致；清数据首启
  首个 `bookmall/tab/v` 返回 100103（通过），点“小说”页后同接口仍 110
  `ILLEGAL_ACCESS`。旧版不能稳定绕过，已恢复官方 7.4.1.32 与 Guest 记录。
  说明 110 是**逐请求/环境触发**，不是单一版本问题；版本回退不是可靠方案。
- **番茄 7.1.9.32 当前 profile 测试（2026-10-27）**：旧实例曾可读；当前干净 profile 上
  bookmall/书城仍 110，说明旧实例可读是**profile/身份特定**，不是版本能力。已恢复官方
  7.4.1.32 与 Guest 记录。版本回退（红果 6.9.9.32、番茄 7.1.9.32）均不能稳定消除 110。
- **旧 SM-S9110 profile（实例 0）复测（2026-10-27 04:58）**：番茄 7.1.9.32 的
  `bookmall/tab` 与 `reader/full` 现在也返回 110；旧实例此前的可读性已不可复现。
  所有 profile/版本（6.9/7.1.9/7.4）均被服务端标记，确认 110 是**容器环境/身份级**，
  不是 profile 或版本能力。
- **模拟器推荐用法（2026-10-27）**：抖音用官方 36.4.0（已实测进入 feed/播放，无模块）；
  红果/番茄完整内容用官方 app；容器用于红果/番茄主流程与模块实验（红果 feed/播放、
  番茄 main/Reader，服务端 110/风控仍在）。带模块的抖音在 MuMu 无解，需真机/换 hook 引擎。
- **抖音 36.4.0 重新导入复测（2026-10-27）**：导入成功（actions=1382）。主进程
  `io.vt.framework:g0` 仍原生 SIGSEGV（ExitInfo reason=SIGNALED status=11）。push 进程原先在
  `TokenObjectProvider.query` 的 Bytex/lancet 包装 ↔ twin 自查询中 `StackOverflowError`
  （stack size 1038KB）；进一步发现容器的 `Hooker` 用全局 `inCallback` 直接抛
  `IllegalStateException: Recursive hook invocation`，把合法的 Provider 重入也打断了。
  已保留 `Hooker` 线程内回调深度计数（上限 64，允许嵌套 hook）；先后尝试 Provider 实例、
  Provider 类名、ContentResolver 三层重入保护，仍会 `Hook callback depth exceeded`，已全部撤回。
  抖音主进程 g0 的 MuMu/houdini SIGSEGV 仍是当前硬阻塞，push 修复需真 arm64 重新验证。
  Tombstone（io.vt.framework:g0）为 `signal 11 SEGV_ACCERR`、fault addr 匿名映射、
  “trying to execute non-executable memory”、仅 1 帧 `<anonymous>`，x86_64/houdini；
  禁用示例模块后 g0 仍约每 1.7 s SIGSEGV 重启，**排除模块 Hook**，指向容器自身 Hook/
  翻译层与抖音 native 的交互，MuMu 上无可行修复。同机官方 36.4.0 可正常到 `SplashActivity`
  （进程存活、无 SIGSEGV），进一步确认崩溃是容器环境特有，而非 MuMu/houdini 通病；
  需真 arm64 + 容器原生兼容性修复。
- **当前最佳状态**：干净实例 + 全量 maps/fd shim，红果 feed/下滑/停止重启可用；
  smoke 多次复跑 11–12/1（“全屏观看”入口偶发未触发，属交互/页面差异，非崩溃；手动点击可进播放页）；
  bookmall 110 为触发式（前几轮 6 次，最近一轮 0 次），Toast 已被抑制、无可见“当前版本不安全”；
  点“商城”页签会弹滑块“请完成下列验证后继续 / 验证过于频繁，请稍后重试[5009][502]”，属服务端风控，
  未自动绕过；商城内容在有验证/冷却时不可用，其余主流程无可见盗版提示；
  5 分钟播放页巡检 0 FATAL、1×110（6 次 Toast 抑制）；番茄 5 分钟 main 巡检 0 FATAL、0×110；
  容器只有 g0/g1 两个槽：番茄 main+`:push` 会占满两槽，红果 launch 报“两个容器进程都在运行其他应用”；
  测试前用 `ui_stop_other_guests` 释放，本次红果回归 11/1；
  官方同机（同样未登录）“听书”页正常，排除登录态；剩余是逐接口服务端风控/环境校验，
  需真 arm64 设备复测。

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
- **Native/ABI**：抖音几乎全 arm64；MuMu（Android 15）上用 houdini 转译可运行官方版，
  但容器版 36.4.0 在 Splash 后 `io.vt.framework:g0` 原生 SIGSEGV（ExitInfo `SIGNALED status=11`），
  官方版同机可到隐私页。真实设备 40.7.0 的旧成功记录需在目标版本复测；x86_64 模拟器不能作为结论。

### 番茄小说（`com.dragon.read`）/ 番茄免费短剧（`com.phoenix.read`）

- 启动、隐私同意、推荐流、短剧播放、番茄正文阅读均已实测跑通（见 3.6.4）；
- **MuMu 容器实测（2026-10-26）**：番茄 7.1.9.32 书城/详情/第 1 章正文可读；连续 3 次
  PID stop/relaunch 110=0，第 4 次启动出现 1 次 `bookmall/tab` `ILLEGAL_ACCESS`（Toast 一闪）。
  说明服务端拒绝是触发式/环境相关，不能承诺“永不弹提示”；
- **剩余阻塞：服务端风险状态**——一次会话后 install_id/设备维度可能被标记，
  下一次启动会被 `当前版本不安全` 拦截；清空 Guest 数据可恢复首会话可用状态；
  清除 cookie 实测**不能**恢复，说明不只是 cookie 层状态；
- 账号登录与支付链路、长时间稳定性、子进程 Provider/Receiver、DRM/版权视频同样未验证；
- 这两款应用大量使用 Tinker/MuteApplication、Lego 启动任务与多进程，版本升级后需重新回归；
  Metasec 采样与开关 ID 属实现细节，应用升级后可能变化，需要重新回归。

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

## 7. 2026-10-27 宿主 UID A/B 与 Metasec 采集诊断

### 7.1 宿主 UID/包名/dataDir 不是 110 根因

为排除“宿主身份被服务端标记”，临时在 debug 构建加 `applicationIdSuffix = ".alt"`，
生成 `io.vt.framework.alt`（新 UID u0_a52、dataDir `/data/user/0/io.vt.framework.alt`），
与主容器共存并导入番茄 7.4.1.32。

- Guest 能正常进 main，`reading/bookapi/bookmall/tab/v` 响应 `code=110 ILLEGAL_ACCESS`，
  日志仍有 `VT-NET assertIllegalAccess skipped`，与主容器表现一致。
- 结论：**110 与宿主包名、UID、dataDir 无关**，是 Guest 运行环境/身份级信号。
- 已回滚 `applicationIdSuffix`（`app/build.gradle.kts` 的 debug 为空块），卸载 alt 容器；
  Manifest 中 StubActivity 的 `${applicationId}.guestN` taskAffinity 参数化保留（主容器值不变）。

### 7.2 签名形态与 bypass 头的定性

- `x-metasec-bypass-mssdk=1` 由应用自身 `vz3.a` 添加：仅当 `NsCommonDepend.IMPL.getSecHeader()`
  返回非空时才追加，属于正常“应用自签名”路径，**不是 SDK 降级标志**。
- 红果/番茄请求的 `X-Argus=8:<4 字节 base64>`（如 `8:X8Lfag==`）是模拟器上的常态短值；
  此前成功的 privacy 请求同样如此，**不能作为降级或 110 的证据**。
- 签名链：`NsCommonDependImpl.getSecHeader` → `MSManagerUtils.get` → `f3.getFeatureHash`
  → native `ms.bd.c.k3.a(33554438, …)`（`libmetasec_ml.so`）。
- Guest 进程 maps 中没有 `lsplant`/`dobby`/`xposed` 库名，容器 Hook 框架库已隐藏。

### 7.3 Metasec 采集 CCE：已修但不是 110 根因

红果 Guest logcat 出现：

```
Key SS_VERSION_NAME expected String but value was a java.lang.Integer.
java.lang.ClassCastException: java.lang.Integer cannot be cast to java.lang.String
  at android.os.BaseBundle.getString
  at ms.bd.c.j3.d
  at ms.bd.c.d3.a
  at ms.bd.c.k3.b
  at ms.bd.c.k3.a(Native Method)
  at ms.bd.c.g3.a / ms.bd.c.g3.run
```

`BaseBundle.getString` 内部捕获 CCE 后返回默认值 `<null>`，采集不中断，只是 versionName 缺失。

已实现 `GuestRuntime.hookMetasecBundleVersion`：Hook `android.os.BaseBundle.getString`，
仅当 key 为 `SS_VERSION_NAME` 且取到的值不是 String 时，返回 Guest 记录的 `versionName`。

- 实测 `metasec version-name shim installed=2`，X-Argus 形态与 bookmall 110 均无变化。
- 结论：该 CCE 是采集噪声，**不是 110 根因**；签名/环境判定仍在 native 或服务端。
- 不得把此 shim 宣传为“110 已修复”。

### 7.4 Metasec 初始化与签名路径诊断（2026-10-27）

在容器中加入只读观测后，红果 7.4.1.32 / MuMu 实测：

- `MSManagerUtils.init` → `result=true`，Metasec 初始化成功，排除“初始化失败→降级”。
- `SS_VERSION_NAME` shim 命中（`was=Integer` → `7.4.1.32`），但 X-Argus 形态与 110 均无变化。
- `ms.bd.c.f3.getFeatureHash` 的 Hook 已安装但**从未被调用**；红果的 X-Argus/X-Medusa
  来自 TTNet `NetworkParams.tryAddSecurityFactor` 路径，而不是应用的
  `NsCommonDependImpl.getSecHeader`。后续排查应聚焦 TTNet/native 签名。
- Guest 内 maps shim 确认生效：`native-trace.log` 记录
  `maps filtered /proc/self/maps bytes=519057->475436`，`trace hook getuid/fopen/stat/access/...=0`。
  从 adb 外部读 `/proc/<pid>/maps` 不经过 Guest shim，因此不能据此判断泄漏。
- 新可疑信号：Guest 内 `getExternalFilesDir` 返回容器真实路径
  `/data/user/0/io.vt.framework/files/guests/com.phoenix.read/data/external/...`，
  真机应为 `/storage/emulated/0/Android/data/com.phoenix.read/files`；
  native `getuid()` 返回宿主 UID（u0_a51 / 10051）。

待验证方案：向 app 暴露“真机视图”的 external/data 路径，并在 native
`open/openat/fopen` 中把假路径重写回容器路径；风险是 IO 重定向不完整会卡加载
（番茄 7.1.9 的 maps/fd shim 先例）。

### 7.5 现状与下一步

- 红果/番茄容器仍 `bookmall`/正文 110；抖音容器仍 MuMu SIGSEGV 硬阻塞。
- 110 已排除：版本、profile、宿主 UID/包名/dataDir、assertIllegalAccess、STUB 槽位、
  模块开关、Metasec 初始化失败、`getFeatureHash` 路径、`SS_VERSION_NAME` 采集异常。
- 下一步：TTNet/native 签名与 Metasec 风险上报字段；或外部路径“真机视图 + IO 重定向”；
  或对比官方同机 Metasec 上报（当前无 frida/真机），或验证账号态。
- 不得把现有 shim 宣传为“110 已修复”。

## 8. 轻量化虚拟机方案实验（2026-10-27）

用户提出改用“VM 内可 root”的轻量虚拟机方案。已在 MuMu 之外创建 Android 14
Google APIs x86_64 AVD（`vtvm34`，SDK 镜像自带 `libndk_translation`，可跑 arm64），
并安装官方原版红果/番茄 7.4.1.32、抖音 36.4.0。

### 8.1 实测结果

| 应用 | AVD 表现 |
| --- | --- |
| 红果 | 首页推荐流正常加载（无容器 110“网络出错”），仅视频子请求“网络异常” |
| 抖音 | 进入主界面、隐私弹窗后 feed 正常播放；**无容器 SIGSEGV** |
| 番茄 | main/书城正常；ReaderActivity 点“下一章”出现“当前版本不安全，请到正规应用市场下载”，正文不显示 |

### 8.2 环境与 root 伪装

- AVD 特征：`ro.debuggable=1`、`ro.kernel.qemu=1`、`ro.hardware=ranchu`、
  fingerprint `google/sdk_gphone64_x86_64/emu64xa:14/.../userdebug/dev-keys`、
  installer=null、`ro.dalvik.vm.native.bridge=libndk_translation.so`。
- 需 `emulator -writable-system` + `adb remount`（overlayfs）+ reboot 才能写 system；
  `ro.debuggable` 改 0 成功；`ro.kernel.qemu`/`ro.hardware`/fingerprint 由 emulator
  qemu-props 注入，改 build.prop 无效；`pm set-installer` 因镜像无 vending 失败。

### 8.3 结论

- VM + root 不等于自动过检测：裸 AVD 上番茄正文仍被判“版本不安全”，与容器同为环境级判定。
- 抖音在 AVD 上无 SIGSEGV，说明 VM 能绕开容器 LSPlant 与 native 反篡改冲突；但要让红果/番茄
  完全可用，还需伪装 qemu-props/内核 cmdline、真机指纹、安装来源、ARM 翻译层，或改用成熟商业 VM。
- AVD 启动命令：`emulator -avd vtvm34 -no-snapshot -no-audio -gpu swiftshader_indirect -memory 4096 -cores 4 -writable-system`。

### 8.4 与 MuMu 的伪装对照（2026-10-27）

同机 MuMu（官方番茄正常）与 AVD（官方番茄“版本不安全”）的关键差异：

| 属性 | MuMu（正常） | AVD（被拒） |
| --- | --- | --- |
| `ro.kernel.qemu` | 未设置（空） | `1` |
| `ro.hardware` | `vivo` | `ranchu` |
| fingerprint | `vivo/PD2245/PD2245:15/...:user/release-keys` | `google/sdk_gphone64_x86_64/emu64xa:14/...:userdebug/dev-keys` |
| model | `V2245A` | `sdk_gphone64_x86_64` |
| installer | null | null（两边都为 null，不是判定依据） |
| ABI | `x86_64,arm64-v8a,x86` | `x86_64,arm64-v8a` |

AVD 运行时伪装尝试与结果：

- `emulator -prop ro.kernel.qemu=0 / ro.hardware=qcom / fingerprint`：**无效**，属性仍为 ranchu/qemu/sdk。
- 修改 `/system/build.prop`（`-writable-system` + remount）：`ro.debuggable` 可改（但改 0 后 `adb root` 失效）；`ro.kernel.qemu` 由 qemu-props 服务注入，改 build.prop 被覆盖；`ro.hardware` 来自 `ro.boot.hardware`（kernel cmdline `androidboot.hardware=ranchu`）。
- `-qemu -append "androidboot.hardware=qcom"`：AVD 内核无法启动（adb offline）。
- `pm set-installer` / `adb install -i`：被 Android 14 证书策略拒绝（即使 root）。
- 结论：要使 AVD 接近 MuMu，必须**离线修改镜像/内核 cmdline/qemu-props**（如 WSL2 挂载并修改 system.img/vendor.img 与 bootimg），运行时伪装不可行；AVD 已恢复正常启动（`-wipe-data` 后数据已清空，三款 app 需重装）。

### 8.5 最终尝试与 MuMu root 开关（2026-10-27）

- emulator 37.3.5 的 `-prop` 对 ro.* 无效；`-append-userspace-opt androidboot.hardware=qcom`
  会导致 AVD 内核无法启动（adb offline）；`androidboot.hardware` 必须为 ranchu，
  属性由 bootconfig + qemu-props 固定。
- 本机 WSL2 执行 `wsl -d Ubuntu` 报 `Wsl/Service/E_UNEXPECTED`（Ubuntu 状态 Running 但命令失败），
  无法用 WSL 挂载 ext4 镜像做离线修改。
- MuMu 的 root 开关已定位：`D:\Program Files\Netease\MuMu\vms\MuMuPlayer-15.0-1\configs\vm_config.json`
  的 `"root": "false"`，以及 `customer_config.json` 的 `"root_mode": "0"`；
  改为 `true`/`1` 并重启 MuMu 即可开启 root（会影响当前容器环境，需用户确认）。
- 结论：在 Windows x86_64 上，裸 AVD 无法复制 MuMu 的真机伪装；MuMu 已是
  “成熟 VM + vivo 真机伪装”，官方三款全功能，开启其 root 是当前最可行的“VM + root”路线。

### 8.6 开源两仪（Twoyi）实验（2026-10-27，失败）

用户澄清目标是用 VM 方案替代容器（不是给 MuMu 开 root），因此测试开源两仪：

- 下载 twoyi 0.5.4（209MB，内置 Android 8.1 arm64 ROM，`user/test-keys`、houdini 转译）。
- 安装到 MuMu（Android 15 x86_64）并启动 `io.twoyi.Render2Activity`，前台成功，
  但初始化 Android 8.1 时 ART 崩溃：
  `Unable to create protected region in stack for implicit overflow check`、
  `Out of memory size: 4096`、DALVIK THREADS dump，随后两仪进程退出。
- 三款 APK 的 minSdk 均兼容（红果/番茄 21、抖音 23），问题在两仪本身：
  版本停更（2021）与 Android 15 宿主/houdini 不兼容。
- 结论：两仪当前版本在 MuMu 内不可用；需要更新维护的商业 VM（VMOS/VPhoneGaGa）
  或独立 QEMU + Android-x86，或修复 AVD 离线镜像伪装（WSL 当前 E_UNEXPECTED）。

### 8.7 Magisk resetprop 实时伪装 AVD（2026-10-27，关键进展）

AVD 的属性虽由 bootconfig/qemu-props 固定，但可用 Magisk 的 `resetprop` 在运行期直接改
只读属性（无需改镜像）：

1. 下载 Magisk v31.1，解压 `lib/x86_64/libmagisk.so`，push 到 AVD `/data/local/tmp/magisk`。
2. `adb root`（AVD userdebug，debuggable=1 时可用），执行：
   `magisk resetprop ro.hardware vivo`、`ro.kernel.qemu 0`、
   `ro.build.fingerprint vivo/PD2245/PD2245:15/V417IR/1171:user/release-keys`、
   `ro.product.model V2245A`、`ro.product.device/name PD2245`、`ro.build.tags release-keys`、
   `ro.boot.hardware vivo`、`ro.boot.qemu 0`。
3. 验证 `getprop`：`ro.hardware=vivo`、`ro.kernel.qemu=0`、fingerprint/model/device/tags
   全部为 vivo PD2245 值——与 MuMu 一致。
4. 番茄重装后能正常进入 main（书城/榜单加载）。

- 注意：resetprop 只在运行期生效，重启丢失；需在每次启动后重新执行（可写成 boot 脚本/模块）。
- 待验证：番茄正文（兴趣引导弹窗需先选类型再关闭，本轮未完成）；若通过，再测红果/抖音。

### 8.8 AVD 属性全量对齐后的正文测试（2026-10-27）

在 resetprop 基础上，继续把 Build 字段与 MuMu 对齐：

- `ro.build.id=V417IR`、`ro.build.display.id=V417IR release-keys`、
  `ro.build.version.incremental=1171`、`ro.build.version.release=15`、
  `ro.build.flavor=missi_V2245A-user`、`ro.build.product=PD2245`、`ro.build.type=user`、
  `ro.product.brand/manufacturer=vivo`、`ro.product.board=V2245A`、
  以及 `ro.odm/product/system/vendor.build.fingerprint` 全部改为
  `vivo/PD2245/PD2245:15/V417IR/1171:user/release-keys`。

结果：

- 重启番茄后，ReaderActivity 不再出现“当前版本不安全，请到正规应用市场下载”，
  改为“章节加载异常，请先看其他章节”——**版本/环境检测已绕过**。
- 但连续点击“下一章”仍显示“章节加载异常”，`reader/full/v` 请求没有成功返回正文。
- 日志无 `ILLEGAL_ACCESS`/网络错误；候选原因：服务端仍对 reader/full 做内容风控、
  未登录账号、x86_64 + libndk_translation/模拟器其他特征。
- 下一步：登录账号验证（需用户本人操作）、抓 reader/full 响应（需 app 内 hook/证书）、
  或继续伪装 ABI/传感器/GPU 等特征。当前 AVD 保持属性伪装状态，番茄停在阅读页。

### 8.9 全量伪装后三款应用复测（2026-10-27）

在 resetprop 全量对齐 vivo PD2245（含 Build 字段、ABI 补 x86）后：

| 应用 | 结果 |
| --- | --- |
| 抖音 36.4.0 | 正常：隐私弹窗后 feed 视频播放，无 SIGSEGV |
| 番茄 7.4.1.32 | “当前版本不安全”消失；正文 `reader/full` 仍“章节加载异常”（多章一致） |
| 红果 7.4.1.32 | 启动 ANR/白屏：`TNCManager.onOk3Response` 锁竞争、AssetManager 慢，Input 超时 |

- 红果 ANR 可能与 `ro.build.version.release=15` 与系统实际 14 不一致（app 按 Android 15
  分支执行）或 ARM 翻译下首次启动慢有关；需要回退 Build 版本字段、只保留
  hardware/fingerprint/model 伪装后复测。
- 结论：VM + resetprop 能绕过版本检测（番茄文案变化已证明），但全量伪装会带来新的稳定性问题，
  必须按应用最小化伪装字段。

### 8.10 当前状态

- AVD `vtvm34` 保持 resetprop 伪装（重启丢失）；抖音可正常播放；番茄版本检测绕过但正文待解决；
  红果启动异常待回退属性复测。
- MuMu 环境未改动；容器方案代码与基线仍在。

### 8.11 红果启动慢的定位（2026-10-27）

- 把 resetprop 全量回退到 AVD 原始属性（ranchu/qemu=1/sdk 指纹）后，红果仍然启动 48s+
  白屏、`mCurrentFocus=null`，进程存活、无 FATAL。
- 日志：`hasPermission` 长锁竞争（1-2s）、`SharedPreferencesImpl.loadFromDisk` 慢、
  GC 释放 38MB 耗时 18.8s、Widevine DRM 初始化；期间 AVD 内存 3921MB 已用 3766MB。
- 停掉抖音/番茄后内存回到 2430MB，红果仍慢，排除单纯内存耗尽，但 4GB 对红果 +
  ARM 翻译仍偏紧。
- 结论：红果在 AVD 上的启动瓶颈不是 resetprop 属性，而是 AVD 4GB RAM + libndk_translation
  下红果自身初始化慢（与容器 110 无关，但也未达到可用）。建议：AVD 内存调到 8GB 后再试，
  或红果继续用 MuMu 官方；番茄正文需登录/抓包定位。抖音在 AVD 上已可用（feed 播放）。

### 8.12 8GB 内存试验与 AVD 恢复（2026-10-27）

- 将 `config.ini` 的 `hw.ramSize` 改为 8192 后启动，emulator 进程直接退出
  （日志仅 netsim 初始化，无明确错误；宿主空闲 15GB），疑为 emulator/QEMU 在 Windows
  上分配 8GB 连续内存失败或与 MuMu 的虚拟化共存冲突。
- 已改回 4096 并重启成功；三款 app 保留。
- 期间 AVD 出现过 `Can't find service: window`（system_server 异常），重启后恢复。
- 当前 AVD：4GB、原始属性（ranchu/qemu=1/sdk 指纹）、三款 app 已装、
  vivo 伪装脚本 `tmp_vm/vivo.sh` 可一键重新应用（需先 `adb root`）。
- 红果在 AVD 上的可用性仍未验证成功；抖音已确认可用；番茄正文待登录/抓包。

### 7.6 模块关闭 A/B 与红果当前状态（2026-10-27）

- 关闭 `sample-module` 后重启红果 7.4.1.32：首页显示“网络出错，请点击重试”，
  底部“首页/剧场/商城/福利/我的”正常；恢复模块后仍是同样错误。
- 模块恢复后 VT-SIG 正常（`X-Argus=8:T8Tfag==`，X-Medusa≈1.2KB），说明签名链路仍在工作。
- 结论：**首页/书城加载失败与 sample-module Hook 无关**，110 是容器环境级的服务端判定。
- 状态修正：红果容器当前首页内容也不可用（不仅书城/正文），不能沿用“主流程稳定”的旧结论；
  之前 smoke 通过可能依赖缓存或不同接口时机，需要重新以冷启动为准。

### 7.7 Hook 变量 A/B：110 与容器 Hook 实现无关（2026-10-27）

在红果 7.4.1.32 冷启动下依次关闭两类 Hook：

1. 关闭容器对 Metasec 自身方法的 Hook（`ms.bd.c.r2.a` URL 事件、
   `SwitchManager.c(14)`、`BaseBundle.getString` / `MSManagerUtils.init` / `f3.getFeatureHash` 观测）
   → `bookmall/tab/v` 仍 `code=110`。
2. 关闭 native shim（`installEnvShim` / crash handler / native trace，即 Dobby 对
   `open/openat/fopen/stat/access/opendir/getuid/system/popen` 的 inline hook 与 maps 过滤）
   → 冷启动后 `bookmall/tab/v` 仍 `code=110`（确认新进程没有 `env shim installed` 日志）。

结论：110 与「Hook Metasec 自身方法」和「Dobby inline hook」均无关。
剩余最可能的面是 **LSPlant 对系统 API（PackageManager/Context/Process/ActivityThread）的 Java Hook
被 Metasec 交叉验证/检测**，或 Metasec 上报的环境风险字段本身。两者都无法通过简单开关验证：
系统 API Hook 是容器虚拟化的基础，去掉即无法运行。

A/B 结束后已恢复基线代码（shim、Metasec shim、观测 Hook 全部恢复）并重新构建安装。

### 7.8 dataDir 真机视图：首页恢复但冷启动卡死（2026-10-27，已回滚）

单变量改动：仅将 `GuestSession.buildApplicationInfo` 中
`ApplicationInfo.dataDir` / `deviceProtectedDataDir` 改为真机路径
（`/data/user/0/<guest>`、`/data/user_de/0/<guest>`），
**不改 Context 的文件 API**（getFilesDir 等仍返回容器真实路径）。

红果 7.4.1.32 冷启动实测：

- 通知权限弹窗关闭后，首页“推荐”流正常加载（短剧封面、点赞/收藏数、简介），
  不再出现此前冷启动的“网络出错，请点击重试”。
- 日志中没有新进程的 `VT-NET code=110`（最后一条仍是改动前的 05:23 旧进程）。
- 对比：改动前同一冷启动流程首页为空/网络错误；改动后首页内容正常。

推断：Metasec 可能通过 `ApplicationInfo.dataDir` 的容器路径
（`/data/user/0/io.vt.framework/files/guests/...`）识别虚拟化环境，是 110 的候选信号。

**复验结果（同日）**：第二次冷启动卡死——Guest Application 在 05:27:26 创建后
不再有 Activity/请求日志，界面持续黑屏。说明应用（tinker/stub 或 SDK）会用
`applicationInfo.dataDir` 拼真实文件路径，指向不存在的 `/data/user/0/com.phoenix.read` 后启动阻塞。

处理：已回滚为 `record.dataDir`（容器真实路径）并重新构建安装。
结论：dataDir 路径值得继续研究，但**必须配合完整的文件 IO 重定向**
（把 `/data/user/0/<guest>` 的读写映射回容器目录）后才能对应用暴露真机视图；
单独改 `ApplicationInfo.dataDir` 不可用。首轮的首页恢复也不排除缓存/时序因素，不能作为 110 已消除的证据。
