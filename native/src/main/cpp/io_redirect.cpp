#include "io_redirect.h"
#include "plt_hook.h"

#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOG_TAG "DoobleIO"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace dooble {
namespace {

char g_space_root[PATH_MAX] = {0};
char g_virtual_pkg[256] = {0};
char g_summary[512] = {0};
bool g_installed = false;

// 原函数指针
int (*real_open)(const char*, int, ...) = nullptr;
int (*real_openat)(int, const char*, int, ...) = nullptr;
int (*real_access)(const char*, int) = nullptr;
int (*real_faccessat)(int, const char*, int, int) = nullptr;
int (*real_stat)(const char*, struct stat*) = nullptr;
int (*real_lstat)(const char*, struct stat*) = nullptr;
int (*real_rename)(const char*, const char*) = nullptr;
int (*real_unlink)(const char*) = nullptr;
int (*real_mkdir)(const char*, mode_t) = nullptr;
DIR* (*real_opendir)(const char*) = nullptr;

bool starts_with(const char* s, const char* prefix) {
    return strncmp(s, prefix, strlen(prefix)) == 0;
}

/// 把 "/data/data/<pkg>" 或 "/data/user/N/<pkg>" 映射到空间目录
bool map_app_data(const char* path, char* out, size_t out_len) {
    if (g_virtual_pkg[0] == '\0' || g_space_root[0] == '\0') return false;

    static const char* kPrefixes[] = {"/data/data/", "/data/user/0/", "/data/user_de/0/"};
    for (const char* prefix : kPrefixes) {
        if (!starts_with(path, prefix)) continue;
        const char* rest = path + strlen(prefix);
        if (!starts_with(rest, g_virtual_pkg)) continue;
        const char next = rest[strlen(g_virtual_pkg)];
        if (next != '\0' && next != '/') continue;  // 避免 com.foo 匹配到 com.foobar

        const char* suffix = rest + strlen(g_virtual_pkg);
        if (starts_with(prefix, "/data/user")) {
            snprintf(out, out_len, "%s/user/0/%s%s", g_space_root, g_virtual_pkg, suffix);
        } else {
            snprintf(out, out_len, "%s/data/%s%s", g_space_root, g_virtual_pkg, suffix);
        }
        return true;
    }
    return false;
}

/// 把外部存储映射到空间私有的 sdcard 目录
bool map_external(const char* path, char* out, size_t out_len) {
    if (g_space_root[0] == '\0') return false;

    static const char* kPrefixes[] = {
            "/sdcard/", "/storage/emulated/0/", "/storage/self/primary/", "/data/media/0/"};
    for (const char* prefix : kPrefixes) {
        if (!starts_with(path, prefix)) continue;
        snprintf(out, out_len, "%s/sdcard/%s", g_space_root, path + strlen(prefix));
        return true;
    }
    if (strcmp(path, "/sdcard") == 0 || strcmp(path, "/storage/emulated/0") == 0) {
        snprintf(out, out_len, "%s/sdcard", g_space_root);
        return true;
    }
    return false;
}

}  // namespace

const char* io_redirect_path(const char* path, char* out, size_t out_len) {
    if (path == nullptr || !g_installed) return path;
    if (path[0] != '/') return path;  // 相对路径交给 dirfd 语义处理

    if (map_app_data(path, out, out_len)) return out;
    if (map_external(path, out, out_len)) return out;
    return path;
}

// ------------------------------------------------------------------ 替身函数

int fake_open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    char buf[PATH_MAX];
    const char* p = io_redirect_path(path, buf, sizeof(buf));
    if (!real_open) real_open = reinterpret_cast<decltype(real_open)>(dlsym(RTLD_NEXT, "open"));
    return real_open ? real_open(p, flags, mode) : -1;
}

int fake_openat(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    char buf[PATH_MAX];
    const char* p = io_redirect_path(path, buf, sizeof(buf));
    if (!real_openat) real_openat = reinterpret_cast<decltype(real_openat)>(dlsym(RTLD_NEXT, "openat"));
    return real_openat ? real_openat(dirfd, p, flags, mode) : -1;
}

int fake_access(const char* path, int mode) {
    char buf[PATH_MAX];
    const char* p = io_redirect_path(path, buf, sizeof(buf));
    if (!real_access) real_access = reinterpret_cast<decltype(real_access)>(dlsym(RTLD_NEXT, "access"));
    return real_access ? real_access(p, mode) : -1;
}

int fake_faccessat(int dirfd, const char* path, int mode, int flags) {
    char buf[PATH_MAX];
    const char* p = io_redirect_path(path, buf, sizeof(buf));
    if (!real_faccessat) real_faccessat = reinterpret_cast<decltype(real_faccessat)>(dlsym(RTLD_NEXT, "faccessat"));
    return real_faccessat ? real_faccessat(dirfd, p, mode, flags) : -1;
}

int fake_stat(const char* path, struct stat* st) {
    char buf[PATH_MAX];
    const char* p = io_redirect_path(path, buf, sizeof(buf));
    if (!real_stat) real_stat = reinterpret_cast<decltype(real_stat)>(dlsym(RTLD_NEXT, "stat"));
    return real_stat ? real_stat(p, st) : -1;
}

int fake_lstat(const char* path, struct stat* st) {
    char buf[PATH_MAX];
    const char* p = io_redirect_path(path, buf, sizeof(buf));
    if (!real_lstat) real_lstat = reinterpret_cast<decltype(real_lstat)>(dlsym(RTLD_NEXT, "lstat"));
    return real_lstat ? real_lstat(p, st) : -1;
}

int fake_rename(const char* old_path, const char* new_path) {
    char b1[PATH_MAX];
    char b2[PATH_MAX];
    const char* p1 = io_redirect_path(old_path, b1, sizeof(b1));
    const char* p2 = io_redirect_path(new_path, b2, sizeof(b2));
    if (!real_rename) real_rename = reinterpret_cast<decltype(real_rename)>(dlsym(RTLD_NEXT, "rename"));
    return real_rename ? real_rename(p1, p2) : -1;
}

int fake_unlink(const char* path) {
    char buf[PATH_MAX];
    const char* p = io_redirect_path(path, buf, sizeof(buf));
    if (!real_unlink) real_unlink = reinterpret_cast<decltype(real_unlink)>(dlsym(RTLD_NEXT, "unlink"));
    return real_unlink ? real_unlink(p) : -1;
}

int fake_mkdir(const char* path, mode_t mode) {
    char buf[PATH_MAX];
    const char* p = io_redirect_path(path, buf, sizeof(buf));
    if (!real_mkdir) real_mkdir = reinterpret_cast<decltype(real_mkdir)>(dlsym(RTLD_NEXT, "mkdir"));
    return real_mkdir ? real_mkdir(p, mode) : -1;
}

DIR* fake_opendir(const char* path) {
    char buf[PATH_MAX];
    const char* p = io_redirect_path(path, buf, sizeof(buf));
    if (!real_opendir) real_opendir = reinterpret_cast<decltype(real_opendir)>(dlsym(RTLD_NEXT, "opendir"));
    return real_opendir ? real_opendir(p) : nullptr;
}

// ------------------------------------------------------------------ 安装

int io_redirect_install(const char* space_root, const char* virtual_package) {
    if (space_root == nullptr || virtual_package == nullptr) return -1;

    snprintf(g_space_root, sizeof(g_space_root), "%s", space_root);
    snprintf(g_virtual_pkg, sizeof(g_virtual_pkg), "%s", virtual_package);

    // 预先把空间目录建出来，避免被重定向后首次写入失败
    char dir[PATH_MAX];
    snprintf(dir, sizeof(dir), "%s/data/%s", g_space_root, g_virtual_pkg);
    mkdir(g_space_root, 0700);
    mkdir(dir, 0700);
    snprintf(dir, sizeof(dir), "%s/sdcard", g_space_root);
    mkdir(dir, 0700);

    g_installed = true;

    int total = 0;
    total += hook_plt_all("open", reinterpret_cast<void*>(&fake_open),
                          reinterpret_cast<void**>(&real_open));
    total += hook_plt_all("openat", reinterpret_cast<void*>(&fake_openat),
                          reinterpret_cast<void**>(&real_openat));
    total += hook_plt_all("access", reinterpret_cast<void*>(&fake_access),
                          reinterpret_cast<void**>(&real_access));
    total += hook_plt_all("faccessat", reinterpret_cast<void*>(&fake_faccessat),
                          reinterpret_cast<void**>(&real_faccessat));
    total += hook_plt_all("stat", reinterpret_cast<void*>(&fake_stat),
                          reinterpret_cast<void**>(&real_stat));
    total += hook_plt_all("lstat", reinterpret_cast<void*>(&fake_lstat),
                          reinterpret_cast<void**>(&real_lstat));
    total += hook_plt_all("rename", reinterpret_cast<void*>(&fake_rename),
                          reinterpret_cast<void**>(&real_rename));
    total += hook_plt_all("unlink", reinterpret_cast<void*>(&fake_unlink),
                          reinterpret_cast<void**>(&real_unlink));
    total += hook_plt_all("mkdir", reinterpret_cast<void*>(&fake_mkdir),
                          reinterpret_cast<void**>(&real_mkdir));
    total += hook_plt_all("opendir", reinterpret_cast<void*>(&fake_opendir),
                          reinterpret_cast<void**>(&real_opendir));

    snprintf(g_summary, sizeof(g_summary),
             "space=%s pkg=%s hooked_slots=%d", g_space_root, g_virtual_pkg, total);
    LOGI("io redirect installed: %s", g_summary);
    return total;
}

const char* io_redirect_summary() {
    return g_summary[0] != '\0' ? g_summary : "not installed";
}

}  // namespace dooble
