package com.dooble.engine

/**
 * 引擎注册中心。宿主 Application 在启动时决定注入哪一个实现：
 *
 * ```
 * val engine = BlackBoxEngine.createOrNull(context) ?: SandboxEngine(context)
 * EngineProvider.install(engine)
 * engine.initialize(context)
 * ```
 */
object EngineProvider {

    @Volatile
    private var engine: VirtualEngine? = null

    fun install(engine: VirtualEngine) {
        this.engine = engine
    }

    fun get(): VirtualEngine = engine ?: error("VirtualEngine 尚未初始化，请先调用 EngineProvider.install()")

    fun peek(): VirtualEngine? = engine
}
