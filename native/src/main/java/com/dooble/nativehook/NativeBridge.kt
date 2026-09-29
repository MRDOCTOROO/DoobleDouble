package com.dooble.nativehook

import android.util.Log

/**
 * native 层能力的 Kotlin 出口。
 *
 * 设计上**不允许**因为 so 加载失败而崩溃：库加载失败时 [available] 为 false，
 * 所有方法返回安全的默认值，上层据此降级（比如禁用 IO 重定向选项并提示用户）。
 * 这在 x86 模拟器、部分裁剪 ROM 上是常态。
 */
object NativeBridge {

    private const val TAG = "DoobleNative"

    val available: Boolean
    val version: String

    init {
        var ok = false
        var ver = "unavailable"
        try {
            System.loadLibrary("dooble_native")
            ok = true
            ver = runCatching { nativeVersion() }.getOrDefault("unknown")
        } catch (t: Throwable) {
            Log.w(TAG, "libdooble_native.so 加载失败，native 能力降级：${t.message}")
        }
        available = ok
        version = ver
    }

    /**
     * 在当前进程安装 IO 重定向。
     *
     * 注意：**必须**在目标应用的 Application.onCreate 之前调用，
     * 否则早期初始化的组件（比如 ContentProvider）已经读过一次文件了。
     * 正确的调用点是容器在 fork 出目标进程后、attachBaseContext 之前。
     *
     * @return 成功替换的 GOT 表项数量；-1 表示参数非法；0 表示 so 不可用或未命中
     */
    fun installIoRedirect(spaceRoot: String, virtualPackage: String): Int {
        if (!available) return 0
        return runCatching { nativeInit(spaceRoot, virtualPackage) }
            .onFailure { Log.e(TAG, "installIoRedirect 失败", it) }
            .getOrDefault(0)
    }

    /** 调试用：看一条路径会被翻译成什么 */
    fun redirectPath(path: String): String {
        if (!available) return path
        return runCatching { nativeRedirectPath(path) }.getOrDefault(path)
    }

    /** 重定向规则摘要 */
    fun summary(): String =
        if (!available) "native 库未加载" else runCatching { nativeSummary() }.getOrDefault("n/a")

    /** 查看某符号当前 GOT 指向，用于验证 hook 是否生效 */
    fun pltTarget(lib: String, symbol: String): String =
        if (!available) "" else runCatching { nativePltTarget(lib, symbol) }.getOrDefault("")

    /** 手动 hook 一个符号（高级用法，供插件/调试） */
    fun hookSymbol(lib: String, symbol: String, replacement: Long): Int =
        if (!available) -1 else runCatching { nativeHookSymbol(lib, symbol, replacement) }.getOrDefault(-1)

    // ---------------------------------------------------------------- JNI

    private external fun nativeInit(spaceRoot: String, virtualPackage: String): Int
    private external fun nativeRedirectPath(path: String): String
    private external fun nativeSummary(): String
    private external fun nativeVersion(): String
    private external fun nativePltTarget(lib: String, symbol: String): String
    private external fun nativeHookSymbol(lib: String, symbol: String, replacement: Long): Int
}
