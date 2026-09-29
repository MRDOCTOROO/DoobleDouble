//
// plt_hook.h —— 轻量 GOT/PLT Hook
//
// 为什么需要它：
//   虚拟化容器要让"未安装的 APK"跑起来，光靠 Java 层 hook 不够。
//   应用里大量文件访问是写死绝对路径的（/data/data/<pkg>/...），
//   这些调用直接进 libc，Java 层拦不到，必须在 native 层把
//   libc 的 open/openat/stat 等函数重定向到空间私有目录。
//
// 实现方式：
//   解析 ELF 的 PT_DYNAMIC，找到 DT_JMPREL（.rela.plt）与 DT_RELA/DT_REL，
//   逐条匹配符号名，把 GOT 表项指向我们的替身函数，原函数地址通过
//   out_original 返回，替身里再转调回去，实现"环绕式"拦截。
//
// 局限（诚实说明）：
//   1. 只能拦截**通过 PLT/GOT 调用**的场景。如果调用方是用
//      dlsym 拿到函数地址后直接 blr，或者被 inline hook 过，则拦不到；
//   2. Android 6+ 默认开启 packed relocations（APS2 压缩格式），
//      DT_JMPREL 可能为空。本实现会对未压缩的情况生效；要全覆盖
//      建议引入 Dobby / xDL（见 CMakeLists 的 USE_DOBBY 开关）。
//
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace dooble {

/// 对指定 so 做 PLT hook。
/// @param lib_name   目标 so 的文件名，例如 "libjavacore.so"
/// @param symbol     要拦截的符号名，例如 "open"
/// @param replacement 替身函数地址
/// @param out_original 输出参数，回填原函数地址（可为 nullptr）
/// @return 成功替换的 GOT 表项数量，0 表示没找到
int hook_plt(const char* lib_name, const char* symbol, void* replacement, void** out_original);

/// 对**所有已加载模块**做 PLT hook（排除黑名单），返回替换总数。
int hook_plt_all(const char* symbol, void* replacement, void** out_original);

/// 扫描指定模块中某个符号当前指向的地址，用于自检/调试。
void* find_plt_target(const char* lib_name, const char* symbol);

}  // namespace dooble
