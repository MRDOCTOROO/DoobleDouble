package com.dooble.engine.blackbox

import android.content.Context
import com.dooble.engine.VirtualEngine
import com.dooble.engine.VirtualEngineFactory

/**
 * Bcore 引擎的工厂 SPI 实现。
 *
 * ## 为什么这一层必须存在
 *
 * [com.dooble.engine.bcore.BcoreEngine] 直接引用 Bcore 的类型，只有 `libs/bcore.aar`
 * 存在时才会被编译。主源集（本文件所在的这一层）**不能**直接 new 它，否则在没有
 * AAR 的环境下会编译失败。
 *
 * 解法：主源集只反射查找**我们自己**的类名。这里不存在"API 漂移"风险——类名和
 * `create()` 方法都是本工程自己的代码，改名必然同步修改，不会像反射第三方库那样
 * 悄悄失效。真正的 Bcore API 调用全部由编译器在 [com.dooble.engine.bcore.BcoreEngine]
 * 内部校验。
 */
object BcoreEngineFactory : VirtualEngineFactory {

    private const val IMPL_CLASS = "com.dooble.engine.bcore.BcoreEngine"

    override val id: String = "bcore"

    /** AAR 是否已接入。UI 用它区分「引擎缺失」和「引擎初始化失败」 */
    override val available: Boolean
        get() = runCatching { Class.forName(IMPL_CLASS) }.isSuccess

    /**
     * 构造 Bcore 引擎；AAR 缺失或构造失败时返回 null，由注册表继续尝试下一个候选。
     *
     * 构造本身很轻（只是 `BlackBoxCore.get()`），可以在
     * `Application.attachBaseContext` 里同步调用。`context` 参数对本引擎无意义，
     * 保留它只是为了满足 [VirtualEngineFactory] 契约。
     */
    override fun create(context: Context): VirtualEngine? = runCatching {
        val cls = Class.forName(IMPL_CLASS)
        cls.getMethod("create").invoke(null) as? VirtualEngine
    }.getOrNull()
}
