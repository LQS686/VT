# vbinder：用户态 binder 适配（自研进行中）

目标：让 **Android 10+ proot 虚拟机** 内的原版 libbinder 使用 **两仪的用户态 binder 栈**，
从而运行普通 app（当前 AOSP 原版 libbinder 依赖宿主全局 binderfs，服务不可用）。

## 两仪 vbinder 架构（逆向确认）

- `libvbinder.so`（56KB）唯一导出 `virtual_binder_ioctl`，内部通过 **Unix socket + ashmem**
  与 **驱动端**通信，命令包括 `VBINDER_IOCTL_CONNECT` / `VBINDER_CLIENT_CONNECT` /
  `VBINDER_CLIENT_READ_WRITE` / `VBINDER_FD_ACCEPT` / `VBINDER_FD_CONNECT`。
- 驱动端在两仪 **init 静态二进制**内：init 创建 `/dev/vbinder`、`/dev/hwbinder`（目录 + socket），
  8.1 的 system 进程（servicemanager/zygote/system_server）通过定制 libbinder + libvbinder 通信。
- binder 驱动协议 v8 与 Android 10 兼容。

## 本目录

- `vbinder_shim.c` / `libvbinder_shim.so`：LD_PRELOAD shim。
  - `open("/dev/binder")` → 打开 `$VBINDER_SOCKET`（默认 `/dev/vbinder`）
  - `ioctl(vbinder_fd, cmd, arg)` → `virtual_binder_ioctl(fd, cmd, arg)`
  - `mmap` 透传（事务内存由驱动端/ashmem 处理）
  - 构建：`aarch64-linux-android21-clang -shared -fPIC -O2 -o libvbinder_shim.so vbinder_shim.c -ldl`
- `libvbinder.so`：从两仪 rootfs.7z 提取的客户端库（闭源，供验证/研究）。
- `libbinder81.so`：两仪定制 libbinder（8.1），用于对照理解。

## 集成方案（待完成）

1. **驱动端**：运行 8.1 VM（两仪 init）提供 `/dev/vbinder` socket；Android 10 的 proot 启动时
   用 `-b <8.1 rootfs>/dev/vbinder:/dev/vbinder` 绑定该 socket。
2. **客户端**：Android 10 的进程（init/zygote/system_server）设置
   `LD_PRELOAD=libvbinder_shim.so`、`VBINDER_LIB=libvbinder.so`、`VBINDER_SOCKET=/dev/vbinder`。
3. **待解决**：
   - shim 的 `ioctl` 对 `/dev/binder` 的 **BINDER_VERSION**/context-manager 等调用需完整转发；
   - binder 事务内 **fd 传递**（`BINDER_TYPE_FD`）需要 `VBINDER_FD_*` 路径配合；
   - `mmap` 事务内存（binder 需要共享 mmap）实现/校验；
   - 驱动端 socket 路径/权限（两仪 init 创建，跨 proot 绑定）；
   - 协议版本/错误码调试（logcat/strace）。
4. 验证通过后，把 shim/vbinder 集成进 lightvm（proot wrapper + rootfs10），再装最新版红果验证。
