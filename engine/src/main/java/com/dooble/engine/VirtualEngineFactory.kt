package com.dooble.engine

import android.content.Context

/**
 * 虚拟化引擎的**工厂 SPI**（Service Provider Interface）。
 *
 * ## 为什么需要这一层
 *
 * 早期代码在 `DoobleContainer` 里硬编码
 * `BcoreEngineFactory.createOrNull() ?: SandboxEngine(app)`：
 * 要新增一个内核（比如未来出现许可证清晰、活跃维护的 VirtualApp 分支），
 * 就必须改依赖容器本身。SPI 化之后，引擎的**发现与实例化**统一走
 * [EngineRegistry]：
 *
 * ```
 * val engine = EngineRegistry.instance.createFirstAvailable(context)
 * ```
 *
 * 新增内核 = 新增一个实现本接口的工厂 + 注册进 [EngineRegistry]，
 * 上层（UI / GMS 供给层 / 依赖容器）零改动。
 */
interface VirtualEngineFactory {

    /** 引擎标识，必须与 [VirtualEngine.name] 一致，例如 "bcore" / "sandbox" */
    val id: String

    /**
     * 实现是否**就绪**（AAR 是否接入、native 是否可用等）。
     *
     * UI 用它区分「引擎缺失」（available=false）与「引擎初始化失败」
     * （available=true 但 [create] 返回 null / initialize 抛异常）。
     */
    val available: Boolean

    /**
     * 构造引擎实例；构造失败返回 null，由注册表继续尝试下一个候选。
     *
     * @param context 应用上下文。Bcore 这类纯反射引擎可以忽略它；
     *                降级沙箱引擎需要它来定位 `filesDir` 等路径。
     */
    fun create(context: Context): VirtualEngine?
}