#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

typedef int (*vbinder_ioctl_fn)(int fd, unsigned long request, void *arg);
static vbinder_ioctl_fn g_vbinder_ioctl;
static int g_vbinder_fd = -1;
static const char *g_vbinder_path;
static int g_ready;
static int (*real_open)(const char *, int, ...);
static int (*real_openat)(int, const char *, int, ...);
static int (*real_ioctl)(int, unsigned long, ...);
static void *(*real_mmap)(void *, size_t, int, int, int, off_t);

static void vbinder_init(void) {
    if (g_ready) return;
    g_ready = 1;
    real_open = dlsym(RTLD_NEXT, "open");
    real_openat = dlsym(RTLD_NEXT, "openat");
    real_ioctl = dlsym(RTLD_NEXT, "ioctl");
    real_mmap = dlsym(RTLD_NEXT, "mmap");
    g_vbinder_path = getenv("VBINDER_SOCKET");
    if (!g_vbinder_path) g_vbinder_path = "/dev/vbinder";
    const char *lib = getenv("VBINDER_LIB");
    if (!lib) lib = "libvbinder.so";
    void *h = dlopen(lib, RTLD_NOW | RTLD_GLOBAL);
    if (h) g_vbinder_ioctl = (vbinder_ioctl_fn)dlsym(h, "virtual_binder_ioctl");
    else fprintf(stderr, "vbinder_shim: dlopen(%s) failed: %s\n", lib, dlerror());
}

static int try_connect(const char *path) {
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    int rc = connect(fd, (struct sockaddr *)&addr, sizeof(addr));
    if (rc == 0) {
        fprintf(stderr, "vbinder_shim: connected %s\n", path);
        return fd;
    }
    fprintf(stderr, "vbinder_shim: connect %s failed errno=%d (%s)\n", path, errno, strerror(errno));
    close(fd);
    return -1;
}

static int vbinder_connect(void) {
    const char *paths[5];
    int n = 0;
    const char *env = getenv("VBINDER_SOCKET");
    if (env) paths[n++] = env;
    paths[n++] = "/dev/vbinder/bis";
    paths[n++] = "/dev/vbinder/bcs";
    paths[n++] = "/dev/vbinder";
    for (int i = 0; i < n; i++) {
        int fd = try_connect(paths[i]);
        if (fd >= 0) return fd;
    }
    int fd = real_open(g_vbinder_path, O_RDWR);
    fprintf(stderr, "vbinder_shim: fallback open %s -> %d\n", g_vbinder_path, fd);
    return fd;
}

int open(const char *path, int flags, ...) {
    vbinder_init();
    if (path && strcmp(path, "/dev/binder") == 0) {
        if (g_vbinder_fd < 0) g_vbinder_fd = vbinder_connect();
        return g_vbinder_fd;
    }
    va_list ap; va_start(ap, flags); int mode = va_arg(ap, int); va_end(ap);
    return real_open(path, flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...) {
    vbinder_init();
    if (path && strcmp(path, "/dev/binder") == 0) {
        if (g_vbinder_fd < 0) g_vbinder_fd = vbinder_connect();
        return g_vbinder_fd;
    }
    va_list ap; va_start(ap, flags); int mode = va_arg(ap, int); va_end(ap);
    return real_openat(dirfd, path, flags, mode);
}

int ioctl(int fd, int request, ...) {
    vbinder_init();
    va_list ap; va_start(ap, request); void *arg = va_arg(ap, void *); va_end(ap);
    if (fd == g_vbinder_fd && g_vbinder_ioctl) return g_vbinder_ioctl(fd, (unsigned long)(unsigned int)request, arg);
    return real_ioctl(fd, (unsigned long)(unsigned int)request, arg);
}

void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off) {
    vbinder_init();
    return real_mmap(addr, len, prot, flags, fd, off);
}
