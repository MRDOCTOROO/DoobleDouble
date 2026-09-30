package com.dooble.engine.internal

import android.content.Context
import com.dooble.engine.VirtualEngine
import com.dooble.engine.VirtualEngineFactory

/**
 * 降级沙箱引擎 [SandboxEngine] 的工厂。
 *
 * 永远可用——它是「诚实降级」的最后兜底：即使没有任何虚拟化内核接入，
 * 也要保证整套 UI 与 GMS 供给层能跑通。
 */
object SandboxEngineFactory : VirtualEngineFactory {

    override val id: String = SandboxEngine.NAME

    override val available: Boolean = true

    override fun create(context: Context): VirtualEngine? = runCatching {
        SandboxEngine(context)
    }.getOrNull()
}