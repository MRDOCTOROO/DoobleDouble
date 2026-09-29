package com.dooble.engine.internal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `install-events.jsonl` 的跨进程格式契约。
 *
 * ## 为什么这个文件特别需要契约测试
 *
 * 这条链路的**写入方在虚拟应用的进程**（`ContainerInstallRelay`，跑在容器里），
 * **读取方在宿主主进程**（UI 刷新"刚刚装了什么"）。两个进程之间没有共享类型，
 * 唯一的约定就是"JSON 里字段叫什么名字"。
 *
 * 这种约定出错时**没有任何编译期或运行期信号**：写方把 `userId` 改成 `uid`，
 * 读方照旧读 `userId` —— 编译通过、单测通过、装完应用 UI 上就是看不到记录。
 * 项目 memory 里那句"字段名错开是静默失败"说的就是这里。
 *
 * 所以下面用**双向**断言把它钉死：
 * - 编码方向：断言写出的 JSON 里确实有这几个键
 * - 解码方向：拿一份**手写的** JSON 字面量（也就是文档里的契约）喂进去，
 *   断言解析结果正确
 *
 * 只做单向是不够的：只测编码，改字段名时两边一起改会"测试通过但契约已变"；
 * 只测解码，字段被删掉时可能因为默认值而静默通过。
 */
class InstallEventLogContractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newFile(name: String = "install-events.jsonl") =
        InstallEventFile(File(tmp.newFolder(), name))

    private fun event(
        ts: Long = 1_700_000_000_000L,
        userId: Int = 1,
        pkg: String = "org.schabi.newpipe",
        label: String = "NewPipe",
        ok: Boolean = true,
        msg: String = "",
    ) = InstallEvent(timestamp = ts, userId = userId, packageName = pkg, label = label, ok = ok, message = msg)

    // ------------------------------------------------------------- 契约本身

    @Test
    fun `编码出的 JSON 必须带这几个字段名`() {
        val json = InstallEventFile.ENCODE.encodeToString(
            InstallEvent.serializer(),
            event(),
        )

        // 这几个键是跨进程契约。改名会让主进程读不到值——而且不报错
        listOf("timestamp", "userId", "packageName", "label", "ok", "message").forEach { key ->
            assertTrue("契约字段 `$key` 丢失：$json", json.contains("\"$key\""))
        }
    }

    @Test
    fun `文档里的 JSON 契约能被解析`() {
        // 这份字面量就是契约本身，不要用序列化器生成——那会变成自证
        val line = """
            {"timestamp":1700000000000,"userId":7,"packageName":"com.example.app",
             "label":"示例","ok":false,"message":"解析失败"}
        """.trimIndent().replace("\n", "")

        val parsed = InstallEventFile.decode(line)

        assertNotNull("契约 JSON 必须能解析", parsed)
        assertEquals(1_700_000_000_000L, parsed!!.timestamp)
        assertEquals(7, parsed.userId)
        assertEquals("com.example.app", parsed.packageName)
        assertEquals("示例", parsed.label)
        assertEquals(false, parsed.ok)
        assertEquals("解析失败", parsed.message)
    }

    @Test
    fun `缺省字段能靠默认值补上`() {
        // 容器侧解析包名失败时只写 timestamp/userId/ok，其余靠默认值
        val parsed = InstallEventFile.decode("""{"timestamp":1,"userId":2,"ok":true}""")

        assertNotNull(parsed)
        assertEquals("", parsed!!.packageName)
        assertEquals("", parsed.label)
        assertEquals("", parsed.message)
    }

    @Test
    fun `多余字段不影响解析`() {
        // 老版本主进程读新版本容器写的日志，必须不炸
        val parsed = InstallEventFile.decode(
            """{"timestamp":1,"userId":2,"ok":true,"futureField":"whatever"}"""
        )
        assertNotNull(parsed)
        assertEquals(2, parsed!!.userId)
    }

    // ------------------------------------------------------------- 读写行为

    @Test
    fun `追加后能原样读回`() {
        val f = newFile()
        val a = event(ts = 1, pkg = "a.b", ok = true)
        val b = event(ts = 2, pkg = "c.d", ok = false, msg = "签名不匹配")

        f.append(a)
        f.append(b)

        assertEquals(listOf(a, b), f.peek())
    }

    @Test
    fun `drain 读后即清`() {
        val f = newFile()
        f.append(event(pkg = "a.b"))

        assertEquals(1, f.drain().size)
        // 第二次读必须是空的——否则 UI 每次刷新都会重复提示同一个安装
        assertTrue(f.drain().isEmpty())
    }

    @Test
    fun `peek 不清空`() {
        val f = newFile()
        f.append(event(pkg = "a.b"))

        assertEquals(1, f.peek().size)
        assertEquals(1, f.peek().size)
        assertEquals(1, f.drain().size)
    }

    @Test
    fun `文件不存在时返回空表而不是抛异常`() {
        val f = InstallEventFile(File(tmp.root, "never-written.jsonl"))

        assertTrue(f.peek().isEmpty())
        assertTrue(f.drain().isEmpty())
    }

    // ------------------------------------------------------------- 坏行容忍

    @Test
    fun `坏行被跳过 好行照常读出`() {
        val f = newFile()
        f.append(event(pkg = "good.one"))
        f.append(event(pkg = "good.two"))
        // 模拟进程被杀导致的半行写入，外加两种垃圾行
        f.path.appendText("""{"timestamp":123,"userId":""" + "\n")
        f.path.appendText("这不是 JSON\n")
        f.path.appendText("\n")   // 空行

        val events = f.drain()

        assertEquals("好行必须全部读出", 2, events.size)
        assertEquals("good.one", events[0].packageName)
        assertEquals("good.two", events[1].packageName)
    }

    @Test
    fun `单行解码对空串与非 JSON 返回 null`() {
        assertNull(InstallEventFile.decode(""))
        assertNull(InstallEventFile.decode("   "))
        assertNull(InstallEventFile.decode("not json at all"))
        assertNull(InstallEventFile.decode("""{"timestamp":"不是数字"}"""))
    }

    // ------------------------------------------------------------- 容量上限

    @Test
    fun `超过上限时只保留最近的若干条`() {
        val f = newFile()
        val total = InstallEventFile.MAX_KEEP + 25

        repeat(total) { i -> f.append(event(ts = i.toLong(), pkg = "pkg.$i")) }

        val events = f.peek()

        assertEquals("必须被截断到上限", InstallEventFile.MAX_KEEP, events.size)
        // 留下的是**最后**写入的那批，最旧的被丢掉
        assertEquals("pkg.25", events.first().packageName)
        assertEquals("pkg.${total - 1}", events.last().packageName)
    }

    @Test
    fun `刚好等于上限时不截断`() {
        val f = newFile()
        repeat(InstallEventFile.MAX_KEEP) { i -> f.append(event(ts = i.toLong(), pkg = "pkg.$i")) }

        assertEquals(InstallEventFile.MAX_KEEP, f.peek().size)
        assertEquals("pkg.0", f.peek().first().packageName)
    }

    // ------------------------------------------------------------- 展示格式

    @Test
    fun `timeText 形如 月-日 时-分`() {
        val text = event(ts = 1_700_000_000_000L).timeText
        assertTrue("实际=$text", Regex("""\d{2}-\d{2} \d{2}:\d{2}""").matches(text))
    }

    @Test
    fun `空间 id 不写进日志 由读方按 userId 反查`() {
        val json = InstallEventFile.ENCODE.encodeToString(InstallEvent.serializer(), event())
        // 冗余存 spaceId 会出现"日志里的空间"和"引擎里的空间"两份真相
        assertTrue("日志里不该出现 spaceId", !json.contains("spaceId"))
        assertTrue("必须存 userId 供读方反查", json.contains("\"userId\""))
    }

    @Test
    fun `单例未初始化时所有操作都是安全的空操作`() {
        // init 之前容器进程可能已经收到安装请求，不能崩
        assertTrue(InstallEventLog.peek().isEmpty())
        assertTrue(InstallEventLog.drain().isEmpty())
        InstallEventLog.record(event())   // 不应抛异常
    }

    @Test
    fun `Json 配置必须容忍未知字段`() {
        // 这条守的是"老读方 + 新写方"的兼容性
        assertTrue(InstallEventFile.ENCODE.configuration.ignoreUnknownKeys)
    }

    @Test
    fun `写入的确实是 JSONL 一行一条`() {
        val f = newFile()
        f.append(event(pkg = "a.b"))
        f.append(event(pkg = "c.d"))

        val lines = f.path.readLines().filter { it.isNotBlank() }

        assertEquals(2, lines.size)
        lines.forEach { line ->
            // 每行必须是能独立解析的完整 JSON——追加写入时靠 \n 分隔
            val obj = Json.parseToJsonElement(line)
            assertTrue(obj is kotlinx.serialization.json.JsonObject)
        }
    }
}
