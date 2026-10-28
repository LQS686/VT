#!/system/bin/sh
# Integrate Android 10 proot VM with twoyi's userspace binder (vbinder).
#
# Prerequisites:
#   - 8.1 VM (twoyi rootfs) is running in lightvm -> its init provides the
#     vbinder driver endpoint (abstract unix socket "@9h7" per libvbinder.so).
#   - prootroot10 exists under /data/data/io.twoyi/files (see proot10-test.sh).
#   - vbinder_shim.so + libvbinder.so pushed to /data/data/io.twoyi/files/prootkit.
#
# Usage (in app context): sh vbinder-start.sh
B=/data/data/io.twoyi/files/prootkit
R=/data/data/io.twoyi/files/prootroot10
export LD_LIBRARY_PATH="$B"
export PROOT_LOADER="$B/loader"
export PROOT_TMP_DIR=/data/data/io.twoyi/cache
export PROOT_NO_SECCOMP=1

# Inject the shim into the Android 10 init (and its children): libbinder's
# /dev/binder access is redirected to libvbinder -> "@9h7" driver endpoint.
export LD_PRELOAD="$B/libvbinder_shim.so"
export VBINDER_LIB="$B/libvbinder.so"
export VBINDER_SOCKET="$B/dev-vbinder"

mkdir -p "$R/dev/__properties__" "$R/dev/socket"
chmod 771 "$R/dev/__properties__"

# Run proot 10 init with the shim. Keep output visible (do NOT pipe to head:
# SIGPIPE would kill init).
exec "$B/proot" -0 -r "$R" -b /proc -b /sys -b /dev/null -b /dev/zero \
  -b /dev/random -b /dev/urandom -w / /system/bin/init
