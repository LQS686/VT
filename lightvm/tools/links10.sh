#!/system/bin/sh
DEST=/data/data/io.twoyi/files/prootroot10
cd "$DEST" || exit 1
mkdir -p "$(dirname 'bin')" 2>/dev/null
ln -sfn '/system/bin' 'bin'
mkdir -p "$(dirname 'bugreports')" 2>/dev/null
ln -sfn '/data/user_de/0/com.android.shell/files/bugreports' 'bugreports'
mkdir -p "$(dirname 'charger')" 2>/dev/null
ln -sfn '/system/bin/charger' 'charger'
mkdir -p "$(dirname 'd')" 2>/dev/null
ln -sfn '/sys/kernel/debug' 'd'
mkdir -p "$(dirname 'default.prop')" 2>/dev/null
ln -sfn 'system/etc/prop.default' 'default.prop'
mkdir -p "$(dirname 'dsp')" 2>/dev/null
ln -sfn '/vendor/lib/dsp' 'dsp'
mkdir -p "$(dirname 'etc')" 2>/dev/null
ln -sfn '/system/etc' 'etc'
mkdir -p "$(dirname 'firmware')" 2>/dev/null
ln -sfn '/vendor/firmware_mnt' 'firmware'
mkdir -p "$(dirname 'init')" 2>/dev/null
ln -sfn '/system/bin/init' 'init'
mkdir -p "$(dirname 'odm/app')" 2>/dev/null
ln -sfn '/vendor/odm/app' 'odm/app'
mkdir -p "$(dirname 'odm/bin')" 2>/dev/null
ln -sfn '/vendor/odm/bin' 'odm/bin'
mkdir -p "$(dirname 'odm/etc')" 2>/dev/null
ln -sfn '/vendor/odm/etc' 'odm/etc'
mkdir -p "$(dirname 'odm/firmware')" 2>/dev/null
ln -sfn '/vendor/odm/firmware' 'odm/firmware'
mkdir -p "$(dirname 'odm/framework')" 2>/dev/null
ln -sfn '/vendor/odm/framework' 'odm/framework'
mkdir -p "$(dirname 'odm/lib')" 2>/dev/null
ln -sfn '/vendor/odm/lib' 'odm/lib'
mkdir -p "$(dirname 'odm/lib64')" 2>/dev/null
ln -sfn '/vendor/odm/lib64' 'odm/lib64'
mkdir -p "$(dirname 'odm/overlay')" 2>/dev/null
ln -sfn '/vendor/odm/overlay' 'odm/overlay'
mkdir -p "$(dirname 'odm/priv-app')" 2>/dev/null
ln -sfn '/vendor/odm/priv-app' 'odm/priv-app'
mkdir -p "$(dirname 'odm/usr')" 2>/dev/null
ln -sfn '/vendor/odm/usr' 'odm/usr'
mkdir -p "$(dirname 'persist')" 2>/dev/null
ln -sfn '/mnt/vendor/persist' 'persist'
mkdir -p "$(dirname 'product')" 2>/dev/null
ln -sfn '/system/product' 'product'
mkdir -p "$(dirname 'product_services')" 2>/dev/null
ln -sfn '/system/product_services' 'product_services'
mkdir -p "$(dirname 'sdcard')" 2>/dev/null
ln -sfn '/storage/self/primary' 'sdcard'
mkdir -p "$(dirname 'system/apex/com.android.runtime.debug/bin/dalvikvm')" 2>/dev/null
ln -sfn 'dalvikvm64' 'system/apex/com.android.runtime.debug/bin/dalvikvm'
mkdir -p "$(dirname 'system/apex/com.android.runtime.debug/bin/linker_asan64')" 2>/dev/null
ln -sfn 'linker64' 'system/apex/com.android.runtime.debug/bin/linker_asan64'
mkdir -p "$(dirname 'system/app/Bluetooth/lib/arm64/libbluetooth_jni.so')" 2>/dev/null
ln -sfn '/system/lib64/libbluetooth_jni.so' 'system/app/Bluetooth/lib/arm64/libbluetooth_jni.so'
mkdir -p "$(dirname 'system/app/BuiltInPrintService/lib/arm64/libcups.so')" 2>/dev/null
ln -sfn '/system/lib64/libcups.so' 'system/app/BuiltInPrintService/lib/arm64/libcups.so'
mkdir -p "$(dirname 'system/app/BuiltInPrintService/lib/arm64/libwfds.so')" 2>/dev/null
ln -sfn '/system/lib64/libwfds.so' 'system/app/BuiltInPrintService/lib/arm64/libwfds.so'
mkdir -p "$(dirname 'system/app/NfcNci/lib/arm64/libnfc_nci_jni.so')" 2>/dev/null
ln -sfn '/system/lib64/libnfc_nci_jni.so' 'system/app/NfcNci/lib/arm64/libnfc_nci_jni.so'
mkdir -p "$(dirname 'system/app/PacProcessor/lib/arm64/libjni_pacprocessor.so')" 2>/dev/null
ln -sfn '/system/lib64/libjni_pacprocessor.so' 'system/app/PacProcessor/lib/arm64/libjni_pacprocessor.so'
mkdir -p "$(dirname 'system/app/PrintSpooler/lib/arm64/libprintspooler_jni.so')" 2>/dev/null
ln -sfn '/system/lib64/libprintspooler_jni.so' 'system/app/PrintSpooler/lib/arm64/libprintspooler_jni.so'
mkdir -p "$(dirname 'system/bin/acpi')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/acpi'
mkdir -p "$(dirname 'system/bin/app_process')" 2>/dev/null
ln -sfn 'app_process64' 'system/bin/app_process'
mkdir -p "$(dirname 'system/bin/base64')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/base64'
mkdir -p "$(dirname 'system/bin/basename')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/basename'
mkdir -p "$(dirname 'system/bin/bc')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/bc'
mkdir -p "$(dirname 'system/bin/blockdev')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/blockdev'
mkdir -p "$(dirname 'system/bin/bootstrap/linker_asan64')" 2>/dev/null
ln -sfn 'linker64' 'system/bin/bootstrap/linker_asan64'
mkdir -p "$(dirname 'system/bin/bunzip2')" 2>/dev/null
ln -sfn 'bzip2' 'system/bin/bunzip2'
mkdir -p "$(dirname 'system/bin/bzcat')" 2>/dev/null
ln -sfn 'bzip2' 'system/bin/bzcat'
mkdir -p "$(dirname 'system/bin/cal')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/cal'
mkdir -p "$(dirname 'system/bin/cat')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/cat'
mkdir -p "$(dirname 'system/bin/chcon')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/chcon'
mkdir -p "$(dirname 'system/bin/chgrp')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/chgrp'
mkdir -p "$(dirname 'system/bin/chmod')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/chmod'
mkdir -p "$(dirname 'system/bin/chown')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/chown'
mkdir -p "$(dirname 'system/bin/chroot')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/chroot'
mkdir -p "$(dirname 'system/bin/chrt')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/chrt'
mkdir -p "$(dirname 'system/bin/cksum')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/cksum'
mkdir -p "$(dirname 'system/bin/clear')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/clear'
mkdir -p "$(dirname 'system/bin/cmp')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/cmp'
mkdir -p "$(dirname 'system/bin/comm')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/comm'
mkdir -p "$(dirname 'system/bin/cp')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/cp'
mkdir -p "$(dirname 'system/bin/cpio')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/cpio'
mkdir -p "$(dirname 'system/bin/cut')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/cut'
mkdir -p "$(dirname 'system/bin/dalvikvm')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/bin/dalvikvm' 'system/bin/dalvikvm'
mkdir -p "$(dirname 'system/bin/dalvikvm32')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/bin/dalvikvm32' 'system/bin/dalvikvm32'
mkdir -p "$(dirname 'system/bin/dalvikvm64')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/bin/dalvikvm64' 'system/bin/dalvikvm64'
mkdir -p "$(dirname 'system/bin/date')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/date'
mkdir -p "$(dirname 'system/bin/dd')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/dd'
mkdir -p "$(dirname 'system/bin/defrag.f2fs')" 2>/dev/null
ln -sfn 'fsck.f2fs' 'system/bin/defrag.f2fs'
mkdir -p "$(dirname 'system/bin/devmem')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/devmem'
mkdir -p "$(dirname 'system/bin/dex2oat')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/bin/dex2oat' 'system/bin/dex2oat'
mkdir -p "$(dirname 'system/bin/dexdiag')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/bin/dexdiag' 'system/bin/dexdiag'
mkdir -p "$(dirname 'system/bin/dexdump')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/bin/dexdump' 'system/bin/dexdump'
mkdir -p "$(dirname 'system/bin/dexlist')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/bin/dexlist' 'system/bin/dexlist'
mkdir -p "$(dirname 'system/bin/dexoptanalyzer')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/bin/dexoptanalyzer' 'system/bin/dexoptanalyzer'
mkdir -p "$(dirname 'system/bin/df')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/df'
mkdir -p "$(dirname 'system/bin/diff')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/diff'
mkdir -p "$(dirname 'system/bin/dirname')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/dirname'
mkdir -p "$(dirname 'system/bin/dmesg')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/dmesg'
mkdir -p "$(dirname 'system/bin/dos2unix')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/dos2unix'
mkdir -p "$(dirname 'system/bin/du')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/du'
mkdir -p "$(dirname 'system/bin/echo')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/echo'
mkdir -p "$(dirname 'system/bin/egrep')" 2>/dev/null
ln -sfn 'grep' 'system/bin/egrep'
mkdir -p "$(dirname 'system/bin/env')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/env'
mkdir -p "$(dirname 'system/bin/expand')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/expand'
mkdir -p "$(dirname 'system/bin/expr')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/expr'
mkdir -p "$(dirname 'system/bin/fallocate')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/fallocate'
mkdir -p "$(dirname 'system/bin/false')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/false'
mkdir -p "$(dirname 'system/bin/fgrep')" 2>/dev/null
ln -sfn 'grep' 'system/bin/fgrep'
mkdir -p "$(dirname 'system/bin/file')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/file'
mkdir -p "$(dirname 'system/bin/find')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/find'
mkdir -p "$(dirname 'system/bin/flock')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/flock'
mkdir -p "$(dirname 'system/bin/fmt')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/fmt'
mkdir -p "$(dirname 'system/bin/free')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/free'
mkdir -p "$(dirname 'system/bin/fsync')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/fsync'
mkdir -p "$(dirname 'system/bin/getconf')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/getconf'
mkdir -p "$(dirname 'system/bin/getenforce')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/getenforce'
mkdir -p "$(dirname 'system/bin/getevent')" 2>/dev/null
ln -sfn 'toolbox' 'system/bin/getevent'
mkdir -p "$(dirname 'system/bin/getprop')" 2>/dev/null
ln -sfn 'toolbox' 'system/bin/getprop'
mkdir -p "$(dirname 'system/bin/groups')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/groups'
mkdir -p "$(dirname 'system/bin/gunzip')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/gunzip'
mkdir -p "$(dirname 'system/bin/gzip')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/gzip'
mkdir -p "$(dirname 'system/bin/head')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/head'
mkdir -p "$(dirname 'system/bin/hostname')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/hostname'
mkdir -p "$(dirname 'system/bin/hwclock')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/hwclock'
mkdir -p "$(dirname 'system/bin/i2cdetect')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/i2cdetect'
mkdir -p "$(dirname 'system/bin/i2cdump')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/i2cdump'
mkdir -p "$(dirname 'system/bin/i2cget')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/i2cget'
mkdir -p "$(dirname 'system/bin/i2cset')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/i2cset'
mkdir -p "$(dirname 'system/bin/iconv')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/iconv'
mkdir -p "$(dirname 'system/bin/id')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/id'
mkdir -p "$(dirname 'system/bin/ifconfig')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/ifconfig'
mkdir -p "$(dirname 'system/bin/inotifyd')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/inotifyd'
mkdir -p "$(dirname 'system/bin/insmod')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/insmod'
mkdir -p "$(dirname 'system/bin/install')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/install'
mkdir -p "$(dirname 'system/bin/ionice')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/ionice'
mkdir -p "$(dirname 'system/bin/iorenice')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/iorenice'
mkdir -p "$(dirname 'system/bin/ip-wrapper-1.0')" 2>/dev/null
ln -sfn 'netutils-wrapper-1.0' 'system/bin/ip-wrapper-1.0'
mkdir -p "$(dirname 'system/bin/ip6tables-restore')" 2>/dev/null
ln -sfn 'ip6tables' 'system/bin/ip6tables-restore'
mkdir -p "$(dirname 'system/bin/ip6tables-save')" 2>/dev/null
ln -sfn 'ip6tables' 'system/bin/ip6tables-save'
mkdir -p "$(dirname 'system/bin/ip6tables-wrapper-1.0')" 2>/dev/null
ln -sfn 'netutils-wrapper-1.0' 'system/bin/ip6tables-wrapper-1.0'
mkdir -p "$(dirname 'system/bin/iptables-restore')" 2>/dev/null
ln -sfn 'iptables' 'system/bin/iptables-restore'
mkdir -p "$(dirname 'system/bin/iptables-save')" 2>/dev/null
ln -sfn 'iptables' 'system/bin/iptables-save'
mkdir -p "$(dirname 'system/bin/iptables-wrapper-1.0')" 2>/dev/null
ln -sfn 'netutils-wrapper-1.0' 'system/bin/iptables-wrapper-1.0'
mkdir -p "$(dirname 'system/bin/kill')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/kill'
mkdir -p "$(dirname 'system/bin/killall')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/killall'
mkdir -p "$(dirname 'system/bin/linker64')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/bin/linker64' 'system/bin/linker64'
mkdir -p "$(dirname 'system/bin/linker_asan64')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/bin/linker64' 'system/bin/linker_asan64'
mkdir -p "$(dirname 'system/bin/ln')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/ln'
mkdir -p "$(dirname 'system/bin/load_policy')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/load_policy'
mkdir -p "$(dirname 'system/bin/log')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/log'
mkdir -p "$(dirname 'system/bin/logname')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/logname'
mkdir -p "$(dirname 'system/bin/losetup')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/losetup'
mkdir -p "$(dirname 'system/bin/ls')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/ls'
mkdir -p "$(dirname 'system/bin/lsmod')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/lsmod'
mkdir -p "$(dirname 'system/bin/lsof')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/lsof'
mkdir -p "$(dirname 'system/bin/lspci')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/lspci'
mkdir -p "$(dirname 'system/bin/lsusb')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/lsusb'
mkdir -p "$(dirname 'system/bin/md5sum')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/md5sum'
mkdir -p "$(dirname 'system/bin/microcom')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/microcom'
mkdir -p "$(dirname 'system/bin/mkdir')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/mkdir'
mkdir -p "$(dirname 'system/bin/mkfifo')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/mkfifo'
mkdir -p "$(dirname 'system/bin/mkfs.ext2')" 2>/dev/null
ln -sfn 'mke2fs' 'system/bin/mkfs.ext2'
mkdir -p "$(dirname 'system/bin/mkfs.ext3')" 2>/dev/null
ln -sfn 'mke2fs' 'system/bin/mkfs.ext3'
mkdir -p "$(dirname 'system/bin/mkfs.ext4')" 2>/dev/null
ln -sfn 'mke2fs' 'system/bin/mkfs.ext4'
mkdir -p "$(dirname 'system/bin/mknod')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/mknod'
mkdir -p "$(dirname 'system/bin/mkswap')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/mkswap'
mkdir -p "$(dirname 'system/bin/mktemp')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/mktemp'
mkdir -p "$(dirname 'system/bin/modinfo')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/modinfo'
mkdir -p "$(dirname 'system/bin/modprobe')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/modprobe'
mkdir -p "$(dirname 'system/bin/more')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/more'
mkdir -p "$(dirname 'system/bin/mount')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/mount'
mkdir -p "$(dirname 'system/bin/mountpoint')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/mountpoint'
mkdir -p "$(dirname 'system/bin/mv')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/mv'
mkdir -p "$(dirname 'system/bin/nc')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/nc'
mkdir -p "$(dirname 'system/bin/ndc-wrapper-1.0')" 2>/dev/null
ln -sfn 'netutils-wrapper-1.0' 'system/bin/ndc-wrapper-1.0'
mkdir -p "$(dirname 'system/bin/netcat')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/netcat'
mkdir -p "$(dirname 'system/bin/netstat')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/netstat'
mkdir -p "$(dirname 'system/bin/nice')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/nice'
mkdir -p "$(dirname 'system/bin/nl')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/nl'
mkdir -p "$(dirname 'system/bin/nohup')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/nohup'
mkdir -p "$(dirname 'system/bin/nproc')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/nproc'
mkdir -p "$(dirname 'system/bin/nsenter')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/nsenter'
mkdir -p "$(dirname 'system/bin/oatdump')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/bin/oatdump' 'system/bin/oatdump'
mkdir -p "$(dirname 'system/bin/od')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/od'
mkdir -p "$(dirname 'system/bin/paste')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/paste'
mkdir -p "$(dirname 'system/bin/patch')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/patch'
mkdir -p "$(dirname 'system/bin/pgrep')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/pgrep'
mkdir -p "$(dirname 'system/bin/pidof')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/pidof'
mkdir -p "$(dirname 'system/bin/pkill')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/pkill'
mkdir -p "$(dirname 'system/bin/pmap')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/pmap'
mkdir -p "$(dirname 'system/bin/printenv')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/printenv'
mkdir -p "$(dirname 'system/bin/printf')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/printf'
mkdir -p "$(dirname 'system/bin/profman')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/bin/profman' 'system/bin/profman'
mkdir -p "$(dirname 'system/bin/ps')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/ps'
mkdir -p "$(dirname 'system/bin/pwd')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/pwd'
mkdir -p "$(dirname 'system/bin/readlink')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/readlink'
mkdir -p "$(dirname 'system/bin/realpath')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/realpath'
mkdir -p "$(dirname 'system/bin/renice')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/renice'
mkdir -p "$(dirname 'system/bin/resize.f2fs')" 2>/dev/null
ln -sfn 'fsck.f2fs' 'system/bin/resize.f2fs'
mkdir -p "$(dirname 'system/bin/restorecon')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/restorecon'
mkdir -p "$(dirname 'system/bin/rm')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/rm'
mkdir -p "$(dirname 'system/bin/rmdir')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/rmdir'
mkdir -p "$(dirname 'system/bin/rmmod')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/rmmod'
mkdir -p "$(dirname 'system/bin/runcon')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/runcon'
mkdir -p "$(dirname 'system/bin/sed')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/sed'
mkdir -p "$(dirname 'system/bin/sendevent')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/sendevent'
mkdir -p "$(dirname 'system/bin/seq')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/seq'
mkdir -p "$(dirname 'system/bin/setenforce')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/setenforce'
mkdir -p "$(dirname 'system/bin/setprop')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/setprop'
mkdir -p "$(dirname 'system/bin/setsid')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/setsid'
mkdir -p "$(dirname 'system/bin/sha1sum')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/sha1sum'
mkdir -p "$(dirname 'system/bin/sha224sum')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/sha224sum'
mkdir -p "$(dirname 'system/bin/sha256sum')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/sha256sum'
mkdir -p "$(dirname 'system/bin/sha384sum')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/sha384sum'
mkdir -p "$(dirname 'system/bin/sha512sum')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/sha512sum'
mkdir -p "$(dirname 'system/bin/sleep')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/sleep'
mkdir -p "$(dirname 'system/bin/sort')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/sort'
mkdir -p "$(dirname 'system/bin/split')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/split'
mkdir -p "$(dirname 'system/bin/start')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/start'
mkdir -p "$(dirname 'system/bin/stat')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/stat'
mkdir -p "$(dirname 'system/bin/stop')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/stop'
mkdir -p "$(dirname 'system/bin/strings')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/strings'
mkdir -p "$(dirname 'system/bin/stty')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/stty'
mkdir -p "$(dirname 'system/bin/swapoff')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/swapoff'
mkdir -p "$(dirname 'system/bin/swapon')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/swapon'
mkdir -p "$(dirname 'system/bin/sync')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/sync'
mkdir -p "$(dirname 'system/bin/sysctl')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/sysctl'
mkdir -p "$(dirname 'system/bin/tac')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/tac'
mkdir -p "$(dirname 'system/bin/tail')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/tail'
mkdir -p "$(dirname 'system/bin/tar')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/tar'
mkdir -p "$(dirname 'system/bin/taskset')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/taskset'
mkdir -p "$(dirname 'system/bin/tc-wrapper-1.0')" 2>/dev/null
ln -sfn 'netutils-wrapper-1.0' 'system/bin/tc-wrapper-1.0'
mkdir -p "$(dirname 'system/bin/tee')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/tee'
mkdir -p "$(dirname 'system/bin/time')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/time'
mkdir -p "$(dirname 'system/bin/timeout')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/timeout'
mkdir -p "$(dirname 'system/bin/top')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/top'
mkdir -p "$(dirname 'system/bin/touch')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/touch'
mkdir -p "$(dirname 'system/bin/tr')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/tr'
mkdir -p "$(dirname 'system/bin/true')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/true'
mkdir -p "$(dirname 'system/bin/truncate')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/truncate'
mkdir -p "$(dirname 'system/bin/tty')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/tty'
mkdir -p "$(dirname 'system/bin/ueventd')" 2>/dev/null
ln -sfn 'init' 'system/bin/ueventd'
mkdir -p "$(dirname 'system/bin/ulimit')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/ulimit'
mkdir -p "$(dirname 'system/bin/umount')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/umount'
mkdir -p "$(dirname 'system/bin/uname')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/uname'
mkdir -p "$(dirname 'system/bin/uniq')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/uniq'
mkdir -p "$(dirname 'system/bin/unix2dos')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/unix2dos'
mkdir -p "$(dirname 'system/bin/unlink')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/unlink'
mkdir -p "$(dirname 'system/bin/unshare')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/unshare'
mkdir -p "$(dirname 'system/bin/uptime')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/uptime'
mkdir -p "$(dirname 'system/bin/usleep')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/usleep'
mkdir -p "$(dirname 'system/bin/uudecode')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/uudecode'
mkdir -p "$(dirname 'system/bin/uuencode')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/uuencode'
mkdir -p "$(dirname 'system/bin/uuidgen')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/uuidgen'
mkdir -p "$(dirname 'system/bin/vmstat')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/vmstat'
mkdir -p "$(dirname 'system/bin/watch')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/watch'
mkdir -p "$(dirname 'system/bin/wc')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/wc'
mkdir -p "$(dirname 'system/bin/which')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/which'
mkdir -p "$(dirname 'system/bin/whoami')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/whoami'
mkdir -p "$(dirname 'system/bin/xargs')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/xargs'
mkdir -p "$(dirname 'system/bin/xxd')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/xxd'
mkdir -p "$(dirname 'system/bin/yes')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/yes'
mkdir -p "$(dirname 'system/bin/zcat')" 2>/dev/null
ln -sfn 'toybox' 'system/bin/zcat'
mkdir -p "$(dirname 'system/fonts/DroidSans-Bold.ttf')" 2>/dev/null
ln -sfn 'Roboto-Bold.ttf' 'system/fonts/DroidSans-Bold.ttf'
mkdir -p "$(dirname 'system/fonts/DroidSans.ttf')" 2>/dev/null
ln -sfn 'Roboto-Regular.ttf' 'system/fonts/DroidSans.ttf'
mkdir -p "$(dirname 'system/framework/arm64/apex-android.test.base.vdex')" 2>/dev/null
ln -sfn '../apex-android.test.base.vdex' 'system/framework/arm64/apex-android.test.base.vdex'
mkdir -p "$(dirname 'system/framework/arm64/apex-apache-xml.vdex')" 2>/dev/null
ln -sfn '../apex-apache-xml.vdex' 'system/framework/arm64/apex-apache-xml.vdex'
mkdir -p "$(dirname 'system/framework/arm64/apex-bouncycastle.vdex')" 2>/dev/null
ln -sfn '../apex-bouncycastle.vdex' 'system/framework/arm64/apex-bouncycastle.vdex'
mkdir -p "$(dirname 'system/framework/arm64/apex-core-libart.vdex')" 2>/dev/null
ln -sfn '../apex-core-libart.vdex' 'system/framework/arm64/apex-core-libart.vdex'
mkdir -p "$(dirname 'system/framework/arm64/apex-ext.vdex')" 2>/dev/null
ln -sfn '../apex-ext.vdex' 'system/framework/arm64/apex-ext.vdex'
mkdir -p "$(dirname 'system/framework/arm64/apex-framework.vdex')" 2>/dev/null
ln -sfn '../apex-framework.vdex' 'system/framework/arm64/apex-framework.vdex'
mkdir -p "$(dirname 'system/framework/arm64/apex-ims-common.vdex')" 2>/dev/null
ln -sfn '../apex-ims-common.vdex' 'system/framework/arm64/apex-ims-common.vdex'
mkdir -p "$(dirname 'system/framework/arm64/apex-okhttp.vdex')" 2>/dev/null
ln -sfn '../apex-okhttp.vdex' 'system/framework/arm64/apex-okhttp.vdex'
mkdir -p "$(dirname 'system/framework/arm64/apex-telephony-common.vdex')" 2>/dev/null
ln -sfn '../apex-telephony-common.vdex' 'system/framework/arm64/apex-telephony-common.vdex'
mkdir -p "$(dirname 'system/framework/arm64/apex-voip-common.vdex')" 2>/dev/null
ln -sfn '../apex-voip-common.vdex' 'system/framework/arm64/apex-voip-common.vdex'
mkdir -p "$(dirname 'system/framework/arm64/apex.vdex')" 2>/dev/null
ln -sfn '../apex.vdex' 'system/framework/arm64/apex.vdex'
mkdir -p "$(dirname 'system/framework/arm64/boot-android.test.base.vdex')" 2>/dev/null
ln -sfn '../boot-android.test.base.vdex' 'system/framework/arm64/boot-android.test.base.vdex'
mkdir -p "$(dirname 'system/framework/arm64/boot-apache-xml.vdex')" 2>/dev/null
ln -sfn '../boot-apache-xml.vdex' 'system/framework/arm64/boot-apache-xml.vdex'
mkdir -p "$(dirname 'system/framework/arm64/boot-bouncycastle.vdex')" 2>/dev/null
ln -sfn '../boot-bouncycastle.vdex' 'system/framework/arm64/boot-bouncycastle.vdex'
mkdir -p "$(dirname 'system/framework/arm64/boot-core-libart.vdex')" 2>/dev/null
ln -sfn '../boot-core-libart.vdex' 'system/framework/arm64/boot-core-libart.vdex'
mkdir -p "$(dirname 'system/framework/arm64/boot-ext.vdex')" 2>/dev/null
ln -sfn '../boot-ext.vdex' 'system/framework/arm64/boot-ext.vdex'
mkdir -p "$(dirname 'system/framework/arm64/boot-framework.vdex')" 2>/dev/null
ln -sfn '../boot-framework.vdex' 'system/framework/arm64/boot-framework.vdex'
mkdir -p "$(dirname 'system/framework/arm64/boot-ims-common.vdex')" 2>/dev/null
ln -sfn '../boot-ims-common.vdex' 'system/framework/arm64/boot-ims-common.vdex'
mkdir -p "$(dirname 'system/framework/arm64/boot-okhttp.vdex')" 2>/dev/null
ln -sfn '../boot-okhttp.vdex' 'system/framework/arm64/boot-okhttp.vdex'
mkdir -p "$(dirname 'system/framework/arm64/boot-telephony-common.vdex')" 2>/dev/null
ln -sfn '../boot-telephony-common.vdex' 'system/framework/arm64/boot-telephony-common.vdex'
mkdir -p "$(dirname 'system/framework/arm64/boot-voip-common.vdex')" 2>/dev/null
ln -sfn '../boot-voip-common.vdex' 'system/framework/arm64/boot-voip-common.vdex'
mkdir -p "$(dirname 'system/framework/arm64/boot.vdex')" 2>/dev/null
ln -sfn '../boot.vdex' 'system/framework/arm64/boot.vdex'
mkdir -p "$(dirname 'system/lib64/libc.so')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/lib64/bionic/libc.so' 'system/lib64/libc.so'
mkdir -p "$(dirname 'system/lib64/libdl.so')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/lib64/bionic/libdl.so' 'system/lib64/libdl.so'
mkdir -p "$(dirname 'system/lib64/libm.so')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/lib64/bionic/libm.so' 'system/lib64/libm.so'
mkdir -p "$(dirname 'system/product/app/Camera2/lib/arm64/libjni_jpegutil.so')" 2>/dev/null
ln -sfn '/system/product/lib64/libjni_jpegutil.so' 'system/product/app/Camera2/lib/arm64/libjni_jpegutil.so'
mkdir -p "$(dirname 'system/product/app/Camera2/lib/arm64/libjni_tinyplanet.so')" 2>/dev/null
ln -sfn '/system/product/lib64/libjni_tinyplanet.so' 'system/product/app/Camera2/lib/arm64/libjni_tinyplanet.so'
mkdir -p "$(dirname 'system/product/app/Gallery2/lib/arm64/libjni_eglfence.so')" 2>/dev/null
ln -sfn '/system/product/lib64/libjni_eglfence.so' 'system/product/app/Gallery2/lib/arm64/libjni_eglfence.so'
mkdir -p "$(dirname 'system/product/app/Gallery2/lib/arm64/libjni_filtershow_filters.so')" 2>/dev/null
ln -sfn '/system/product/lib64/libjni_filtershow_filters.so' 'system/product/app/Gallery2/lib/arm64/libjni_filtershow_filters.so'
mkdir -p "$(dirname 'system/product/app/Gallery2/lib/arm64/libjni_jpegstream.so')" 2>/dev/null
ln -sfn '/system/product/lib64/libjni_jpegstream.so' 'system/product/app/Gallery2/lib/arm64/libjni_jpegstream.so'
mkdir -p "$(dirname 'system/product/app/LatinIME/lib/arm64/libjni_latinime.so')" 2>/dev/null
ln -sfn '/system/product/lib64/libjni_latinime.so' 'system/product/app/LatinIME/lib/arm64/libjni_latinime.so'
mkdir -p "$(dirname 'system/usr/icu')" 2>/dev/null
ln -sfn '/apex/com.android.runtime/etc/icu' 'system/usr/icu'
mkdir -p "$(dirname 'system/vendor')" 2>/dev/null
ln -sfn '/vendor' 'system/vendor'
mkdir -p apex'
ln -sfn /system/apex/com.android.runtime.debug apex/com.android.runtime
echo links10-done
