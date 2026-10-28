# VT LightVM：自研轻量安卓虚拟机 App

目标是替代 VT 容器：在安卓手机（arm64）上运行一个轻量 Android 虚拟机，
VM 内自带 root，可启动红果/番茄/抖音，并加载 Xposed/LSPosed 模块。

## 技术路线

- **proot/chroot + 嵌入式 Android rootfs（arm64）**，不依赖硬件虚拟化/KVM/真机 root。
- 渲染与输入复用两仪（twoyi, MPL-2.0）的 native 组件：
  - `libloader.so`：proot loader，rootfs 内 `init` 通过 `TYLOADER` 加载。
  - `libtwoyi.so`：OpenGL 渲染 + 触摸输入转发（Surface → ANativeWindow）。
  - `libOpenglRender.so`：OpenGL 渲染实现。
  - `libp7zip.so`：rootfs.7z 解压。
- rootfs：两仪 1.0.0（Android 8.1 arm64，`user/test-keys`），后续自建 Android 9/10 rootfs。
- Xposed：两仪 README 明确支持 Xposed / EdXposed / LSPosed（rootfs 内挂载），
  后续在 rootfs 中预装 LSPosed 并为红果/番茄/抖音加载模块。

## 模块结构

```
lightvm/
  build.gradle.kts            # application, io.vt.lightvm, arm64-v8a
  src/main/AndroidManifest.xml
  src/main/java/io/vt/lightvm/
    LightVmApp.kt
    MainActivity.kt           # 安装 rootfs / 启动 / 停止 / 渲染窗口
    VmManager.kt              # rootfs 安装、init 启动、APK 安装（骨架）
    VmRenderActivity.kt       # SurfaceView + renderer JNI 占位
  src/main/jniLibs/arm64-v8a/ # libloader/libtwoyi/libOpenglRender/libp7zip
```

## 当前进度（2026-10-27）

- 模块已创建，`:lightvm:assembleDebug` 编译通过。
- native 组件已从两仪官方 APK 提取并放入 `jniLibs`。
- `rootfs.7z`（202MB）已打入 `assets`，完整 APK 已构建。
- 已实现：
  1. `VmManager.installRootfs`：assets/rootfs.7z → `dataDir/rootfs`，使用 **libp7zip 原生 7z**
     （`P7ZipApi.executeCommand("7z x -aoa ... -o<dataDir>")`，保留权限/符号链接），
     并给 `init`/`system/bin` 等标记可执行权限。
  2. `VmManager.prepareBoot`：创建 `dev/input|socket|maps`、`socket` 目录。
  3. `VmRenderActivity`：SurfaceView → `io.twoyi.Renderer.init(surface, libloader, xdpi, ydpi, 60)`，
     触摸 `handleTouch`、按键 `sendKeycode` 转发。
  4. `VmManager.installApk`：用 `libadb.so` 启动 adb server（9563），连接 VM 内 adbd
     （localhost:22122），执行 `adb install -t -r`。
  5. `MainActivity`：安装 rootfs / 启动（prepareBoot + 渲染窗口）/ 停止。
- rootfs 结构已确认：`rootfs/init`（1.3MB 可执行）、`init.rc`、`system/bin/adbd`；
  两仪 ROM 具备启动与 adb 通道。
- 待接入：
  1. 真机验证 7z 解压权限/符号链接、`./init` 启动与渲染。
  2. LSPosed/Xposed：两仪代码有 "magisk 模式" 分支，rootfs 是否预装 Magisk 需真机确认；
     后续在 rootfs 内安装 LSPosed 或预置 Magisk 模块，并把 VT 的 Xposed 模块装进去。
  3. 兼容 Android 14/15 宿主（两仪 2023 停更，需适配 linker/SELinux）。

## 真机调试记录（2026-10-27，Xiaomi 23116PN5BC / Android 16 / arm64）

- 安装 `io.vtlvm` 成功；rootfs 自动解压成功（`dataDir/rootfs` 614MB，`init` 可执行）。
- `VmRenderActivity` 的 SurfaceView 已建立（SurfaceFlinger 识别），首次 `renderer_init`
  只绑定 Surface，需第二次 init 才启动 `./init`（已在 surfaceCreated 后延迟 600ms 再 init）。
- 首次 init 日志：`init: init second stage started!` / `init: Failed to initialize property area`。
- 根因：rootfs 的 `init` 硬编码 `/data/data/io.twoyi/loader64`、`/data/data/io.twoyi/rootfs`。
  已把 `init` 中的 `io.twoyi` 等长 patch 为 `io.vtlvm`，并创建 `dataDir/loader64 -> libloader.so`
  软链、覆盖 `rootfs/init`（chmod 755）。重跑后 `log.txt` 为空（init 秒退/无输出），待继续排查
  （TYLOADER 环境、init 参数、Android 16 兼容）。
- 注意：targetSdk 需降到 28，Android 10+ 才允许从 app 数据目录 exec（否则 init 无法启动）。

### 真机里程碑（2026-10-27，Xiaomi Android 16）

- **VM 引擎已在真机跑通**：临时使用 `io.twoyi` 包名 + 两仪原始 `init`/`libtwoyi`/`rootfs` 后，
  rootfs 内的 Android 8.1 init 完整启动：servicemanager、audioserver、keystore、media、
  adbd、hidl_memory、audio-hal 等均拉起（log.txt 可见），`VmRenderActivity` 前台。
- 必要条件：
  1. targetSdk=27（SELinux 域 `untrusted_app_27` 才能从 app data exec）。
  2. 包名/路径必须与 init/libtwoyi 硬编码的 `/data/data/io.twoyi/{rootfs,loader64}` 一致；
     自研包名时 init 会秒退（patch 不完整）。
  3. **只调用一次 `Renderer.init`**（surfaceCreated）：第二次 init 会重复启动 renderer 并清掉
     subwin，出现 `CLIENT_EGL: No subwin exist` 黑屏；`surfaceChanged` 的 `resetWindow`
     只在 renderer 就绪后调用。
- **画面已成功显示**：VM 内 Android 8.1 桌面（星空壁纸 / Search / 导航栏）已在真机显示。
  VM 内 adb 调试通道：`adb forward tcp:22123 tcp:22122` → `adb connect 127.0.0.1:22123`。
- **最新版红果在 Android 8.1 rootfs 上崩溃**（`FATAL EXCEPTION: MiraThreadPoolHelper2`），
  并拖垮 VM 的 `system_server`（`Can't find service: activity`）。用户明确：不要用老版本
  验证（版本不同检测也不同），必须升级 rootfs 后用**最新版**验证。
- 用户要求：两仪是 2021-2022 老项目，最终要**升级优化**（新版 loader/proot、Android 10/12
  rootfs、渲染、LSPosed 集成、自研 UI），不能直接交付两仪。

## 升级规划

1. **loader**：以新版 proot / 自研 ptrace/chroot loader 替换 2021 版 libloader.so，适配
   Android 14/15/16（W^X、SELinux、16KB page）。
2. **rootfs**：从 Android 8.1 升级到 Android 10/12（AOSP userdebug 或自建），预置 Magisk/LSPosed。
3. **渲染/输入**：更新两仪 Rust renderer（或自研 OpenGL/Surface 对接），保证 surfaceflinger 画面。
4. **Xposed**：rootfs 内集成 LSPosed + VT 模块；验证红果/番茄/抖音在 VM 内的检测结果。
5. **自研包名**：重编 Rust/init，去掉 `io.twoyi` 硬编码，形成 `io.vt.lightvm` 正式版。

## Android 10/12 rootfs 升级验证（2026-10-27 真机）

- 从 SDK arm64 镜像提取 **Android 12 / Android 10** rootfs（AVB 头偏移、ext4 解析、
  APEX runtime 链接、符号链接与权限重建）。
- 用 **Termux 开源 proot（aarch64）** 在真机 app 内启动 Android 10：
  - `/system/bin/sh` 可运行（uid=0）；
  - **init/zygote64/servicemanager/surfaceflinger/system_server/webview_zygote64 全部启动**。
- **binder 卡点（核心）**：
  - Android 12 的 linker/libc 在 APEX runtime，proot 无法挂载 → 12 不可行；
  - Android 10 可用，但服务间 binder 依赖宿主**全局 binderfs**（`/dev/binderfs`，`stats=global`）；
  - 两仪的 8.1 VM 使用**用户态 vbinder**（`/dev/ashmem/vbinder` + socket，libbinder 定制），
    不依赖内核 binder；AOSP 10 原版 libbinder 无法与宿主 16 的全局 binder 域共存；
  - 真机内核 **未启用 user namespace**（`unshare -U` 返回 EINVAL），无法创建独立 binderfs 实例。
- 结论：**Android 10+ VM 要跑 app，必须实现/移植用户态 binder（vbinder）**；
  该项是两仪未开源的核心技术，自研工作量大。

## 成熟方案对照

- 真机已安装 **VMOS Pro**（com.vmos.pro）与 **VPhoneGaGa**（com.vphonegaga.titan，已有实例）。
- VMOS Pro 支持 ROOT/Xposed，首次创建本地虚拟机需登录/激活（会员），无法自动完成。
- VPhoneGaGa 已有 VM 实例，用户正在自行验证其 VM 内最新版 app 的检测表现。

## 验证条件

- rootfs 为 arm64，必须在 arm64 真机上运行；x86_64 AVD 无法通过 proot 运行 arm64。
- 需要用户接入安卓真机（USB 调试）后继续。

## 构建与 ROM 获取

- `lightvm/src/main/assets/rootfs.7z`（两仪 Android 8.1 ROM，约 202MB）超过 GitHub 单文件
  100MB 限制，不入库。构建前放入该路径即可：可用 topminipie/twoyi Releases 中的
  `0.7.5-rom` / `0.5.4-rom` 的 rootfs.7z，或从两仪 APK 的 `assets/rootfs.7z` 提取。
- 已验证可用的组合是**两仪 0.5.4 全套**（libloader/libtwoyi/libOpenglRender + 0.5.4 ROM）。
  0.7.5 的 loader/ROM 需要配套的 libtwoyi 启动参数，直接混用会出现
  `init: execv("./init") failed`（argv0/工作目录不匹配）。
- 两仪 guest 内执行 `dumpsys SurfaceFlinger` 会触发其 gralloc dump 崩溃（SIGBUS），
  排查渲染问题时应避免该命令。
- 已知稳定性问题：重负载（如红果软解 1080p）下 guest system_server 可能因 keystore
  binder 调用阻塞被 watchdog 杀死；已实现的缓解是按 2/3 分辨率渲染（720×1488）并降低 DPI。
