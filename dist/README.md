# VT 0.1.0 构建产物

## 推荐安装（轻量测试包，约 2.8 MB/ABI）

1. `vt-framework-0.1.0-arm64-v8a-releaseTest.apk` — 真机 arm64 优先使用
2. 或 `vt-framework-0.1.0-x86_64-releaseTest.apk` — x86_64 模拟器
3. `vt-sample-module.apk` — 示例 Xposed 模块（必须安装）
4. `vt-sample-guest.apk` — 示例 Guest v17（用于导入，含前台通知、跨进程 Activity、Activity 结果回调测试按钮）

`releaseTest` 是 **R8 最小化 + debug 签名 + 包含 DebugControlActivity（导出）** 的测试包，仅用于开发验证，不可直接作为商店发布包。正式发布请移除 `src/debug` 控制入口并使用自己的 keystore。

`vt-framework-0.1.0-universal-releaseTest.apk` 是包含三 ABI 的通用测试包（约 4.1 MB）。
`vt-framework-0.1.0-debug.apk`（约 60.8 MB）仅用于对比/排障。
本版示例模块为 debug 签名，可直接覆盖安装；其中包含 NewPipe 作用域的演示 Hook（仅写日志）。

## 使用

安装框架 + 示例模块 + 示例 Guest → 打开 VT（首次启动会申请通知权限）→ 导入示例 Guest →
详情勾选示例模块 → 启动。

示例 Guest 会显示：
`providerReady=true, queryCount=1, serviceStarted=true, ... native=native-ok, bound=true, boundResult=pong-from-guest-service, meta=meta-ok, signers=1`。

点击“测试前台通知（Guest）”会以宿主身份发布真实前台通知，点击通知可回到 Guest；
“停止前台通知”或框架详情的“停止”会取消通知。注意这不是 AMS 前台服务，没有 oom 提升。
“测试 ACTIVITY 结果回调”会启动 `ResultActivity` 并在返回后显示 `Activity 结果: result-ok`，
用于验证登录/滑块等依赖 `onActivityResult` 的链路。

本版同时验证了真实第三方应用 NewPipe 0.29.1、Telegram 13.1.0，并在 **Android 16 arm64 真机**上
验证抖音 40.7.0（导入、真实 Splash alias、推荐流/视频、Push/WsChannel 跨进程 Service、示例模块注入并显示
“VT MODULE ACTIVE” 浮层）；番茄小说 7.4.1.32 与红果免费短剧 7.4.1.32 均可自然启动、隐私同意并浏览
主界面/推荐流。**注意：番茄/红果打开正文时服务端返回 `ILLEGAL_ACCESS`（code 110），提示“当前版本不安全”，
这是服务端防盗版/风控，当前无 root 容器无法可靠绕过。** 抖音短信登录的 ActivityResult 丢失已在容器侧修复，
滑块后的验证码页仍需真实手机号复测。以上应用需自行准备 APK 并安装后导入，仓库不附带其安装包。
