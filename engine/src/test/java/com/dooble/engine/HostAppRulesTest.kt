package com.dooble.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主应用扫描规则的测试。
 *
 * 这些规则原本埋在 [HostAppScanner] 里，而那个类要 `PackageManager`
 * —— JVM 单测里是 `Stub!` 桩。抽成 [HostAppRules] 后规则可测，
 * 而且"抽出来"这件事本身也强制了规则与 Android 框架解耦。
 */
class HostAppRulesTest {

    private fun app(
        pkg: String,
        label: String = pkg,
        isSystem: Boolean = false,
        usesGoogle: Boolean = false,
        splits: List<String> = emptyList(),
    ) = HostAppInfo(
        packageName = pkg,
        label = label,
        versionName = "1.0",
        versionCode = 1L,
        apkPath = "/data/app/$pkg/base.apk",
        splitApks = splits,
        isSystem = isSystem,
        usesGoogleApis = usesGoogle,
        sizeBytes = 1024L,
        installedAt = 0L,
    )

    // ───────────────────── 谷歌依赖判定 ─────────────────────

    @Test
    fun `声明 gms 系列权限判定为依赖谷歌`() {
        assertTrue(HostAppRules.usesGoogleApis(listOf("com.google.android.gms.permission.AD_ID")))
        assertTrue(HostAppRules.usesGoogleApis(listOf("com.google.android.gms")))
    }

    @Test
    fun `声明 C2DM 推送权限判定为依赖谷歌`() {
        // GCM/FCM 的推送权限，是"这应用要连谷歌云"最典型的信号。
        assertTrue(
            HostAppRules.usesGoogleApis(listOf("com.google.android.c2dm.permission.RECEIVE"))
        )
    }

    @Test
    fun `声明 vending 权限判定为依赖谷歌`() {
        // 应用内购买走 Play 结算，需要 vending。
        assertTrue(HostAppRules.usesGoogleApis(listOf("com.android.vending.BILLING")))
    }

    @Test
    fun `名字里含 gms 的第三方权限不算依赖谷歌`() {
        // 用的是 startsWith 不是 contains。若哪天改成 contains，这条会红。
        assertFalse(HostAppRules.usesGoogleApis(listOf("com.example.gms.thing")))
        assertFalse(HostAppRules.usesGoogleApis(listOf("com.mycompany.google.android.gms")))
    }

    @Test
    fun `普通权限列表不算依赖谷歌`() {
        assertFalse(
            HostAppRules.usesGoogleApis(
                listOf("android.permission.INTERNET", "android.permission.CAMERA")
            )
        )
        assertFalse(HostAppRules.usesGoogleApis(emptyList()))
    }

    @Test
    fun `只要有一条命中就算依赖谷歌`() {
        assertTrue(
            HostAppRules.usesGoogleApis(
                listOf("android.permission.INTERNET", "com.google.android.gms.permission.AD_ID")
            )
        )
    }

    // ───────────────────── 搜索词匹配 ─────────────────────

    @Test
    fun `空搜索词全部通过`() {
        assertTrue(HostAppRules.matchesQuery(app("com.a", "微信"), ""))
        assertTrue(HostAppRules.matchesQuery(app("com.a", "微信"), "   "))
    }

    @Test
    fun `标签匹配忽略大小写`() {
        val a = app("com.tencent.mm", "WeChat")
        assertTrue(HostAppRules.matchesQuery(a, "wechat"))
        assertTrue(HostAppRules.matchesQuery(a, "WECHAT"))
        assertTrue(HostAppRules.matchesQuery(a, "cha"))
    }

    @Test
    fun `包名匹配忽略大小写`() {
        val a = app("com.tencent.mm", "微信")
        assertTrue(HostAppRules.matchesQuery(a, "TENCENT"))
        assertTrue(HostAppRules.matchesQuery(a, "com.tencent"))
    }

    @Test
    fun `标签和包名都不含搜索词时不匹配`() {
        assertFalse(HostAppRules.matchesQuery(app("com.tencent.mm", "微信"), "alipay"))
    }

    @Test
    fun `中文标签可以被中文搜索词命中`() {
        assertTrue(HostAppRules.matchesQuery(app("com.tencent.mm", "微信"), "微"))
    }

    // ───────────────────── 过滤流水线 ─────────────────────

    @Test
    fun `排除宿主自己`() {
        val list = listOf(app("com.dooble.space"), app("com.a"))
        val out = HostAppRules.filter(list, selfPackage = "com.dooble.space")
        assertEquals(listOf("com.a"), out.map { it.packageName })
    }

    @Test
    fun `默认隐藏系统应用`() {
        val list = listOf(app("com.a"), app("android", isSystem = true))
        assertEquals(
            listOf("com.a"),
            HostAppRules.filter(list, "com.self").map { it.packageName },
        )
    }

    @Test
    fun `显式要求时保留系统应用`() {
        val list = listOf(app("com.a"), app("android", isSystem = true))
        assertEquals(
            listOf("android", "com.a"),
            HostAppRules.filter(list, "com.self", includeSystem = true).map { it.packageName },
        )
    }

    @Test
    fun `只看谷歌依赖时过滤掉其余应用`() {
        val list = listOf(
            app("com.a", usesGoogle = true),
            app("com.b", usesGoogle = false),
        )
        assertEquals(
            listOf("com.a"),
            HostAppRules.filter(list, "com.self", onlyGmsDependent = true).map { it.packageName },
        )
    }

    @Test
    fun `搜索词与只看谷歌是叠加关系而不是二选一`() {
        val list = listOf(
            app("com.a", label = "Alpha", usesGoogle = true),
            app("com.b", label = "Beta", usesGoogle = true),
            app("com.c", label = "Alpha", usesGoogle = false),
        )
        val out = HostAppRules.filter(
            list,
            selfPackage = "com.self",
            onlyGmsDependent = true,
            query = "alpha",
        )
        assertEquals(listOf("com.a"), out.map { it.packageName })
    }

    @Test
    fun `按标签排序且忽略大小写`() {
        // 用 lowercase() 归一：否则 'Z' (0x5A) < 'a' (0x61)，Zebra 会排到 apple 前面。
        val list = listOf(
            app("com.z", label = "Zebra"),
            app("com.a", label = "apple"),
            app("com.b", label = "Banana"),
        )
        assertEquals(
            listOf("apple", "Banana", "Zebra"),
            HostAppRules.filter(list, "com.self").map { it.label },
        )
    }

    @Test
    fun `空列表返回空列表`() {
        assertTrue(HostAppRules.filter(emptyList(), "com.self").isEmpty())
    }

    // ───────────────────── 派生属性 ─────────────────────

    @Test
    fun `有拆分包时 hasSplits 为真`() {
        assertTrue(app("com.a", splits = listOf("/x/split_config.arm64_v8a.apk")).hasSplits)
        assertFalse(app("com.a").hasSplits)
    }

    @Test
    fun `依赖谷歌与需要谷歌环境是同一个判断`() {
        // needsGoogleEnvironment 是给 UI 读的别名，两者不能各判一套。
        assertTrue(app("com.a", usesGoogle = true).needsGoogleEnvironment)
        assertFalse(app("com.a", usesGoogle = false).needsGoogleEnvironment)
    }

    // ───────────────────── 提取产物命名 ─────────────────────

    @Test
    fun `单 APK 用 apk 后缀`() {
        assertEquals("com.a.apk", HostAppRules.archiveName("com.a", multiApk = false))
    }

    @Test
    fun `多 APK 用 apks 后缀`() {
        // 容器侧靠后缀区分"单个 APK"与"归档"，后缀错了会静默按单 APK 处理。
        assertEquals("com.a.apks", HostAppRules.archiveName("com.a", multiApk = true))
    }

    @Test
    fun `归档条目 base 永远在最前`() {
        assertEquals(listOf("base.apk"), HostAppRules.archiveEntries(0))
        assertEquals(
            listOf("base.apk", "split_0.apk", "split_1.apk"),
            HostAppRules.archiveEntries(2),
        )
    }

    @Test
    fun `归档条目数量等于 split 数加一`() {
        for (n in 0..8) {
            assertEquals(n + 1, HostAppRules.archiveEntries(n).size)
        }
    }

    @Test
    fun `归档条目名互不重复`() {
        // 重复的 zip entry 名会让 ZipOutputStream 抛异常（或覆盖），
        // 而原始 split 文件名（split_config.*.apk）不保证唯一 —— 所以用索引编号。
        val names = HostAppRules.archiveEntries(6)
        assertEquals(names.size, names.toSet().size)
    }

    // ───────────────────── 包可见性判定 ─────────────────────

    @Test
    fun `launcher 比 visible 多说明被拦截`() {
        // Android 11+ 上 getInstalledPackages 只给可见包；MIUI/ColorOS 的
        // 「读取应用列表」开关关着时它只剩自己，而 queryIntentActivities 那条路
        // 不受影响 —— 两者数量差就是被拦截的确凿证据。
        val v = HostAppRules.visibilityVerdict(visibleCount = 1, launcherCount = 87)
        assertTrue(v.gated)
        assertEquals(86, v.hiddenCount)
    }

    @Test
    fun `数量相等或更多都不算被拦截`() {
        assertFalse(HostAppRules.visibilityVerdict(50, 50).gated)
        // visible 比 launcher 多很正常：没有桌面图标的后台服务、插件、系统组件
        // 都在 getInstalledPackages 里但不在 launcher 里。这不是异常。
        assertFalse(HostAppRules.visibilityVerdict(120, 60).gated)
    }

    @Test
    fun `launcher 查询失败时不能判定为被拦截`() {
        // launcherCount == 0 说明是查询本身失败（而不是被拦），
        // 据此报警会给用户一个查不出原因的引导。
        assertFalse(HostAppRules.visibilityVerdict(visibleCount = 3, launcherCount = 0).gated)
        assertFalse(HostAppRules.visibilityVerdict(0, 0).gated)
    }

    @Test
    fun `hiddenCount 不会为负`() {
        assertEquals(0, HostAppRules.visibilityVerdict(10, 3).hiddenCount)
        assertEquals(0, HostAppRules.visibilityVerdict(0, 0).hiddenCount)
    }

    // ───────────────────── 兜底结果合并 ─────────────────────

    @Test
    fun `合并时主路径优先且按包名去重`() {
        val primary = listOf(app("com.a", label = "主路径A"), app("com.b"))
        val extra = listOf(app("com.a", label = "兜底A"), app("com.c"))
        val merged = HostAppRules.mergeDistinct(primary, extra)
        assertEquals(listOf("com.a", "com.b", "com.c"), merged.map { it.packageName })
        // 主路径的条目必须保留原样：兜底路径拿不到 GET_META_DATA，
        // 用它的版本覆盖会把已有的元数据抹掉。
        assertEquals("主路径A", merged.first().label)
    }

    @Test
    fun `合并保持主路径顺序不变`() {
        val primary = listOf(app("com.z"), app("com.y"))
        val merged = HostAppRules.mergeDistinct(primary, listOf(app("com.x")))
        assertEquals(listOf("com.z", "com.y", "com.x"), merged.map { it.packageName })
    }

    @Test
    fun `兜底为空时原样返回主路径`() {
        val primary = listOf(app("com.a"))
        assertEquals(primary, HostAppRules.mergeDistinct(primary, emptyList()))
    }

    @Test
    fun `兜底里的重复包只保留一条`() {
        val merged = HostAppRules.mergeDistinct(
            primary = emptyList(),
            extra = listOf(app("com.a", label = "第一次"), app("com.a", label = "第二次")),
        )
        assertEquals(1, merged.size)
        assertEquals("第一次", merged.first().label)
    }
}
