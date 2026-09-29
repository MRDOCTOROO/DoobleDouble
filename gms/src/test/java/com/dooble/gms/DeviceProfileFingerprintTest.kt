package com.dooble.gms

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「从 fingerprint 派生 DISPLAY / ID / ro.build.description」的回归测试。
 *
 * ## 这里曾经全错
 *
 * 旧实现用 `substringAfter('/').substringBefore('/')` 一类的方式切指纹：
 *  - `DISPLAY` 得到的是**设备代号**（walleye），而不是 build id；
 *  - `ID` 得到的是 `release-keys`；
 *  - `ro.build.description` 拼成 `walleye-user 28 release-keys`，三段全不对。
 *
 * 这组字段的价值恰恰在与 fingerprint **整体自洽**——派生错了反而是
 * "最容易识别的异常设备"。现在按标准结构
 * `brand/product/device:release/id/incremental:type/tags` 切分，用真实
 * 机型指纹（含厂商定制形态）钉死输出。
 */
class DeviceProfileFingerprintTest {

    @Test
    fun `Pixel 标准指纹派生正确`() {
        val fp = "google/husky/husky:14/AP1A.240505.005/11688946:user/release-keys"

        val parts = DeviceProfileSpoofer.parseFingerprint(fp)

        assertEquals("AP1A.240505.005", parts.display)
        assertEquals("AP1A.240505.005", parts.id)
        assertEquals("husky-user 14 AP1A.240505.005 11688946 release-keys", parts.description)
    }

    @Test
    fun `小米定制指纹（incremental 含厂商后缀）派生正确`() {
        val fp = "Xiaomi/houji/houji:14/UKQ1.231003.002/V816.0.5.0.UNCCNXM:user/release-keys"

        val parts = DeviceProfileSpoofer.parseFingerprint(fp)

        assertEquals("UKQ1.231003.002", parts.display)
        assertEquals("UKQ1.231003.002", parts.id)
        assertEquals("houji-user 14 UKQ1.231003.002 V816.0.5.0.UNCCNXM release-keys", parts.description)
    }

    @Test
    fun `畸形指纹不抛异常且回退占位`() {
        val parts = DeviceProfileSpoofer.parseFingerprint("nonsense")

        // 结构残缺时各段回退占位值——绝不能抛异常打断伪装配置的写入
        assertEquals("?", parts.display)
        assertEquals("?", parts.id)
        assertEquals("nonsense-user ? ? release-keys", parts.description)
    }
}
