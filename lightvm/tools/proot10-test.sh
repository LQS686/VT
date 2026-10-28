#!/system/bin/sh
B=/data/data/io.twoyi/files/prootkit
R=/data/data/io.twoyi/files/prootroot10
if [ ! -f "$R/system/bin/init" ]; then
  echo "copying rootfs10..."
  rm -rf "$R"
  cp -r /data/local/tmp/rootfs10 "$R"
fi
sh /data/local/tmp/links10.sh
ln -sfn /system/apex/com.android.runtime.debug "$R/apex/com.android.runtime" 2>/dev/null
chmod 755 "$R/system/bin/init" "$R/system/bin/sh" "$R/system/bin/linker64" 2>/dev/null
chmod 755 "$R/system/apex/com.android.runtime.debug/bin/linker64" 2>/dev/null
chmod 755 "$R/system/bin"/* 2>/dev/null
chmod 644 "$R/system/apex/com.android.runtime.debug/lib64"/*.so 2>/dev/null
export LD_LIBRARY_PATH="$B"
export PROOT_LOADER="$B/loader"
export PROOT_TMP_DIR=/data/data/io.twoyi/cache
export PROOT_NO_SECCOMP=1
echo "=== 10 shell ==="
"$B/proot" -0 -r "$R" -b /proc -b /sys -b /dev/null -w / /system/bin/sh -c "id; getprop ro.build.version.release; echo SHELL10-OK" 2>&1 | head -20
echo "=== 10 init ==="
mkdir -p "$R/dev/__properties__" "$R/dev/socket" "$R/dev/input" "$R/dev/maps"
chmod 771 "$R/dev/__properties__"
"$B/proot" -0 -r "$R" -b /proc -b /sys -b /dev/null -b /dev/zero -b /dev/random -b /dev/urandom -w / /system/bin/init 2>&1 | head -100
echo "=== exit ==="
