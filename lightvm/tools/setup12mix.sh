#!/system/bin/sh
set -e
cd /data/data/io.twoyi
rm -rf rootfs81
if [ -d rootfs ]; then mv rootfs rootfs81; fi
cp -r /data/local/tmp/rootfs12mix rootfs
cd rootfs
ln -sfn /system/bin bin
ln -sfn /system/etc etc
ln -sfn /system/bin/init init2
ln -sfn /sys/kernel/debug d
ln -sfn /storage/self/primary sdcard
rm -f ./*.symlink
mkdir -p dev/input dev/socket dev/maps dev/__properties__
chmod 771 dev/__properties__
chmod 755 init
for d in system/bin system/xbin vendor/bin system_ext/bin; do
  [ -d "$d" ] || continue
  find "$d" -type f 2>/dev/null | while read -r f; do chmod 755 "$f"; done
done
for d in system/lib64 vendor/lib64; do
  [ -d "$d" ] || continue
  find "$d" -type f -name '*.so' 2>/dev/null | while read -r f; do chmod 644 "$f"; done
done
echo setup12mix-done
