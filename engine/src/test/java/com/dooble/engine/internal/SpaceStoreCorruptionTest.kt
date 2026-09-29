package com.dooble.engine.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「spaces.json 损坏时的行为」与「userId 高水位」的回归测试。
 *
 * ## 这里曾经有一条完整的数据丢失链
 *
 * 解析失败时旧实现 `getOrElse { linkedMapOf() }` —— 坏文件被当作"没有空间"，
 * 随后任意一次 put() 把空表**全量写回** `spaces.json`，旧内容永久丢失。
 * 连着 bcoreUserId 映射一起丢：allocateUserId 只查内存注册表，新空间会分到
 * 已占用的 userId，直接继承上一个空间的应用与账号。
 *
 * 现在的契约：
 *  1. 解析失败 → 坏文件改名 `spaces.json.corrupt-<ts>` 留档（人能手工抢救）；
 *  2. `user-id-floor` 高水位文件跨删除单调递增——哪怕注册表从零重建，
 *     也不会把发出去过的 userId 发给新空间。
 */
class SpaceStoreCorruptionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun configOf(id: String) = com.dooble.engine.SpaceConfig(id = id, name = id)

    // ------------------------------------------------------------ 损坏留档

    @Test
    fun `损坏的 spaces_json 被改名留档而不是当作空表`() {
        val root = tmp.newFolder("meta")
        File(root, "spaces.json").writeText("{ 这不是 JSON")

        val store = SpaceStore(root)

        assertTrue("解析失败应得到空表", store.all().isEmpty())
        val archives = root.listFiles { f -> f.name.startsWith("spaces.json.corrupt-") }.orEmpty()
        assertEquals("坏文件必须留档一份", 1, archives.size)
        assertTrue("留档内容应与原文件一致", archives.first().readText().contains("这不是 JSON"))
        assertFalse("原文件不应还在原位（避免下次再被覆盖写）", File(root, "spaces.json").exists())
    }

    @Test
    fun `留档后继续使用不会抹掉现场`() {
        val root = tmp.newFolder("meta2")
        File(root, "spaces.json").writeText("] broken [")

        val store = SpaceStore(root)
        store.put(SpaceStore.Record(config = configOf("sp_a")))

        // 新 spaces.json 已写出，但坏文件的留档必须还在——那是唯一能手工
        // 找回旧空间列表的地方
        assertTrue(File(root, "spaces.json").exists())
        assertEquals(1, root.listFiles { f -> f.name.startsWith("spaces.json.corrupt-") }!!.size)
    }

    @Test
    fun `合法文件正常读写不受影响`() {
        val root = tmp.newFolder("meta3")

        val first = SpaceStore(root)
        first.put(SpaceStore.Record(config = configOf("sp_ok"), bcoreUserId = 3))

        val second = SpaceStore(root)
        assertEquals(1, second.all().size)
        assertEquals(3, second.get("sp_ok")?.bcoreUserId)
    }

    // ------------------------------------------------------------ userId 高水位

    @Test
    fun `高水位跨实例持久化且只升不降`() {
        val root = tmp.newFolder("meta4")

        SpaceStore(root).markUserIdUsed(5)
        assertEquals(5, SpaceStore(root).userIdFloor())

        // 旧值不许把水位拉回去
        SpaceStore(root).markUserIdUsed(3)
        assertEquals(5, SpaceStore(root).userIdFloor())
    }

    @Test
    fun `没有高水位记录时返回 0`() {
        assertEquals(0, SpaceStore(tmp.newFolder("meta5")).userIdFloor())
    }
}
