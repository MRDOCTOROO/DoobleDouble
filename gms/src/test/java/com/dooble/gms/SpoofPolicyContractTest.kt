package com.dooble.gms

import com.dooble.engine.internal.Base64Codec
import com.dooble.engine.internal.SpoofPolicyCodec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * 「签名伪造策略」文件的**格式契约测试**。
 *
 * ## 为什么需要它
 *
 * 这份文件的写入方在 `gms`（[GmsSignatureSpoof.apply]），读取方在引擎的容器
 * 服务进程（`BcoreSignatureSpoofer` → [SpoofPolicyCodec]）。两端之间只有
 * 「JSON 字段叫什么名字」这一个约定。
 *
 * 而字段名对不上时的失败方式特别恶心：**编译通过、单测通过、运行时静默不生效**
 * ——用户看到的是"签名伪造开了但没效果"，排查起来要跨模块、跨进程一路追。
 *
 * 所以这里不做单元级别的 mock，而是拿**真实的序列化输出**直接喂给引擎真正的
 * 解析器。任何一端改了字段名，这个测试立刻红。
 */
class SpoofPolicyContractTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 造一段假的证书字节。内容不重要，重要的是能原样往返 */
    private fun fakeDer(seed: Int, size: Int = 600) =
        ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun policyOf(der: ByteArray, enabled: Boolean = true) =
        GmsSignatureSpoof.SpoofPolicy(
            mode = SpoofMode.TEST.name,
            enabled = enabled,
            targets = GmsSignatureSpoof.SPOOF_PACKAGES.map { pkg ->
                GmsSignatureSpoof.SpoofTarget(
                    packageName = pkg,
                    sha1 = sha1(der),
                    derBase64 = Base64Codec.encode(der),
                )
            },
        )

    private fun serialize(policy: GmsSignatureSpoof.SpoofPolicy): String =
        json.encodeToString(GmsSignatureSpoof.SpoofPolicy.serializer(), policy)

    // ------------------------------------------------------------------ 主契约

    @Test
    fun `引擎能读懂 gms 写出的策略`() {
        val der = fakeDer(seed = 1)

        val parsed = SpoofPolicyCodec.parse(serialize(policyOf(der)))

        assertEquals(
            "包名集合必须完全一致",
            GmsSignatureSpoof.SPOOF_PACKAGES.toSet(),
            parsed.map { it.packageName }.toSet(),
        )
        parsed.forEach { target ->
            assertArrayEquals(
                "证书字节必须原样往返（Base64 编解码不能有任何偏差）",
                der,
                target.der,
            )
        }
    }

    @Test
    fun `策略里的 sha1 必须等于证书 DER 的 sha1`() {
        val der = fakeDer(seed = 7)
        val policy = policyOf(der)

        // microG 的比对方式：sha1bytes(sig.toByteArray()) 与常量比对。
        // 容器侧返回的是 new Signature(der)，所以 toByteArray() 就是 der 本身。
        // 这条断言保证写入方算的哈希与运行时容器返回的字节是同一个东西。
        policy.targets.forEach { target ->
            val runtimeBytes = Base64Codec.decode(target.derBase64)
            assertEquals(
                "策略里写的 sha1 与 DER 实际哈希不一致，容器返回的签名永远匹配不上",
                target.sha1,
                sha1(runtimeBytes),
            )
        }
    }

    // ------------------------------------------------------------------ 边界

    @Test
    fun `enabled 为 false 时引擎忽略整份策略`() {
        val der = fakeDer(seed = 2)
        val parsed = SpoofPolicyCodec.parse(serialize(policyOf(der, enabled = false)))

        assertTrue("关闭状态下不应产出任何伪造目标", parsed.isEmpty())
    }

    @Test
    fun `缺少 enabled 字段时按关闭处理`() {
        // 宁可少伪造，不可误伪造
        val text = """{"targets":[{"packageName":"com.google.android.gms","derBase64":"AAAA"}]}"""

        assertTrue(SpoofPolicyCodec.parse(text).isEmpty())
    }

    @Test
    fun `条目缺字段时被跳过而不是抛异常`() {
        val der = fakeDer(seed = 3)
        val good = Base64Codec.encode(der)
        val text = """
            {
              "enabled": true,
              "targets": [
                {"packageName": "com.google.android.gms", "derBase64": "$good"},
                {"packageName": "", "derBase64": "$good"},
                {"packageName": "com.missing.der"},
                {"derBase64": "$good"},
                {"packageName": "com.bad.base64", "derBase64": "这不是合法 Base64!!!"}
              ]
            }
        """.trimIndent()

        val parsed = SpoofPolicyCodec.parse(text)

        assertEquals("只有第一条是合法的", 1, parsed.size)
        assertEquals("com.google.android.gms", parsed.first().packageName)
    }

    @Test
    fun `未知字段不影响解析（向前兼容）`() {
        val der = fakeDer(seed = 4)
        val good = Base64Codec.encode(der)
        val text = """
            {
              "enabled": true,
              "version": 99,
              "futureField": {"nested": [1, 2, 3]},
              "targets": [
                {"packageName": "com.google.android.gms", "derBase64": "$good", "extra": true}
              ]
            }
        """.trimIndent()

        val parsed = SpoofPolicyCodec.parse(text)

        assertEquals(1, parsed.size)
        assertArrayEquals(der, parsed.first().der)
    }

    @Test
    fun `内容不是 JSON 时返回空表`() {
        assertTrue(SpoofPolicyCodec.parse("").isEmpty())
        assertTrue(SpoofPolicyCodec.parse("{ 这不是 JSON").isEmpty())
        assertTrue(SpoofPolicyCodec.parse("[]").isEmpty())
    }

    // ------------------------------------------------------------------ Base64

    @Test
    fun `Base64 编解码覆盖各种长度`() {
        // 0/1/2 是补 '=' 的边界，3 是整组，600 是真实证书量级
        listOf(0, 1, 2, 3, 4, 5, 255, 600, 1024).forEach { size ->
            val original = ByteArray(size) { ((it * 7 + size) and 0xFF).toByte() }
            val roundTrip = Base64Codec.decode(Base64Codec.encode(original))
            assertArrayEquals("长度 $size 的往返失败", original, roundTrip)
        }
    }

    @Test
    fun `Base64 编码结果与标准实现一致`() {
        // 用 JDK 自带实现做交叉验证，防止自研编码器写出"自己能读、别人读不了"的格式
        val bytes = "Dooble Double 签名伪造测试".toByteArray(Charsets.UTF_8)
        val expected = java.util.Base64.getEncoder().encodeToString(bytes)

        assertEquals(expected, Base64Codec.encode(bytes))
        assertArrayEquals(bytes, Base64Codec.decode(expected))
    }
}
