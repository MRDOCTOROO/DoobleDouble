package com.dooble.space.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「最近使用」顺序表的顺序语义与存盘格式。
 *
 * ## 为什么值得测
 *
 * 这张表是内存回收的**唯一依据**。它错了不会报错，只会让回收器挑错牺牲品：
 *
 * - **顺序反了** → 回收器先杀用户刚打开的应用，留下半小时没碰过的
 * - **读回来是空的** → 回收器认为"所有应用都没被打开过"，随便挑一个杀
 * - **去重漏了** → 同一个应用在表里占好几条，把别的应用挤出 32 条上限
 *
 * 三种都不会崩，但都会让"保活"这件事失去意义。所以顺序和格式必须有单测。
 */
class WarmOrderTest {

    private val s1 = "sp_one"
    private val s2 = "sp_two"

    private fun e(space: String, pkg: String, ts: Long) = WarmPool.Entry(space, pkg, ts)

    // ------------------------------------------------------------- 顺序语义

    @Test
    fun `touch 把目标顶到队首`() {
        val current = listOf(e(s1, "a", 1), e(s1, "b", 2), e(s1, "c", 3))

        val result = WarmOrder.touch(current, s1, "c", now = 100)

        assertEquals("c", result.first().packageName)
        assertEquals(100L, result.first().ts)
        assertEquals(listOf("c", "a", "b"), result.map { it.packageName })
    }

    @Test
    fun `重复 touch 同一个应用只留一条`() {
        var list = emptyList<WarmPool.Entry>()
        repeat(5) { i -> list = WarmOrder.touch(list, s1, "a", now = i.toLong()) }

        // 不去重的话这里会是 5 条，"a"一个人占掉 5 个名额
        assertEquals(1, list.size)
        assertEquals(4L, list.first().ts)
    }

    @Test
    fun `同名应用在不同空间互不干扰`() {
        var list = emptyList<WarmPool.Entry>()
        list = WarmOrder.touch(list, s1, "com.x", now = 1)
        list = WarmOrder.touch(list, s2, "com.x", now = 2)

        // (spaceId, packageName) 才是主键；只看包名会让两个空间互相顶掉
        assertEquals(2, list.size)
        assertEquals(s2, list.first().spaceId)
    }

    @Test
    fun `超过上限时丢弃队尾`() {
        var list = emptyList<WarmPool.Entry>()
        repeat(WarmOrder.MAX_ENTRIES + 10) { i ->
            list = WarmOrder.touch(list, s1, "pkg.$i", now = i.toLong())
        }

        assertEquals(WarmOrder.MAX_ENTRIES, list.size)
        // 最新的是最后一个写入的，最旧的十个已经被挤出去
        assertEquals("pkg.${WarmOrder.MAX_ENTRIES + 9}", list.first().packageName)
        assertTrue("最旧的必须已被挤掉", list.none { it.packageName == "pkg.0" })
    }

    @Test
    fun `空白参数不改变现有顺序`() {
        val current = listOf(e(s1, "a", 1))

        assertEquals(current, WarmOrder.touch(current, "", "a", 2))
        assertEquals(current, WarmOrder.touch(current, s1, "", 2))
        assertEquals(current, WarmOrder.touch(current, "  ", "a", 2))
    }

    @Test
    fun `forget 只删精确匹配的那一条`() {
        val current = listOf(e(s1, "a", 1), e(s2, "a", 2), e(s1, "b", 3))

        val result = WarmOrder.forget(current, s1, "a")

        // s2 下的同名应用必须留着——不然删一个空间的应用会波及另一个空间
        assertEquals(listOf(e(s2, "a", 2), e(s1, "b", 3)), result)
    }

    @Test
    fun `forgetSpace 清掉该空间的全部条目`() {
        val current = listOf(e(s1, "a", 1), e(s2, "b", 2), e(s1, "c", 3))

        val result = WarmOrder.forgetSpace(current, s1)

        assertEquals(listOf(e(s2, "b", 2)), result)
    }

    // ------------------------------------------------------------- 编解码

    @Test
    fun `编解码往返一致`() {
        val entries = listOf(e(s1, "com.a", 1), e(s2, "com.b", 2), e(s1, "com.c", 3))

        assertEquals(entries, WarmOrder.decode(WarmOrder.encode(entries)))
    }

    @Test
    fun `编码格式稳定`() {
        // 这是落盘格式的契约：改了它，用户升级后原有的顺序表会全部读不出来
        val encoded = WarmOrder.encode(listOf(e("sp", "com.a", 42)))

        assertEquals("sp\u0001com.a\u000142", encoded)
    }

    @Test
    fun `空输入解码为空表`() {
        assertTrue(WarmOrder.decode(null).isEmpty())
        assertTrue(WarmOrder.decode("").isEmpty())
        assertTrue(WarmOrder.decode("   ").isEmpty())
    }

    @Test
    fun `坏行只丢自己 不影响其他行`() {
        val good1 = WarmOrder.encode(listOf(e(s1, "a", 1)))
        val good2 = WarmOrder.encode(listOf(e(s1, "b", 2)))

        val raw = listOf(
            good1,
            "只有两段\u0001缺一段",        // 段数不对
            "sp\u0001pkg\u0001不是数字",    // ts 解析失败
            "",                             // 空行
            good2,
        ).joinToString(WarmOrder.ROW.toString())

        val result = WarmOrder.decode(raw)

        assertEquals("坏行必须只丢自己", 2, result.size)
        assertEquals(listOf("a", "b"), result.map { it.packageName })
    }

    @Test
    fun `空白的空间 id 或包名被丢弃`() {
        // 这种条目永远不会被命中，留着只是白占名额
        val raw = listOf(
            "\u0001pkg\u000110",
            "sp\u0001\u000110",
            "sp\u0001pkg\u000110",
        ).joinToString(WarmOrder.ROW.toString())

        val result = WarmOrder.decode(raw)

        assertEquals(1, result.size)
        assertEquals("pkg", result.first().packageName)
    }

    @Test
    fun `段数多出来的行被拒绝`() {
        // 分隔符撞车会产生多余段，这时宁可丢这一行也不要拼出一个错的包名
        val raw = "sp\u0001com.a\u0001extra\u000110"

        assertTrue(WarmOrder.decode(raw).isEmpty())
    }

    @Test
    fun `编解码可以连续多轮不漂移`() {
        // 初始两条 + 新 touch 的 com.c = 三条，反复 20 轮后条数必须还是三条：
        // 去重失效会让它每轮涨一条，编码不幂等会让它越读越少
        var list = listOf(e(s1, "com.a", 1), e(s2, "com.b", 2))
        repeat(20) {
            list = WarmOrder.touch(list, s1, "com.c", now = 100L + it)
            list = WarmOrder.decode(WarmOrder.encode(list))
        }

        assertEquals("com.c", list.first().packageName)
        assertEquals(3, list.size)
        assertEquals(119L, list.first().ts)
    }
}
