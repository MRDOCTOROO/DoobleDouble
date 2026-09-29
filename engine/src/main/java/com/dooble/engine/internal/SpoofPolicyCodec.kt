package com.dooble.engine.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「签名伪造策略」文件的**格式契约**。
 *
 * ## 为什么单独抽出来
 *
 * 这份文件的**写入方在 `gms` 模块**（`GmsSignatureSpoof.apply`），
 * **读取方在引擎的容器服务进程**（`BcoreSignatureSpoofer`）。两者之间只有
 * 「JSON 字段叫什么名字」这一个约定——而这类约定恰恰最容易在重构中悄悄错开：
 * 写方改了字段名，读方照旧，结果是**编译通过、单测通过、运行时静默不生效**。
 *
 * 所以把它抽成一个**零 Android 依赖的纯函数**，让 `gms` 那边的单元测试能直接
 * 拿真实的序列化结果喂进来验证。抽出来之后 `BcoreSignatureSpoofer` 只负责
 * 「把 DER 字节包成 Signature」，解析逻辑不再和 Bcore 绑死。
 *
 * ## 契约
 *
 * ```json
 * {
 *   "enabled": true,                        // 必须为 true，否则整份策略被忽略
 *   "targets": [
 *     { "packageName": "com.google.android.gms", "derBase64": "MIIF..." }
 *   ]
 * }
 * ```
 *
 * 解析刻意**宽松**：只挑自己需要的字段，容忍多余字段与版本演进。
 * 任何一处不合法都只跳过那一条，绝不抛异常——策略文件坏掉最坏的结果应该是
 * "不伪造"，而不是"容器起不来"。
 */
object SpoofPolicyCodec {

    /**
     * 一条伪造目标。
     *
     * 手写 equals/hashCode 是因为 [der] 是 `ByteArray`——`data class` 自动生成的
     * equals 用引用比较，会让断言和去重都失效。
     */
    class Target(val packageName: String, val der: ByteArray) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Target) return false
            return packageName == other.packageName && der.contentEquals(other.der)
        }

        override fun hashCode(): Int = 31 * packageName.hashCode() + der.contentHashCode()

        override fun toString(): String = "Target($packageName, der=${der.size}B)"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 解析策略文件内容。返回空列表表示"不需要伪造" */
    fun parse(text: String): List<Target> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyList()

        // enabled 缺失时按 false 处理：宁可少伪造，不可误伪造
        if (root["enabled"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()

        val targets = runCatching { root["targets"]?.jsonArray }.getOrNull() ?: return emptyList()

        return targets.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null

            val packageName = obj["packageName"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val encoded = obj["derBase64"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val der = runCatching { Base64Codec.decode(encoded) }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            Target(packageName, der)
        }
    }
}
