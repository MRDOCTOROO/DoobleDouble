package com.dooble.gms

import com.dooble.engine.StorePackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「商店包名」这份跨模块名单的契约测试。
 *
 * ## 为什么需要它
 *
 * 这份名单有两个消费方，分处两个模块、两个进程角色：
 *  - **写入方 / 供给方**：`gms` 的 [StoreProvisioner] 决定往空间里装哪个商店；
 *  - **读取方 / 路由方**：`engine` 的容器在解析 `market://` 链接时，
 *    拿同一份名单决定"这条链接该交给谁"。
 *
 * 两者一旦脱节，失败方式同样是**静默**的：商店装上了，用户点应用内的
 * "更新"却没反应（链接被路由到一个没装、或者装了但没有界面的包）。
 * 编译期发现不了，因为两边各自都是合法的字符串。
 *
 * 所以这里把"名单形状"钉死：谁改了顺序或内容，测试立刻红。
 */
class StoreRoutingContractTest {

    @Test
    fun `供给的商店包名必须来自引擎那份名单`() {
        assertEquals(
            "StoreProvisioner 与容器路由用的必须是同一个包名",
            StorePackages.AURORA,
            StoreProvisioner.PRIMARY_PACKAGE,
        )
    }

    @Test
    fun `PRIMARY_PACKAGE 必须是名单里的第一优先项`() {
        // 容器按顺序逐个 setPackage() 试，第一个命中的胜出。
        // 如果主供给的商店不是第一项，会出现"装的是 A，链接却优先给 B"。
        assertEquals(
            "主供给商店必须排在最前面，否则链接会被优先路由到别的商店",
            StoreProvisioner.PRIMARY_PACKAGE,
            StorePackages.ALL.first(),
        )
    }

    @Test
    fun `Aurora 必须排在 FakeStore 之前`() {
        // microG 的 com.android.vending 是 FakeStore：只有"应用已安装"的占位实现，
        // 没有界面也没有下载能力。把它排在前面，用户点商店链接会"点了没反应"。
        assertTrue(
            "Aurora Store 必须优先于 FakeStore",
            StorePackages.ALL.indexOf(StorePackages.AURORA) <
                StorePackages.ALL.indexOf(StorePackages.FAKE_STORE),
        )
    }

    @Test
    fun `名单里不能有空项或重复项`() {
        // 空项会让 setPackage("") 抛异常（被 catch 掉，但白白浪费一次解析）；
        // 重复项没有意义，只说明有人改名单时手滑了。
        StorePackages.ALL.forEach { pkg ->
            assertTrue("包名不能为空白：'$pkg'", pkg.isNotBlank())
            assertTrue("包名不能含空格：'$pkg'", !pkg.contains(' '))
        }
        assertEquals(
            "名单里不能有重复包名",
            StorePackages.ALL.size,
            StorePackages.ALL.toSet().size,
        )
    }

    @Test
    fun `名单必须覆盖 FakeStore 作为兜底`() {
        // 用户可能只装了 microG（FakeStore）而没装 Aurora。
        // 少了这一项，那种空间里点商店链接会一路放行到宿主系统。
        assertTrue(
            "FakeStore 必须在兜底名单里",
            StorePackages.FAKE_STORE in StorePackages.ALL,
        )
    }
}
