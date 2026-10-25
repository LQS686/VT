# 第三方组件与许可

VT 以 **GPL-3.0** 发布。以下组件随源码或二进制一起使用：

| 组件 | 用途 | 许可 | 位置 |
| --- | --- | --- | --- |
| LSPosed / Xposed API 82 实现 | `de.robv.android.xposed` API、XposedHelpers、回调类 | GPL-3.0 | `xposed/src/main/java/de/robv/android/xposed/` |
| LSPosed `MemberUtilsX` | 反射工具 | Apache-2.0 | `xposed/src/main/java/org/apache/commons/lang3/reflect/MemberUtilsX.java` |
| LSPlant（源码） | ART Java 方法 Hook | LGPL-3.0 | `xposed/src/main/cpp/third_party/lsplant/` |
| DexBuilder + parallel-hashmap | LSPlant 生成 hook dex | Apache-2.0 | `xposed/src/main/cpp/third_party/lsplant/external/dex_builder/` |
| Dobby | 内联 Hook（LSPlant inline hooker） | Apache-2.0 | Maven `io.github.vvb2060.ndk:dobby:1.2` |
| xz-embedded | 解压 ART `.gnu_debugdata` | 0BSD | `xposed/src/main/cpp/xz/`（含 `COPYING`） |
| AndroidX / Compose / Lifecycle | UI 与基础库 | Apache-2.0 | Gradle 依赖 |
| Kotlin / kotlinx.coroutines | 语言与协程 | Apache-2.0 | Gradle 依赖 |
| Gson | JSON 存储 | Apache-2.0 | Gradle 依赖 |
| Android NDK libc++ | C++ 运行时 | Apache-2.0 with LLVM exception | NDK 30 |
| Apache Commons Lang | 反射工具依赖 | Apache-2.0 | Gradle 依赖 |

## 说明

- LSPlant 以静态库链接入 `libvt_xposed.so`，但整个 VT 项目以 GPL-3.0 发布，满足 LGPL 的组合作品要求；LSPlant 源码与许可文件保留在 third_party 目录。
- Xposed API 实现来自 LSPosed（GPL-3.0），保留了原始版权头；VT 的修改（Hook 后端、日志、资源层）同样以 GPL-3.0 开放。
- 示例应用/示例模块为本项目原创，供端到端测试。
- 本项目不包含抖音、番茄小说、番茄免费短剧的任何代码或资源；这些名称仅用于兼容性讨论。
