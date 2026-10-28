# 路线图

目标不是“再做一个多开”，而是可在真机上稳定加载主流 Xposed 模块的免 root 容器。
当前 0.1 只完成了活动主链路；以下按对“抖音/番茄可用”的阻塞程度排序。

## 已完成（2026-10-25，主进程）

- ActivityThread 虚拟化 + 多 Activity / 返回栈；
- 系统 Provider 的宿主 attribution（不再依赖 Settings shim）；
- Guest Provider 本地 Transport 路由与 onCreate 初始化；
- Guest Service 的 start / bind / unbind / stop 本地生命周期；
- 动态广播注册/投递 + 静态 Receiver 的 AXML 解析/注册（Guest 内部 action）；
- Guest 原生库加载（JNI 验证）；
- 应用更新保留数据与模块作用域；
- LSPlant Xposed 运行时与示例模块实例 Hook；
- 模块加载提前到启动 Activity 实例化之前，构造方法 Hook 已命中（NewPipe `MainActivity.<init>`）；
- 通知渠道 / LocaleManager / MediaSession / PendingIntent 的宿主身份重映射；
- JobScheduler 实际实现类（`JobSchedulerImpl`）空实现，WorkManager 启动链路可通过；
- 真实第三方应用 NewPipe 0.29.1：主界面/设置页/PlayerService + 模块 Hook 验证；
- 真实第三方应用 Telegram 13.1.0：activity-alias 解析、Application 提前创建、原生库/引导页/权限/Proxy Settings；
- **Android 16 arm64 真机：抖音 40.7.0 导入、推荐流/视频播放、Push/WsChannel 跨进程 Service、native Hook 兼容修复**；
- **抖音 Xposed 模块注入：示例模块命中 MainActivity.onCreate 并显示可见浮层；模块 APK 更新后的路径回退**；
- Guest 前台服务通知：宿主身份真实通知、图标/渠道改写、点击回跳、stopForeground/停止容器清理；
- Guest 子进程 Activity：按 `processName` 路由到 `:g0/:g1`，独立 Application/模块加载，停止时双进程退出；
- **activity-alias 启动保真**：保留 alias 组件语义、实例化映射 `targetActivity`；抖音/番茄/Telegram 均按真实 launcher 启动；
- **番茄小说 7.4.1.32**：自然 Splash → 隐私同意 → 书城推荐流 → 书籍详情 → **正文阅读**
  （屏蔽 Metasec 反 Hook 采样后实测；服务端风险状态滞留时需清除 Guest 数据，见兼容性 3.6.4）；
- **红果免费短剧 7.4.1.32**：自然 Splash → 隐私同意 → 推荐短剧流 → 全屏播放 → 下滑切换；
  Toast 归属校验崩溃已修复；
- **抖音短信登录容器侧修复**：`execStartActivity` 保留 Guest Activity 作为结果接收者，示例 v17 验证
  `onActivityResult` 往返；抖音登录页可打开，验证码页待真实手机号复测；
- **Toast 身份修复**：`Toast$TN` 包名/Context 宿主化，解决番茄系初始化 Toast 触发 WindowManager 拒绝崩溃；
- **字节系初始化兜底**：RPC `g4.c`、TTAccount `p5.b`、HTTP `h1.e`（TTNet/Cronet）。

## P0：让主流应用能启动、能导航

1. **多进程 Guest**：Activity 子进程与跨进程 Service（StubService + Binder 透传）已完成；
   继续做跨进程 Provider/Receiver、>2 进程槽，以及每个 Guest 进程独立模块作用域与生命周期。
2. **跨应用广播**：将 Guest 隐式广播按 action 分类，内部定向宿主、外部走系统身份。
3. **前台服务语义与系统调度**：真实通知展示/点击回跳/停止清理已完成；剩余 AMS 前台服务登记
   （oom adj、5 秒规则）、真实 JobScheduler/AlarmManager 调度、账户等系统服务的身份校验。
4. **签名与反篡改兼容**：补全 `SigningInfo`、PMS 查询、`getInstalledPackages` 过滤、ApplicationInfo 元数据；
   为签名校验类 SDK 提供 profile。
   **已完成的客户端部分（2026-10-26）**：PackageManager 视图自洽性系统性修复，
   示例应用内置 28 项交叉比对断言（容器 / 普通 / 真机已卸载三场景均 `passed=28 failed=0`，
   Android 15 模拟器与 Android 16 arm64 真机 eb72c928 均已复跑）。
   修复覆盖 flags 语义、组件级查询、metaData、签名自洽、UID 反查、宿主包隐藏，
   以及此前完全绕过容器的静态查询路径 `PackageManager.getPackageInfoAsUserCached`；
   另修正了 `before` 回调用 `throw` 短路会被 `Hooker.invoke()` 回滚、
   导致目标应用真机已安装时严格语义失效的问题。
   仍未覆盖：服务端签名校验、真机完整性校验类 SDK。
5. **真实设备兼容实验室**：Android 16 arm64 HyperOS 真机已跑通抖音主流程、番茄小说/红果短剧
   启动与浏览；继续补抖音短信登录复测、反作弊/长稳，以及每应用独立 profile。
6. **启动回归、反 Hook 采样与稳定性（2026-10-26 已修部分）**：`ActivityTaskManager` 归属校验、
   Toast `enqueueToast` 崩溃、Metasec 调用栈采样均已定位并修复，红果可浏览/播放、
   番茄首会话正文可读（见兼容性 3.6.4）。
   **第四批（2026-10-26）**：
   - 修复媒体键 `ISessionManager` 归属（按暂停键不再杀 Guest）；
   - `stop` 改为 PID 终止并等待退出，`clear` 确认退出后再删数据（旧广播在进程改名后不可达）；
   - 静态 Receiver 用 Guest classloader 接管，修复 `t41.c` ClassNotFound 与“VT 反复停止”；
   - 干净对照确认：官方直装同机 3 次重启正常，而 Guest 真正清空数据/无残留后
     首个 `bookmall/tab` 仍被 `ILLEGAL_ACCESS` 拒绝并弹盗版 Toast；
   - 每 Guest 独立 Android ID 实验无效，已撤回；LSPatch 改签在 MuMu 受 houdini 限制，
     需真 arm64 设备验证。
   - **番茄 7.1.9.32（MuMu）**：书城/详情/第 1 章正文可读；多轮 stop/relaunch 中
     110 次数在 0–3 次间波动，触发式而非永久首会话。
   - **抖音 36.4.0（MuMu）**：官方直装能到隐私页；容器版 `io.vt.framework:g0` 在 Splash 后
     约 1 秒原生 SIGSEGV（ExitInfo `SIGNALED status=11`），无 Java FATAL。
     ContentProvider 改为传 Application 后 Aha/EC 的 ClassCastException 消失，
     但仍 SIGSEGV。AppOps 包归属错误只是症状；AppOps 改写尝试未生效，已撤回。
     真实设备 40.7.0 需目标版本复测。
   - **签名路径诊断**：CronetUrlRequest/CronetAppProviderManager 的安全头 Hook 已安装
     但从未命中，x-argus 不在该 Java/Cronet 桥生成，无法用此方式证明降级。
   - **2026-10-27 干净 profile + native 路径虚拟化 A/B**：新 MuMu profile（PD2245，
     adb 16416）+ maps/fd 路径 shim → 红果启动/feed 110=0，连续 3 次 force-stop 重启仍 0；
     点“全屏观看”时 `bookmall/tab` 仍 2×110/网络错误；同 profile 官方直装播放正常。
     关闭 shim 后 Guest 启动约 1 秒原生崩溃（ExitInfo `APP CRASH(NATIVE)` status=6），
     说明 shim 是启动必需。原生 trace 抓到 SecSDK 读 `/proc/self/maps`、枚举
     `/proc/self/fd` 并 readlink 到宿主 APK/容器路径；getuid 仅框架调用。
     剩余 bookmall 110 指向宿主 UID/host APK 签名/dataDir 或服务端 device risk。
   **仍阻塞**：免 root 容器无法同时满足真实 UID/数据目录，服务端风控无法从本地彻底消除；
   不得绕过付费/会员/DRM。
7. **改包路线验证（P0 备选）**：已在本地生成红果/番茄 7.4.1.32 的 LSPatch 本地模式包
   （无嵌入模块，见 TESTING 9.10）；MuMu 因 houdini 无法运行。下一步在真实 arm64 设备上
   优先用第二用户安装，验证本地签名校验、首屏/正文/播放与服务端是否接受改签包；
   LSPatch 0.398 的 LSPlant 对 Android 16 的兼容性未知。若可用，再评估将其作为
   “可选改包模式”接入项目（不用于绕过付费/DRM；需用户明确确认安装范围）。

## P1：模块与稳定性

- ~~APK 体积裁剪~~（已完成 R8 + ABI 分包，约 2.8 MB/ABI；正式发布仍需移除 debug bridge、配置 release 签名）；
- ~~Guest 崩溃日志持久化~~（已加入原生 SIGSEGV/SIGABRT 探针，写 maps+PC 到
  `files/logs/native-crash.log`；MuMu/houdini 下抖音崩溃未命中，待真机验证）；
  进程槽回收、ANR 诊断；
- 模块设置 Activity 在容器内运行，或模块 prefs 镜像导入；
- 应用导入进度/失败原因 UI、系统/游戏过滤开关；
- 自动更新策略、签名变更提示、空间占用清理；
- 应用数据备份/恢复；
- 16 KB page size、Android 16/17 适配验证。

## P2：安全与工程化

- 宿主/Guest 文件隔离（当前同 UID，Guest 可读宿主私有文件）；
- 反调试/反容器研究仅用于兼容性测试与防御验证，明确合规边界；
- 单元测试：GuestImporter 更新保留数据、ModuleStore 扫描、ModuleLoader 入口解析、Hooker 参数分发；
- CI：构建全 ABI、lint、native 符号检查、模拟器 smoke test；
- 贡献指南、issue 模板、模块兼容性提交模板。

## 非目标

- 游戏多开/游戏外挂；
- 绕过付费、破解会员、规避平台风控；
- 未经授权对第三方应用进行逆向或再分发。
