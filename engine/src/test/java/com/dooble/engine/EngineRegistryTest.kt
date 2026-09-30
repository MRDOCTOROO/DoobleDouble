package com.dooble.engine

import android.app.Application
import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎注册表（[EngineRegistry]）的契约测试。
 *
 * 用临时注册表实例（不触碰全局 [EngineRegistry.instance]）验证：
 *  - 按注册顺序挑选第一个可用引擎；
 *  - 不可用工厂被跳过；
 *  - 全部不可用 / 未注册 id 返回 null。
 */
class EngineRegistryTest {

    private class FakeEngine(override val name: String = "fake") : VirtualEngine {
        override val capability = EngineCapability(
            supportsGms = false,
            supportsSignatureSpoof = false,
            supportsXposed = false,
            supportsMultiInstance = false,
            nativeHook = false,
            supports64Bit = false,
        )

        override suspend fun initialize(context: Context) = Unit
        override fun isReady(): Boolean = true

        override suspend fun createSpace(config: SpaceConfig): SpaceRuntime =
            error("测试引擎不创建空间")

        override suspend fun deleteSpace(spaceId: String) = Unit
        override fun listSpaces(): List<SpaceRuntime> = emptyList()
        override fun space(spaceId: String): SpaceRuntime? = null

        override suspend fun installApk(spaceId: String, apk: File, system: Boolean): VirtualApp =
            error("测试引擎不安装 APK")

        override suspend fun uninstall(spaceId: String, packageName: String) = Unit
        override fun listApps(spaceId: String): List<VirtualApp> = emptyList()

        override suspend fun launch(spaceId: String, packageName: String): LaunchResult =
            LaunchResult.fail("测试引擎不启动应用")

        override suspend fun stop(spaceId: String, packageName: String) = Unit
        override fun runningProcesses(): List<RunningProcess> = emptyList()
    }

    private class FakeFactory(
        override val id: String,
        override val available: Boolean,
        private val engine: VirtualEngine? = FakeEngine(id),
    ) : VirtualEngineFactory {
        override fun create(context: Context): VirtualEngine? = engine
    }

    private val context: Context = Application()

    @Test
    fun `createFirstAvailable 返回第一个可用工厂的引擎`() {
        val registry = EngineRegistry()
        registry.register(FakeFactory("unavailable-1", available = false))
        registry.register(FakeFactory("good", available = true))

        val engine = registry.createFirstAvailable(context)

        assertNotNull(engine)
        assertEquals("good", engine?.name)
    }

    @Test
    fun `可用工厂中排在前面者优先`() {
        val registry = EngineRegistry()
        registry.register(FakeFactory("first", available = true))
        registry.register(FakeFactory("second", available = true))

        assertEquals("first", registry.createFirstAvailable(context)?.name)
    }

    @Test
    fun `全部不可用时返回 null`() {
        val registry = EngineRegistry()
        registry.register(FakeFactory("a", available = false))
        registry.register(FakeFactory("b", available = false))

        assertNull(registry.createFirstAvailable(context))
    }

    @Test
    fun `未注册的 id 不可用且不能创建`() {
        val registry = EngineRegistry()

        assertFalse(registry.isAvailable("nope"))
        assertNull(registry.create("nope", context))
    }

    @Test
    fun `按 id 创建可用引擎`() {
        val registry = EngineRegistry()
        registry.register(FakeFactory("x", available = true))

        assertTrue(registry.isAvailable("x"))
        assertEquals("x", registry.create("x", context)?.name)
    }

    @Test
    fun `按 id 创建不可用引擎返回 null`() {
        val registry = EngineRegistry()
        registry.register(FakeFactory("off", available = false))

        assertFalse(registry.isAvailable("off"))
        assertNull(registry.create("off", context))
    }

    @Test
    fun `availableFactories 只包含就绪工厂且保持注册顺序`() {
        val registry = EngineRegistry()
        registry.register(FakeFactory("off", available = false))
        registry.register(FakeFactory("on-1", available = true))
        registry.register(FakeFactory("on-2", available = true))

        assertEquals(listOf("on-1", "on-2"), registry.availableFactories().map { it.id })
    }

    @Test
    fun `重复注册同 id 覆盖旧实现`() {
        val registry = EngineRegistry()
        registry.register(FakeFactory("same", available = true))
        registry.register(FakeFactory("same", available = true, engine = FakeEngine("replacement")))

        assertEquals("replacement", registry.create("same", context)?.name)
    }
}