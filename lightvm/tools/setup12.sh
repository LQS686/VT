#!/system/bin/sh
set -e
cd /data/data/io.twoyi
rm -rf rootfs81
if [ -d rootfs ]; then mv rootfs rootfs81; fi
cp -r /data/local/tmp/rootfs12 rootfs
cd rootfs
# Android system-as-root top-level links
ln -sfn /system/bin bin
ln -sfn /system/etc etc
ln -sfn /system/bin/init init
ln -sfn /sys/kernel/debug d
ln -sfn /storage/self/primary sdcard
rm -f ./*.symlink
# dev/property area
mkdir -p dev/input dev/socket dev/maps dev/__properties__
chmod 771 dev/__properties__
# executable bits
chmod 755 init 2>/dev/null || true
for d in system/bin system/xbin vendor/bin system_ext/bin system/system/bin; do
  [ -d "$d" ] || continue
  find "$d" -type f 2>/dev/null | while read -r f; do chmod 755 "$f"; done
done
for d in system/lib64 vendor/lib64 system/system/lib64 system/system/lib; do
  [ -d "$d" ] || continue
  find "$d" -type f -name '*.so' 2>/dev/null | while read -r f; do chmod 644 "$f"; done
done
ls system/etc/init/hw/init.rc >/dev/null 2>&1 && echo init-rc-ok || echo init-rc-missing
echo setup12-done
