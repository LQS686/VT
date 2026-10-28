#!/system/bin/sh
# Full on-device verification for the Android 10 + vbinder stack.
# Preconditions:
#   - VMOS / other memory-heavy apps closed (16GB device is tight).
#   - lightvm (io.twoyi) installed; rootfs 8.1 available (rootfs/init).
#   - prootkit (proot/loader/libvbinder/libvbinder_shim) and prootroot10 in app files.
#   - vbinder_shim.so compiled with the correct driver socket name (see note).
#
# Step 1: start lightvm (8.1 VM) so its init provides the vbinder driver endpoint.
#     am start -n io.twoyi/io.vt.lightvm.MainActivity   (from host adb)
# Step 2: wait for the 8.1 VM to boot, then from inside the app:
#     sh verify-vbinder.sh
#
# NOTE: driver socket name is not yet confirmed statically. Before running,
# inspect:  cat /proc/net/unix | grep -iE 'vbinder|9h7|@'
# and set VBINDER_SOCKET_NAME accordingly (shim currently connects "@9h7").

B=/data/data/io.twoyi/files/prootkit
R=/data/data/io.twoyi/files/prootroot10
export LD_LIBRARY_PATH="$B"
export PROOT_LOADER="$B/loader"
export PROOT_TMP_DIR=/data/data/io.twoyi/cache
export PROOT_NO_SECCOMP=1
export LD_PRELOAD="$B/libvbinder_shim.so"
export VBINDER_LIB="$B/libvbinder.so"

echo "== vbinder sockets =="
cat /proc/net/unix 2>/dev/null | grep -iE 'vbinder|9h7' | head -10

echo "== starting Android 10 init with shim =="
# Do not pipe to head (SIGPIPE kills init). Background with timeout.
(timeout 180 "$B/proot" -0 -r "$R" \
  -b /proc -b /sys -b /dev/null -b /dev/zero -b /dev/random -b /dev/urandom \
  -w / /system/bin/init >/dev/null 2>&1 &)
sleep 25

echo "== services =="
ps -A -o PID,PPID,USER,NAME | grep -aE 'servicemanager|system_server|zygote64' | head -8

echo "== binder services =="
timeout 15 "$B/proot" -0 -r "$R" -b /proc -b /sys -b /dev/null -w / /system/bin/service list 2>&1 | head -15

echo "== package service =="
timeout 15 "$B/proot" -0 -r "$R" -b /proc -b /sys -b /dev/null -w / /system/bin/pm list packages 2>&1 | head -5

echo "== done =="
