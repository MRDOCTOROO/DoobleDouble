package com.dooble.engine

import android.content.Context
import com.dooble.engine.blackbox.BcoreEngineFactory
import com.dooble.engine.internal.SandboxEngineFactory

/**
 * 引擎**注册表**：按注册顺序保存所有 [VirtualEngineFactory]，并负责
 * 「按顺序探测、挑选第一个可用引擎」。
 *
 * ## 与 [EngineProvider] 的分工
 *
 * - [EngineProvider]：**运行时单例**（Service Locator），持有启动后选中的那个
 *   引擎实例，全局业务代码通过它访问当前引擎。
 * - [EngineRegistry]：**工厂注册表**，负责在启动时按顺序探测并实例化引擎。
 *
 * 启动流程：
 * ```
 * val engine = EngineRegistry.instance.createFirstAvailable(context)
 * EngineProvider.install(engine)
 * ```
 *
 * ## 内置引擎（默认注册顺序 = 优先级顺序）
 *
 * 1. [BcoreEngineFactory] —— 真正的虚拟化内核（`libs/bcore.aar` 接入才可用）；
 * 2. [SandboxEngineFactory] —— 永远可用的「诚实降级」沙箱。
 *
 * 未来接入新内核：
 * ```
 * EngineRegistry.instance.register(MyEngineFactory)
 * ```
 * 注册顺序靠前 = 优先被 [createFirstAvailable] 选中；也可以显式
 * [create] 指定 id 来「切换内核」。
 */
class EngineRegistry {

    private val factories = LinkedHashMap<String, VirtualEngineFactory>()

    /** 注册一个工厂；同 id 重复注册会覆盖旧实现 */
    fun register(factory: VirtualEngineFactory) {
        factories[factory.id] = factory
    }

    fun unregister(id: String) {
        factories.remove(id)
    }

    /** 全部已注册工厂（按注册顺序） */
    fun factories(): List<VirtualEngineFactory> = factories.values.toList()

    /** 已注册且 [VirtualEngineFactory.available] 为 true 的工厂 */
    fun availableFactories(): List<VirtualEngineFactory> = factories.values.filter { it.available }

    /** 某 id 是否已注册且可用 */
    fun isAvailable(id: String): Boolean = factories[id]?.available ?: false

    /** 按 id 创建；未注册或不可用时返回 null */
    fun create(id: String, context: Context): VirtualEngine? =
        factories[id]?.takeIf { it.available }?.create(context)

    /** 依注册顺序创建第一个可用引擎；全部失败返回 null */
    fun createFirstAvailable(context: Context): VirtualEngine? =
        availableFactories().firstNotNullOfOrNull { it.create(context) }

    companion object {
        /** 全局注册表：内置 Bcore + 沙箱两个引擎 */
        val instance: EngineRegistry by lazy {
            EngineRegistry().apply {
                register(BcoreEngineFactory)
                register(SandboxEngineFactory)
            }
        }
    }
}