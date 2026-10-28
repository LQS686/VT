import os
import sys

from ext4 import (
    Volume,
    Directory,
    File,
    SymbolicLink,
)

src = sys.argv[1]
dst = sys.argv[2]
manifest_path = dst + ".manifest"
nonroot = "--nonroot" in sys.argv

vol = Volume(open(src, "rb"))
manifest = open(manifest_path, "w", encoding="utf-8")


def walk(directory: Directory, out: str, rel: str) -> None:
    os.makedirs(out, exist_ok=True)
    for dirent, _ft in directory.opendir():
        name = dirent.name_str
        if name in (".", ".."):
            continue
        try:
            inode = directory.inode_at(name)
        except Exception as exc:  # noqa: BLE001
            print(f"ERR inode {rel}/{name}: {exc}", flush=True)
            continue
        path = os.path.join(out, name)
        relpath = f"{rel}/{name}" if rel else name
        mode = inode.i_mode & 0o7777
        if isinstance(inode, Directory):
            walk(inode, path, relpath)
        elif isinstance(inode, SymbolicLink):
            target = inode.readlink().decode("utf-8", "replace")
            manifest.write(f"SYMLINK {mode:o} {relpath} -> {target}\n")
            try:
                os.symlink(target, path)
            except OSError:
                with open(path + ".symlink", "w", encoding="utf-8") as fh:
                    fh.write(target)
        elif isinstance(inode, File):
            try:
                with inode.open() as srcf, open(path, "wb") as dstf:
                    while True:
                        chunk = srcf.read(1 << 20)
                        if not chunk:
                            break
                        dstf.write(chunk)
                manifest.write(f"FILE {mode:o} {inode.i_size} {relpath}\n")
            except Exception as exc:  # noqa: BLE001
                print(f"ERR file {relpath}: {exc}", flush=True)
        # device/fifo/socket nodes are recreated by init; skip.


root = vol.root
# system.img root contains system/ plus top-level links; walk all.
walk(root, dst, "")
manifest.close()
print("DONE", dst, flush=True)
