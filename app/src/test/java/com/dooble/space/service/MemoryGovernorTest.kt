package com.dooble.space.service

import android.content.ComponentCallbacks2
import com.dooble.engine.RunningProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回收决策的单元测试。
 *
 * [MemoryGovernor] 被刻意设计成纯函数，就是为了能在这里穷举组合——
 * 「杀错进程」在真机上极难复现（要靠系统在恰好的时刻派发恰好档位的回调），
 * 但在表驱动测试里一目了然。
 */
class MemoryGovernorTest {

    private fun proc(space: String, pkg: String, pid: Int) =
        RunningProcess(spaceId = space, packageName = pkg, pid = pid, memoryKb = 0L)

    private fun lru(vararg pairs: Pair<String, String>) =
        pairs.mapIndexed { index, (space, pkg) ->
            WarmPool.Entry(space, pkg, ts = 1_000L - index)
        }

    private val s1 = "sp_one"
    private val s2 = "sp_two"

    @Test
    fun `切到后台时按 LRU 保留最近两个`() {
        val running = listOf(
            proc(s1, "com.a", 1),
            proc(s1, "com.b", 2),
            proc(s1, "com.c", 3),
        )
        // c 最近用过，其次是 b，a 最久
        val plan = MemoryGovernor.plan(
            trimLevel = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            running = running,
            lru = lru(s1 to "com.c", s1 to "com.b", s1 to "com.a"),
        )

        assertEquals(listOf("com.c", "com.b"), plan.keep.map { it.packageName })
        assertEquals(listOf("com.a"), plan.evict.map { it.packageName })
        assertTrue(plan.changed)
    }

    @Test
    fun `被列入后台待回收时只留一个`() {
        val running = listOf(
            proc(s1, "com.a", 1),
            proc(s1, "com.b", 2),
            proc(s1, "com.c", 3),
        )
        val plan = MemoryGovernor.plan(
            trimLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            running = running,
            lru = lru(s1 to "com.c", s1 to "com.b", s1 to "com.a"),
        )

        assertEquals(listOf("com.c"), plan.keep.map { it.packageName })
        assertEquals(listOf("com.b", "com.a"), plan.evict.map { it.packageName })
    }

    @Test
    fun `钉住的空间永远不动 即使超出预算`() {
        val running = listOf(
            proc(s1, "com.a", 1),
            proc(s1, "com.b", 2),
            proc(s2, "com.pinned", 3),
        )
        // 预算 2，钉住的进程已经占掉 1 个名额，剩下 1 个给 s1
        val plan = MemoryGovernor.plan(
            trimLevel = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            running = running,
            lru = lru(s1 to "com.a", s1 to "com.b"),
            pinnedSpaceId = s2,
        )

        assertTrue("钉住的进程必须被保留", plan.keep.any { it.packageName == "com.pinned" })
        assertEquals(listOf("com.a"), plan.keep.filter { it.spaceId == s1 }.map { it.packageName })
        assertEquals(listOf("com.b"), plan.evict.map { it.packageName })
    }

    @Test
    fun `钉住的空间占满预算时 其他空间被清空`() {
        val running = listOf(
            proc(s1, "com.a", 1),
            proc(s2, "com.p1", 2),
            proc(s2, "com.p2", 3),
        )
        // 预算 1，钉住的空间有 2 个进程 → 空闲名额为 0，s1 全清
        val plan = MemoryGovernor.plan(
            trimLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            running = running,
            lru = lru(s1 to "com.a"),
            pinnedSpaceId = s2,
        )

        assertEquals(2, plan.keep.size)
        assertTrue(plan.keep.all { it.spaceId == s2 })
        assertEquals(listOf("com.a"), plan.evict.map { it.packageName })
    }

    @Test
    fun `从没被打开过的进程优先被回收`() {
        val running = listOf(
            proc(s1, "com.never_used", 1),
            proc(s1, "com.used", 2),
        )
        val plan = MemoryGovernor.plan(
            trimLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            running = running,
            lru = lru(s1 to "com.used"),
        )

        assertEquals(listOf("com.used"), plan.keep.map { it.packageName })
        assertEquals(listOf("com.never_used"), plan.evict.map { it.packageName })
    }

    @Test
    fun `无关档位不动手`() {
        val running = listOf(proc(s1, "com.a", 1), proc(s1, "com.b", 2))
        // TRIM_MEMORY_RUNNING_MODERATE(5) 这类档位不该触发回收
        val plan = MemoryGovernor.plan(
            trimLevel = 5,
            running = running,
            lru = lru(s1 to "com.a"),
        )

        assertFalse(plan.changed)
        assertEquals(2, plan.keep.size)
    }

    @Test
    fun `没有运行中的进程时返回空计划`() {
        val plan = MemoryGovernor.plan(
            trimLevel = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            running = emptyList(),
            lru = emptyList(),
        )

        assertFalse(plan.changed)
        assertTrue(plan.keep.isEmpty())
        assertTrue(plan.evict.isEmpty())
    }

    @Test
    fun `手动档位等价于从容瘦身`() {
        val running = listOf(
            proc(s1, "com.a", 1),
            proc(s1, "com.b", 2),
            proc(s1, "com.c", 3),
        )
        val plan = MemoryGovernor.plan(
            trimLevel = MemoryGovernor.LEVEL_MANUAL,
            running = running,
            lru = lru(s1 to "com.c", s1 to "com.b", s1 to "com.a"),
        )

        assertEquals(2, plan.keep.size)
        assertEquals(listOf("com.a"), plan.evict.map { it.packageName })
    }
}
