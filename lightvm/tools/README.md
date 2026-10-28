# LightVM 工具链（rootfs 提取与 proot 启动）

这些脚本用于把 AOSP arm64 系统镜像转换为可在真机 app 内由 proot 启动的 rootfs。
**限制**：Android 10+ 的系统服务依赖 binder；AOSP 原版 libbinder 使用内核全局 binderfs，
在 proot 内无法获得独立域（内核未开 user namespace、app 无 mount 权限）。
因此这些脚本目前只能启动到“系统进程层”（init/zygote/servicemanager/surfaceflinger/system_server），
**无法运行普通 app**。两仪 8.1 使用用户态 vbinder（ashmem+socket，libbinder 定制）解决此问题。

## 步骤（以 Android 10 / android-29 default arm64 为例）

1. 下载镜像（直连，避免代理断流）：
   `curl -L --retry 8 --noproxy '*' -o arm64-v8a-29_r08.zip https://dl.google.com/android/repository/sys-img/android/arm64-v8a-29_r08.zip`
2. 解压：`unzip -oq arm64-v8a-29_r08.zip -d android29`
3. 跳过 AVB 头提取 ext4：
   - system.img 前 3MB 是 AVB 头：`dd if=system.img of=system.ext4.img bs=1M skip=3`
   - vendor.img 前 1MB：`dd if=vendor.img of=vendor.ext4.img bs=1M skip=1`
4. 用 `extract_ext4.py`（依赖 `pip install ext4`）提取：
   - `python extract_ext4.py system.ext4.img rootfs10/system`
   - `python extract_ext4.py vendor.ext4.img rootfs10/vendor`
   - 生成 `rootfs10/system.manifest`（文件权限/符号链接清单）。
5. 组装根：`cp -r rootfs10/vendor rootfs10/system/vendor`；rootfs 根为 `rootfs10/system` 的内容。
6. 生成符号链接脚本：见 `links10.sh`（按 manifest 的 SYMLINK 行在 app 私有目录重建）。
   - `/system/bin/linker64 -> /apex/com.android.runtime/bin/linker64`
   - `apex/com.android.runtime -> /system/apex/com.android.runtime.debug`（镜像中的 runtime 是 debug 版）
   - 修复可执行位：`system/bin/init`、`system/bin/bootstrap/linker64`（init 的 interpreter）、`system/bin/sh`。
7. 推送 rootfs 到真机：`adb push rootfs10/system /data/local/tmp/rootfs10`，
   再在 app 内 `cp -r` 到 `files/prootroot10`（app 私有目录可执行）。
8. Termux proot 组件：从 `https://packages.termux.dev/apt/termux-main/pool/main/` 下载
   proot、libtalloc、libandroid-shmem（aarch64），解出 `proot`、`loader`、`loader32`、`libtalloc.so.2`、`libandroid-shmem.so`。
9. 启动（app 内）：
   ```
   export LD_LIBRARY_PATH=$B PROOT_LOADER=$B/loader PROOT_TMP_DIR=<cache> PROOT_NO_SECCOMP=1
   $B/proot -0 -r $R -b /proc -b /sys -b /dev/binder:/binder -b /dev/null -b /dev/zero -b /dev/random -b /dev/urandom -w / /system/bin/init
   ```
   - **不要用 `| head` 截断输出**：会向 init 发送 SIGPIPE 导致其退出（曾误判为“秒退”）。
   - 结果：init/zygote/servicemanager/surfaceflinger/system_server 启动，
     但 `pm/service list` 因 binder 域问题不可用（见上方限制）。

## vbinder 调试命令（Android 10 + 两仪用户态 binder）

前置：8.1 VM 运行（lightvm），prootkit 内含 `proot/loader/libvbinder.so/libvbinder_shim.so`，
prootroot10 已就绪。在 app 内（run-as io.twoyi）执行：

```
B=/data/data/io.twoyi/files/prootkit
R=/data/data/io.twoyi/files/prootroot10
VB=/data/data/io.twoyi/rootfs/dev/vbinder
export LD_LIBRARY_PATH=$B PROOT_LOADER=$B/loader PROOT_TMP_DIR=/data/data/io.twoyi/cache PROOT_NO_SECCOMP=1
export LD_PRELOAD=$B/libvbinder_shim.so VBINDER_LIB=$B/libvbinder.so VBINDER_SOCKET=$VB/bis
# 启动 10 init（后台，勿用 head 截断）
(timeout 180 $B/proot -0 -r $R -b /proc -b /sys -b $VB:$VB -b $B:$B -b /dev/null -b /dev/zero -b /dev/random -b /dev/urandom -w / /system/bin/init >/dev/null 2>&1 &)
sleep 25
# 服务连通性（前台看 shim 日志）
timeout 20 $B/proot -0 -r $R -b /proc -b /sys -b $VB:$VB -b $B:$B -b /dev/null -w / /system/bin/service list
timeout 20 $B/proot -0 -r $R -b /proc -b /sys -b $VB:$VB -b $B:$B -b /dev/null -w / /system/bin/pm list packages
```

已知现象：服务进程可启动（servicemanager/system_server）；`service list` 曾无输出被 timeout 杀（SIGTERM 15），
需确认 shim 的 connect errno（日志到 stderr）与 proot 的 sockaddr 翻译行为。app 内直接
connect `$VB/bis` 已验证成功（返回 0）。

## 归档文件

- `extract_ext4.py`：Python ext4 递归提取（权限清单/符号链接记录）。
- `links10.sh`：271 个符号链接重建脚本（Android 10 manifest 生成）。
- `proot10-test.sh`：复制 rootfs、链接、chmod、proot 启动 shell/init。
- `proot-test.sh`：Android 12 版本的对应脚本。
- `setup12.sh` / `setup12mix.sh`：把 12 rootfs / 8.1+12 混合 rootfs 替换进 lightvm 的 rootfs 目录。
