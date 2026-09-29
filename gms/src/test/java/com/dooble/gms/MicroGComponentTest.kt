package com.dooble.gms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * microG 组件清单的契约测试。
 *
 * ## 为什么值得钉
 *
 * 这份清单同时被三个地方消费：供给层的下载地址拼接、容器内的包名匹配、
 * UI 的槽位呈现。它们错开的方式都是**静默**的：
 *  - `assetName` 重复 → 两个组件互相覆盖对方的缓存文件，装出来的是同一个包；
 *  - `releaseTag` 与仓库不匹配 → 404，被镜像回退掩盖成"网络慢"；
 *  - `remoteName` 与实际资产不符 → 下载到 404 页面，`looksLikeApk` 才发现。
 */
class MicroGComponentTest {

    @Test
    fun `包名与离线资产名互不重复`() {
        // 重复的 assetName 会让两个组件共用一份缓存文件 —— 先下的那个会被后下的覆盖，
        // 用户看到的是"两个组件都装上了"，实际是同一个包装了两遍。
        val assetNames = MicroGComponent.entries.map { it.assetName }
        assertEquals("离线资产名不能重复", assetNames.size, assetNames.toSet().size)

        val packages = MicroGComponent.entries.map { it.packageName }
        assertEquals("包名不能重复", packages.size, packages.toSet().size)
    }

    @Test
    fun `包名必须落在 microG 已知的那几个上`() {
        // 这几个包名是容器内签名伪造白名单、自检、应用兼容性判断的共同依据。
        val known = setOf(
            "com.google.android.gms",
            "com.android.vending",
            "com.google.android.gsf",
            "com.google.android.maps",
        )
        MicroGComponent.entries.forEach {
            assertTrue("意外的包名：${it.packageName}", it.packageName in known)
        }
    }

    @Test
    fun `有直链的组件必须同时给出仓库 tag 和文件名`() {
        MicroGComponent.entries.filter { it.downloadable }.forEach {
            assertTrue("${it.shortName} 缺 downloadRepo", it.downloadRepo.isNotBlank())
            assertTrue("${it.shortName} 缺 releaseTag", it.releaseTag.isNotBlank())
            assertTrue("${it.shortName} 缺 remoteName", it.remoteName.isNotBlank())
            assertTrue("${it.shortName} 的直链必须是 .apk", it.remoteName.endsWith(".apk"))
            assertTrue("${it.shortName} 的 tag 应以 v 开头", it.releaseTag.startsWith("v"))
        }
    }

    @Test
    fun `无直链的组件必须给出原因`() {
        // 没有直链又不给原因，UI 只能显示"未安装"，用户会一直找下载按钮。
        MicroGComponent.entries.filter { !it.downloadable }.forEach {
            assertNotNull("${it.shortName} 无直链却没写原因", it.manualOnlyReason)
            assertTrue("${it.shortName} 的原因不能为空", it.manualOnlyReason!!.isNotBlank())
            assertTrue("${it.shortName} 不该有仓库", it.downloadRepo.isBlank())
        }
    }

    @Test
    fun `GmsCore 与 FakeStore 共用同一个仓库和 tag`() {
        // 这两个是同一个 release 里的两个资产。tag 写岔了会导致其中一个 404。
        assertEquals(MicroGComponent.GMS_CORE.downloadRepo, MicroGComponent.FAKE_STORE.downloadRepo)
        assertEquals(MicroGComponent.GMS_CORE.releaseTag, MicroGComponent.FAKE_STORE.releaseTag)
    }

    @Test
    fun `GmsCore 的 tag 与仓库常量保持一致`() {
        // MicroGRepository.RELEASE_TAG 是给 UI 与自检用的展示值，
        // 真正的下载地址用的是组件自己带的 tag。两者漂移会让界面显示的版本
        // 和实际下载的版本对不上。
        assertEquals(MicroGRepository.RELEASE_TAG, MicroGComponent.GMS_CORE.releaseTag)
    }

    @Test
    fun `华为变体只在有变体时生效否则回退标准版`() {
        // 上游某次发版可能漏掉 -hw 包，此时必须回退而不是返回空串。
        assertTrue(MicroGComponent.GMS_CORE.remoteFileName(huawei = true).contains("-hw"))
        assertFalse(MicroGComponent.GMS_CORE.remoteFileName(huawei = false).contains("-hw"))

        // GSF Proxy 上游没有 -hw 变体
        assertNull(MicroGComponent.GSF_PROXY.remoteNameHw)
        assertEquals(
            MicroGComponent.GSF_PROXY.remoteName,
            MicroGComponent.GSF_PROXY.remoteFileName(huawei = true),
        )
    }

    @Test
    fun `无直链组件在华为变体下也返回空串`() {
        assertEquals("", MicroGComponent.MAPS_V1.remoteFileName(huawei = false))
        assertEquals("", MicroGComponent.MAPS_V1.remoteFileName(huawei = true))
    }

    @Test
    fun `REQUIRED 只含必需组件`() {
        assertTrue(MicroGComponent.GMS_CORE in MicroGComponent.REQUIRED)
        assertTrue(MicroGComponent.FAKE_STORE in MicroGComponent.REQUIRED)
        assertFalse(MicroGComponent.GSF_PROXY in MicroGComponent.REQUIRED)
        assertFalse(MicroGComponent.MAPS_V1 in MicroGComponent.REQUIRED)
    }

    @Test
    fun `可装配槽位不含已废弃组件`() {
        // 留一个永远装不上的槽位，用户会反复点、反复失败，然后认为整个功能是坏的。
        assertFalse(MicroGComponent.MAPS_V1 in MicroGComponent.INSTALLABLE)
        assertTrue(MicroGComponent.MAPS_V1.deprecated)
        assertNotNull(MicroGComponent.MAPS_V1.deprecatedReason)

        // 其余都在
        assertTrue(MicroGComponent.GMS_CORE in MicroGComponent.INSTALLABLE)
        assertTrue(MicroGComponent.FAKE_STORE in MicroGComponent.INSTALLABLE)
        assertTrue(MicroGComponent.GSF_PROXY in MicroGComponent.INSTALLABLE)
    }

    @Test
    fun `除 MapsV1 外没有别的组件被标为废弃`() {
        // 误标废弃会让功能悄悄从 UI 上消失。
        val deprecated = MicroGComponent.entries.filter { it.deprecated }
        assertEquals(listOf(MicroGComponent.MAPS_V1), deprecated)
    }

    @Test
    fun `byPackage 能反查回组件`() {
        MicroGComponent.entries.forEach {
            assertEquals(it, MicroGComponent.byPackage(it.packageName))
        }
        assertNull(MicroGComponent.byPackage("com.example.nope"))
    }

    @Test
    fun `releaseUrl 只在有仓库时给出`() {
        assertTrue(MicroGComponent.GSF_PROXY.releaseUrl.contains("/microg/GsfProxy/"))
        assertTrue(MicroGComponent.GSF_PROXY.releaseUrl.endsWith("/v0.1.0"))
        assertEquals("", MicroGComponent.MAPS_V1.releaseUrl)
    }

    @Test
    fun `每个组件的展示字段都不为空`() {
        MicroGComponent.entries.forEach {
            assertTrue("${it.name} 缺 shortName", it.shortName.isNotBlank())
            assertTrue("${it.name} 缺 description", it.description.isNotBlank())
            assertTrue("${it.name} 的预估体积应为正", it.approxSizeMb > 0)
        }
    }
}
