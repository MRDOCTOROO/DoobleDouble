//
// io_redirect.h —— 文件访问重定向
//
// 应用在容器内运行时，看到的应该是自己的私有目录，
// 但进程实际跑在宿主 uid 下，真实可写路径只有宿主的 filesDir。
// 所以要把目标应用写死的绝对路径「翻译」到空间私有目录：
//
//   /data/data/com.instagram.android/xxx        →  <space>/data/com.instagram.android/xxx
//   /data/user/0/com.instagram.android/xxx      →  <space>/user/0/com.instagram.android/xxx
//   /sdcard/Pictures/xxx                        →  <space>/sdcard/Pictures/xxx
//   /storage/emulated/0/Android/data/<pkg>/xxx  →  <space>/sdcard/Android/data/<pkg>/xxx
//
// 不做这件事的后果：两个空间里的同一个应用会共享同一份 SharedPreferences /
// 数据库 / 缓存，多开就变成了"数据串号"，甚至互相覆盖登录态。
//
#pragma once

#include <stddef.h>

namespace dooble {

/// 安装 IO 重定向钩子，返回成功替换的 GOT 表项总数。
/// @param space_root     空间私有根目录（宿主的 filesDir 下）
/// @param virtual_package 当前进程所代理的虚拟应用包名
int io_redirect_install(const char* space_root, const char* virtual_package);

/// 按当前规则转换单条路径。未命中规则时原样返回。
const char* io_redirect_path(const char* path, char* out, size_t out_len);

/// 规则摘要，供自检面板展示
const char* io_redirect_summary();

}  // namespace dooble
