package com.dooble.space.system

import com.dooble.space.system.VirtualTaskResolver.VirtualTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「最近任务卡片属于哪个虚拟应用」反查规则的测试。
 *
 * ## 为什么值得测
 *
 * 这里判错的代价是**用户直接看到功能坏了**，而且两个方向的错法都很糟：
 *  - 认不出容器建的任务 → 宿主自己的任务卡片被误收（用户找不到主界面）；
 *  - 认错空间 → 收掉另一个空间里还在运行的同名应用卡片，那个应用再也点不开。
 *
 * 判定本身依赖 stub intent 的 extra，而 `Intent` 在 JVM 单测里是 `Stub!` 桩，
 * 所以规则被抽成 [VirtualTaskResolver] 的纯函数。
 */
class VirtualTaskResolverTest {

    private val pkg = "com.tencent.mm"
    private val spaceA = 10001
    private val spaceB = 10002

    // ───────────────────────── resolve：认不认得出虚拟任务 ─────────────────────────

    @Test
    fun `优先用虚拟 ActivityInfo 的包名`() {
        val t = VirtualTaskResolver.resolve(pkg, "com.other", spaceA)
        assertEquals(pkg, t?.packageName)
    }

    @Test
    fun `ActivityInfo 缺失时回退到原始 intent 的 component`() {
        val t = VirtualTaskResolver.resolve(null, pkg, spaceA)
        assertEquals(pkg, t?.packageName)
    }

    @Test
    fun `ActivityInfo 为空串时也回退`() {
        // Bcore 早期版本或某些 ROM 会写空串，不能把它当成有效包名。
        val t = VirtualTaskResolver.resolve("", pkg, spaceA)
        assertEquals(pkg, t?.packageName)
    }

    @Test
    fun `两个来源都没有时返回 null 表示不是容器任务`() {
        // 宿主自己的 MainActivity 任务就走这条路，必须原样放过。
        assertNull(VirtualTaskResolver.resolve(null, null, null))
        assertNull(VirtualTaskResolver.resolve("", "", null))
    }

    @Test
    fun `userId 缺失时保留为 null 而不是 0`() {
        // 这是刻意的：0 是一个合法的"未知/未分配"哨兵值，与"没有这个 extra"语义不同。
        // 混同会导致 matches 里把"未知"当成"就是 0 号空间"，从而漏清卡片。
        val t = VirtualTaskResolver.resolve(pkg, null, userId = null)
        assertNull(t?.userId)
        assertEquals(spaceA, VirtualTaskResolver.resolve(pkg, null, spaceA)?.userId)
    }

    @Test
    fun `userId 为 0 是有效值并被原样保留`() {
        assertEquals(0, VirtualTaskResolver.resolve(pkg, null, 0)?.userId)
    }

    // ───────────────────────── matches：归属判定 ─────────────────────────

    @Test
    fun `包名不同一律不匹配`() {
        assertFalse(
            VirtualTaskResolver.matches(VirtualTask("com.a", spaceA), "com.b", spaceA)
        )
        // 包名不同时，就算 userId 也是 null 也不能匹配。
        assertFalse(VirtualTaskResolver.matches(VirtualTask("com.a", null), "com.b", null))
    }

    @Test
    fun `同包名同空间匹配`() {
        assertTrue(VirtualTaskResolver.matches(VirtualTask(pkg, spaceA), pkg, spaceA))
    }

    @Test
    fun `同包名不同空间不匹配`() {
        // 这条是整个 userId 过滤存在的理由：空间 A 里"结束运行"不该收掉空间 B 的卡片。
        assertFalse(VirtualTaskResolver.matches(VirtualTask(pkg, spaceB), pkg, spaceA))
    }

    @Test
    fun `调用方不指定空间时同包名即命中`() {
        assertTrue(VirtualTaskResolver.matches(VirtualTask(pkg, spaceA), pkg, userId = null))
    }

    @Test
    fun `卡片缺 userId 时同包名即命中`() {
        // 信息不全时宁可多清：漏清的后果是"点了结束运行卡片还在"，用户会认定功能坏了；
        // 多清的后果只是少一张卡片，重开应用即可恢复。
        assertTrue(VirtualTaskResolver.matches(VirtualTask(pkg, null), pkg, spaceA))
    }

    @Test
    fun `两边都没有 userId 时同包名即命中`() {
        assertTrue(VirtualTaskResolver.matches(VirtualTask(pkg, null), pkg, null))
    }

    // ───────────────────────── filterUserId：换算成过滤参数 ─────────────────────────

    @Test
    fun `已分配的空间返回真实 userId`() {
        assertEquals(spaceA, VirtualTaskResolver.filterUserId(spaceA))
        assertEquals(1, VirtualTaskResolver.filterUserId(1))
    }

    @Test
    fun `未分配的空间返回 null 而不是 0`() {
        // 传 0 会变成"只匹配 userId 为 0 的卡片"，而真实卡片是 10000 起的，
        // 结果是**一张都清不掉** —— 把安全兜底反向变成全漏。这条钉的就是这个坑。
        assertNull(VirtualTaskResolver.filterUserId(0))
        assertNull(VirtualTaskResolver.filterUserId(-1))
    }

    @Test
    fun `未分配空间时同包名的卡片仍然会被清掉`() {
        // 端到端串一遍：空间还没分配 userId（刚建好），
        // filterUserId → null → matches 不按空间过滤 → 卡片照样收掉。
        val filter = VirtualTaskResolver.filterUserId(0)
        assertTrue(VirtualTaskResolver.matches(VirtualTask(pkg, spaceA), pkg, filter))
    }

    // ───────────────────────── 协议字面量 ─────────────────────────

    @Test
    fun `协议常量必须与 Bcore 的 saveStub 逐字一致`() {
        // 这三个字面量是容器与宿主之间的协议，改任意一边都要同步另一边。
        // 写死在这里是为了：谁改了就立刻红，而不是等到设备上发现"卡片收不掉"。
        assertEquals("_B_|_activity_info_", VirtualTaskResolver.EXTRA_ACTIVITY_INFO)
        assertEquals("_B_|_target_", VirtualTaskResolver.EXTRA_TARGET)
        assertEquals("_B_|_user_id_", VirtualTaskResolver.EXTRA_USER_ID)
    }

    @Test
    fun `三个协议常量互不相同`() {
        // 复制粘贴改漏一个字母（比如两个都写成 _target_）会导致读不到值且静默失败。
        val keys = setOf(
            VirtualTaskResolver.EXTRA_ACTIVITY_INFO,
            VirtualTaskResolver.EXTRA_TARGET,
            VirtualTaskResolver.EXTRA_USER_ID,
        )
        assertEquals(3, keys.size)
    }
}
