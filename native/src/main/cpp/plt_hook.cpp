#include "plt_hook.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define LOG_TAG "DoobleHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if defined(__LP64__)
#define DOOBLE_R_SYM(x) ELF64_R_SYM(x)
#define DOOBLE_R_TYPE(x) ELF64_R_TYPE(x)
#else
#define DOOBLE_R_SYM(x) ELF32_R_SYM(x)
#define DOOBLE_R_TYPE(x) ELF32_R_TYPE(x)
#endif

namespace dooble {
namespace {

constexpr size_t kMaxModules = 512;

struct ModuleInfo {
    uintptr_t base = 0;
    const ElfW(Phdr)* phdr = nullptr;
    size_t phnum = 0;
    const char* name = nullptr;
};

struct ElfTables {
    const ElfW(Dyn)* dyn = nullptr;
    const char* strtab = nullptr;
    const ElfW(Sym)* symtab = nullptr;
    const void* jmprel = nullptr;
    size_t jmprelsz = 0;
    int pltrel_type = 0;
    const void* rela = nullptr;
    size_t relasz = 0;
    const void* rel = nullptr;
    size_t relsz = 0;
};

/// DT_STRTAB / DT_SYMTAB 在部分平台上已经是绝对地址，部分平台是相对 vaddr。
/// 用"是否落在模块地址空间内"做启发式判断，两种都能兼容。
inline uintptr_t fix_ptr(uintptr_t value, uintptr_t base, size_t module_size) {
    if (value >= base && value < base + module_size) return value;
    return base + value;
}

bool read_tables(const ModuleInfo& mod, ElfTables* out) {
    const ElfW(Dyn)* dyn = nullptr;
    uintptr_t module_end = 0;

    for (size_t i = 0; i < mod.phnum; ++i) {
        const ElfW(Phdr)& ph = mod.phdr[i];
        if (ph.p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const ElfW(Dyn)*>(mod.base + ph.p_vaddr);
        }
        if (ph.p_type == PT_LOAD) {
            uintptr_t end = mod.base + ph.p_vaddr + ph.p_memsz;
            if (end > module_end) module_end = end;
        }
    }
    if (!dyn) return false;

    const size_t module_size = module_end > mod.base ? module_end - mod.base : (1u << 20);
    out->dyn = dyn;

    for (const ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; ++d) {
        switch (d->d_tag) {
            case DT_STRTAB:
                out->strtab = reinterpret_cast<const char*>(
                        fix_ptr(static_cast<uintptr_t>(d->d_un.d_ptr), mod.base, module_size));
                break;
            case DT_SYMTAB:
                out->symtab = reinterpret_cast<const ElfW(Sym)*>(
                        fix_ptr(static_cast<uintptr_t>(d->d_un.d_ptr), mod.base, module_size));
                break;
            case DT_JMPREL:
                out->jmprel = reinterpret_cast<const void*>(
                        fix_ptr(static_cast<uintptr_t>(d->d_un.d_ptr), mod.base, module_size));
                break;
            case DT_PLTRELSZ:
                out->jmprelsz = d->d_un.d_val;
                break;
            case DT_PLTREL:
                out->pltrel_type = static_cast<int>(d->d_un.d_val);
                break;
            case DT_RELA:
                out->rela = reinterpret_cast<const void*>(
                        fix_ptr(static_cast<uintptr_t>(d->d_un.d_ptr), mod.base, module_size));
                break;
            case DT_RELASZ:
                out->relasz = d->d_un.d_val;
                break;
            case DT_REL:
                out->rel = reinterpret_cast<const void*>(
                        fix_ptr(static_cast<uintptr_t>(d->d_un.d_ptr), mod.base, module_size));
                break;
            case DT_RELSZ:
                out->relsz = d->d_un.d_val;
                break;
            default:
                break;
        }
    }
    return out->strtab != nullptr && out->symtab != nullptr;
}

bool make_writable(void* addr, size_t len) {
    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) return false;
    const uintptr_t start = reinterpret_cast<uintptr_t>(addr) & ~static_cast<uintptr_t>(page - 1);
    const uintptr_t end = (reinterpret_cast<uintptr_t>(addr) + len + page - 1) &
                          ~static_cast<uintptr_t>(page - 1);
    return mprotect(reinterpret_cast<void*>(start), end - start,
                    PROT_READ | PROT_WRITE) == 0;
}

/// 在 ElfW(Rela) 表里找符号并替换 GOT 表项
int patch_rela(const ModuleInfo& mod, const ElfTables& t, const char* symbol,
               void* replacement, void** out_original) {
    if (!t.jmprel || t.pltrel_type != DT_RELA) return 0;
    const auto* relas = reinterpret_cast<const ElfW(Rela)*>(t.jmprel);
    const size_t count = t.jmprelsz / sizeof(ElfW(Rela));

    int patched = 0;
    for (size_t i = 0; i < count; ++i) {
        const ElfW(Rela)& r = relas[i];
        const size_t sym_idx = DOOBLE_R_SYM(r.r_info);
        const char* name = t.strtab + t.symtab[sym_idx].st_name;
        if (strcmp(name, symbol) != 0) continue;

        auto* slot = reinterpret_cast<void**>(mod.base + r.r_offset);
        if (out_original != nullptr && *out_original == nullptr) {
            *out_original = *slot;
        }
        if (make_writable(slot, sizeof(void*))) {
            *slot = replacement;
            ++patched;
        }
    }
    return patched;
}

int patch_rel(const ModuleInfo& mod, const ElfTables& t, const char* symbol,
              void* replacement, void** out_original) {
    if (!t.rel || t.relsz == 0) return 0;
    const auto* rels = reinterpret_cast<const ElfW(Rel)*>(t.rel);
    const size_t count = t.relsz / sizeof(ElfW(Rel));

    int patched = 0;
    for (size_t i = 0; i < count; ++i) {
        const ElfW(Rel)& r = rels[i];
        const size_t sym_idx = DOOBLE_R_SYM(r.r_info);
        const char* name = t.strtab + t.symtab[sym_idx].st_name;
        if (strcmp(name, symbol) != 0) continue;

        auto* slot = reinterpret_cast<void**>(mod.base + r.r_offset);
        if (out_original != nullptr && *out_original == nullptr) {
            *out_original = *slot;
        }
        if (make_writable(slot, sizeof(void*))) {
            *slot = replacement;
            ++patched;
        }
    }
    return patched;
}

struct CollectCtx {
    ModuleInfo* modules;
    size_t count;
    size_t capacity;
};

int collect_cb(struct dl_phdr_info* info, size_t size, void* data) {
    auto* ctx = static_cast<CollectCtx*>(data);
    if (ctx->count >= ctx->capacity) return 1;
    ctx->modules[ctx->count++] = ModuleInfo{
            static_cast<uintptr_t>(info->dlpi_addr),
            info->dlpi_phdr,
            static_cast<size_t>(info->dlpi_phnum),
            info->dlpi_name,
    };
    return 0;
}

const char* basename_of(const char* path) {
    if (path == nullptr || *path == '\0') return "";
    const char* slash = strrchr(path, '/');
    return slash != nullptr ? slash + 1 : path;
}

}  // namespace

int hook_plt(const char* lib_name, const char* symbol, void* replacement, void** out_original) {
    ModuleInfo modules[kMaxModules];
    CollectCtx ctx{modules, 0, kMaxModules};
    dl_iterate_phdr(collect_cb, &ctx);

    for (size_t i = 0; i < ctx.count; ++i) {
        const ModuleInfo& mod = modules[i];
        if (strcmp(basename_of(mod.name), lib_name) != 0) continue;

        ElfTables tables{};
        if (!read_tables(mod, &tables)) {
            LOGE("read_tables failed for %s", lib_name);
            return 0;
        }
        int n = patch_rela(mod, tables, symbol, replacement, out_original);
        n += patch_rel(mod, tables, symbol, replacement, out_original);
        if (n > 0) {
            LOGI("hooked %s@%s (%d slot(s))", symbol, lib_name, n);
        }
        return n;
    }
    return 0;
}

int hook_plt_all(const char* symbol, void* replacement, void** out_original) {
    ModuleInfo modules[kMaxModules];
    CollectCtx ctx{modules, 0, kMaxModules};
    dl_iterate_phdr(collect_cb, &ctx);

    int total = 0;
    for (size_t i = 0; i < ctx.count; ++i) {
        const ModuleInfo& mod = modules[i];
        // 跳过主程序和自身，避免把自己也 hook 了
        if (mod.name == nullptr || *mod.name == '\0') continue;
        const char* base = basename_of(mod.name);
        if (strcmp(base, "libdooble_native.so") == 0) continue;
        if (strcmp(base, "libc.so") == 0) continue;
        if (strcmp(base, "libdl.so") == 0) continue;

        ElfTables tables{};
        if (!read_tables(mod, &tables)) continue;

        int n = patch_rela(mod, tables, symbol, replacement, out_original);
        n += patch_rel(mod, tables, symbol, replacement, out_original);
        total += n;
    }
    return total;
}

void* find_plt_target(const char* lib_name, const char* symbol) {
    ModuleInfo modules[kMaxModules];
    CollectCtx ctx{modules, 0, kMaxModules};
    dl_iterate_phdr(collect_cb, &ctx);

    for (size_t i = 0; i < ctx.count; ++i) {
        const ModuleInfo& mod = modules[i];
        if (strcmp(basename_of(mod.name), lib_name) != 0) continue;

        ElfTables tables{};
        if (!read_tables(mod, &tables)) return nullptr;

        if (tables.jmprel != nullptr && tables.pltrel_type == DT_RELA) {
            const auto* relas = reinterpret_cast<const ElfW(Rela)*>(tables.jmprel);
            const size_t count = tables.jmprelsz / sizeof(ElfW(Rela));
            for (size_t k = 0; k < count; ++k) {
                const size_t sym_idx = DOOBLE_R_SYM(relas[k].r_info);
                const char* name = tables.strtab + tables.symtab[sym_idx].st_name;
                if (strcmp(name, symbol) == 0) {
                    return *reinterpret_cast<void**>(mod.base + relas[k].r_offset);
                }
            }
        }
        return nullptr;
    }
    return nullptr;
}

}  // namespace dooble
