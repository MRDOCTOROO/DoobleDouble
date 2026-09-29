package com.dooble.engine.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 磁盘占用统计的回归测试。
 *
 * ## 这里曾经错得很安静
 *
 * `diskUsage` 走的是 `root/<spaceId>`，看着合理（BcoreEngine 确实会创建这个
 * 目录），但空间数据根本不在那儿。后果是返回值**恒为 0**。
 *
 * `0` 是个完全"合法"的数字——不抛异常、不打日志、UI 上「磁盘占用」那一栏就是
 * 安安静静显示 0。空间里装了 8 个应用、223MB，界面照旧写 0，看起来只是
 * "还没统计到"，很容易被当成正常现象放过去。
 *
 * 这类**静默返回默认值**的错误靠人工点界面发现不了（没有异常信号），
 * 只能靠把真实路径和真实数字钉死的断言来守。所以下面不仅测"能求和"，
 * 更测**路径本身拼得对不对**——那才是当初出错的地方。
 */
class SpaceStoreDiskUsageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeFile(dir: File, name: String, size: Int) {
        dir.mkdirs()
        File(dir, name).writeBytes(ByteArray(size))
    }

    // ------------------------------------------------------------ SpaceStore

    @Test
    fun `递归累加多个目录下的全部文件`() {
        val a = tmp.newFolder("a")
        val b = tmp.newFolder("b")
        writeFile(a, "base.apk", 1000)
        writeFile(File(a, "lib"), "libnative.so", 500)
        writeFile(b, "data.db", 250)

        val store = SpaceStore(tmp.newFolder("meta"))

        assertEquals(1750L, store.diskUsage(a, b))
    }

    @Test
    fun `目录不存在时返回 0`() {
        val store = SpaceStore(tmp.newFolder("meta2"))
        assertEquals(0L, store.diskUsage(File(tmp.root, "nope")))
    }

    @Test
    fun `不传目录时返回 0`() {
        val store = SpaceStore(tmp.newFolder("meta3"))
        assertEquals(0L, store.diskUsage())
    }

    // -------------------------------------------------- BcoreStorageLayout

    @Test
    fun `Bcore 内部虚拟根是 dataDir 下的 blackbox`() {
        val dataDir = File("/data/data/com.example")
        assertEquals(
            File("/data/data/com.example/blackbox"),
            BcoreStorageLayout.internalRoot(dataDir),
        )
    }

    @Test
    fun `APK 目录按包名组织且不带 userId`() {
        val dataDir = File("/data/data/com.example")
        assertEquals(
            File("/data/data/com.example/blackbox/data/app/org.schabi.newpipe"),
            BcoreStorageLayout.appDir(dataDir, "org.schabi.newpipe"),
        )
    }

    @Test
    fun `应用数据目录按 userId 组织`() {
        val dataDir = File("/data/data/com.example")
        assertEquals(
            File("/data/data/com.example/blackbox/data/user/3"),
            BcoreStorageLayout.userDir(dataDir, 3),
        )
        assertEquals(
            File("/data/data/com.example/blackbox/data/user_de/3"),
            BcoreStorageLayout.userDeDir(dataDir, 3),
        )
    }

    @Test
    fun `外部存储不可用时 externalRoot 返回 null 而不是抛异常`() {
        assertEquals(null, BcoreStorageLayout.externalRoot(null))
        assertEquals(null, BcoreStorageLayout.externalUserDir(null, 1))
    }

    @Test
    fun `spaceDirs 覆盖 APK 与用户数据两类目录`() {
        val dataDir = File("/data/data/com.example")
        val external = File("/sdcard/Android/data/com.example/files")

        val dirs = BcoreStorageLayout.spaceDirs(
            dataDir = dataDir,
            externalFilesDir = external,
            userId = 2,
            packages = listOf("a.b", "c.d"),
        )

        assertTrue(dirs.contains(File("/data/data/com.example/blackbox/data/user/2")))
        assertTrue(dirs.contains(File("/data/data/com.example/blackbox/data/user_de/2")))
        assertTrue(dirs.contains(File("/data/data/com.example/blackbox/data/app/a.b")))
        assertTrue(dirs.contains(File("/data/data/com.example/blackbox/data/app/c.d")))
        assertTrue(dirs.contains(File("/sdcard/Android/data/com.example/files/blackbox/storage/emulated/2")))
    }

    /**
     * userId 0 是宿主保留值。若把它的数据算进某个空间，用户会看到一个
     * 自己没装过东西却占了几百 MB 的空间。
     */
    @Test
    fun `userId 为 0 时返回空列表`() {
        val dirs = BcoreStorageLayout.spaceDirs(
            dataDir = File("/data/data/com.example"),
            externalFilesDir = null,
            userId = 0,
            packages = listOf("a.b"),
        )
        assertTrue(dirs.isEmpty())
    }

    /**
     * 端到端：按 BcoreStorageLayout 给出的路径，真的能统计到文件。
     *
     * 这条是防"路径拼对了但拼到了别的地方"——上面几条断言的是字符串形状，
     * 这一条断言的是**同一个 File 对象能被走通**。
     */
    @Test
    fun `按 Bcore 布局落盘的文件能被统计到`() {
        val dataDir = tmp.newFolder("data")
        val packageName = "org.schabi.newpipe"
        val userId = 2

        // 按真实布局写入：APK 在 data/app/<包名>/，数据在 data/user/<userId>/
        writeFile(BcoreStorageLayout.appDir(dataDir, packageName), "base.apk", 4096)
        writeFile(
            File(BcoreStorageLayout.userDir(dataDir, userId), packageName),
            "prefs.xml",
            128,
        )

        val dirs = BcoreStorageLayout.spaceDirs(
            dataDir = dataDir,
            externalFilesDir = null,
            userId = userId,
            packages = listOf(packageName),
        )

        assertEquals(4224L, SpaceStore(tmp.newFolder("meta4")).diskUsage(*dirs.toTypedArray()))
    }
}
