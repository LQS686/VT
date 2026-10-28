#!/system/bin/sh
BASE=/data/data/io.twoyi/files/prootkit
DEST=/data/data/io.twoyi/files/prootroot12
chmod 755 "$BASE/proot" "$BASE/loader" "$BASE/loader32" 2>/dev/null
chmod 755 "$DEST/system/bin/init" 2>/dev/null
mkdir -p "$DEST/dev/__properties__" "$DEST/dev/socket" "$DEST/dev/input" "$DEST/dev/maps"
chmod 771 "$DEST/dev/__properties__"
export LD_LIBRARY_PATH="$BASE"
export PROOT_LOADER="$BASE/loader"
export PROOT_TMP_DIR=/data/data/io.twoyi/cache
export PROOT_NO_SECCOMP=1
echo "=== starting 12 init (no /dev bind) ==="
"$BASE/proot" -0 -r "$DEST" -b /proc -b /sys -b /dev/null -b /dev/zero -b /dev/random -b /dev/urandom -b /dev/full -w / /system/bin/init 2>&1 | head -120
echo "=== exit ==="
