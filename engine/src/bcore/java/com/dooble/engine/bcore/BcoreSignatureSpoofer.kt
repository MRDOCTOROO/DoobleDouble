package com.dooble.engine.bcore

import android.content.pm.Signature
import com.dooble.engine.internal.SpaceStore
import com.dooble.engine.internal.SpoofPolicyCodec
import top.niunaijun.blackbox.core.system.pm.PackageSignatureSpoofer
import java.io.File

/**
 * 容器级签名伪造的**宿主侧实现**。
 *
 * ## 它跑在哪、什么时候被调用
 *
 * 注册进 Bcore 的 `PackageSignatureSpoofer` 之后，它会在**容器服务进程**
 * （Bcore 的 `:black`）里被调用——具体位置是 `PackageManagerCompat`
 * 生成 `PackageInfo` 的出口，也就是容器内 `signatures` / `signingInfo`
 * 唯一的填充处。每一次包查询都会走到这里，**包括容器自身的内部查询**。
 *
 * 因此三条硬约束：
 *  1. **必须快**。一次虚拟应用启动会触发几十上百次包查询。
 *  2. **必须线程安全**。Binder 线程池会并发调用。
 *  3. **绝不能抛异常**。抛出去会把容器的包管理搞崩，代价远大于"签名没伪造成功"。
 *     （`PackageSignatureSpoofer` 里已经兜了一层，这里再兜一层是有意的冗余。）
 *
 * ## 为什么要读文件而不是共享内存
 *
 * 策略的**写入方**是主进程里的 `GmsSignatureSpoof`（用户点一下"切换模式"就写盘），
 * 读取方是本进程。两者之间没有回调通道——为一个只有几 KB 的配置去搭 Binder
 * 服务或者 ContentProvider，复杂度远高于收益。文件 + 短 TTL 缓存是最简单的
 * 一致性方案：用户改完模式，最多 3 秒后生效。
 *
 * ## 契约：策略文件格式
 *
 * 本类**刻意用宽松解析**（只挑自己需要的字段，容忍多余字段和版本演进），
 * 这样 `gms` 模块的 DTO 可以自由升级而不必同步改动引擎。契约只有四条：
 *
 * ```json
 * {
 *   "enabled": true,                       // 必须为 true，否则整体忽略
 *   "targets": [                           // 每一项都必须同时具备下面两个字段
 *     { "packageName": "...", "derBase64": "..." }
 *   ]
 * }
 * ```
 *
 * `derBase64` 是证书**原始 DER 字节**的 Base64。容器侧用
 * `Signature(der)` 构造返回值——注意 microG 比对的是 `sig.toByteArray()` 的
 * SHA-1，也就是这段原始字节的 SHA-1，所以这里必须是 DER 本身，
 * 不能是 PEM 文本，也不能是别的什么东西。
 */
object BcoreSignatureSpoofer : PackageSignatureSpoofer.Hook {

    /** 策略缓存有效期。取 3 秒：改配置后感知足够快，又不至于每次查询都读盘 */
    private const val TTL_MS = 3_000L

    private const val POLICY_RELATIVE = "data/.dooble/spoof_policy.json"

    /** `<filesDir>/dooble`。由 [install] 注入 */
    @Volatile
    private var root: File? = null

    /** userId -> 包名 -> 伪造签名 */
    @Volatile
    private var cache: Map<Int, Map<String, Array<Signature>>> = emptyMap()

    @Volatile
    private var cacheAt = 0L

    /**
     * 注册钩子。由 `BcoreEngine.initialize()` 在**每个进程**里调用——
     * 尤其是容器服务进程，那里才是签名查询真正发生的地方。
     */
    fun install(rootDir: File) {
        root = rootDir
        invalidate()
        PackageSignatureSpoofer.setHook(this)
    }

    fun uninstall() {
        PackageSignatureSpoofer.setHook(null)
        root = null
        invalidate()
    }

    private fun invalidate() {
        cache = emptyMap()
        cacheAt = 0L
    }

    // ------------------------------------------------------------ 钩子入口

    override fun spoof(packageName: String, userId: Int): Array<Signature>? {
        if (userId <= 0 || packageName.isEmpty()) return null
        return runCatching { table()[userId]?.get(packageName) }.getOrNull()
    }

    private fun table(): Map<Int, Map<String, Array<Signature>>> {
        if (System.currentTimeMillis() - cacheAt < TTL_MS) return cache

        synchronized(this) {
            // 双重检查：并发进来时只让第一个线程真正重建
            if (System.currentTimeMillis() - cacheAt < TTL_MS) return cache

            val fresh = runCatching { buildTable() }.getOrDefault(emptyMap())
            cache = fresh
            cacheAt = System.currentTimeMillis()
            return fresh
        }
    }

    /**
     * 重建映射表：`spaces.json` 给出 userId ↔ spaceId，各空间的策略文件给出
     * 包名 → 证书。
     *
     * 每次都新建一个 [SpaceStore] 而不是复用：`SpaceStore` 在内存里缓存了
     * `spaces.json` 的内容，而**写入方在主进程**——复用实例会永远看不到新建的
     * 空间，表现为"新空间里签名伪造不生效"。这个文件只有几 KB，重读的代价
     * 远低于缓存失准的代价。
     */
    private fun buildTable(): Map<Int, Map<String, Array<Signature>>> {
        val base = root ?: return emptyMap()
        if (!base.isDirectory) return emptyMap()

        val result = HashMap<Int, Map<String, Array<Signature>>>()

        SpaceStore(base).all().forEach { record ->
            val userId = record.bcoreUserId
            if (userId <= 0) return@forEach

            val policyFile = File(base, "virtual/${record.config.id}/$POLICY_RELATIVE")
            val targets = readPolicy(policyFile)
            if (targets.isNotEmpty()) {
                result[userId] = targets.toMap()
            }
        }

        return result
    }

    /**
     * 读一份策略文件，转成容器能直接返回的 `Signature[]`。
     *
     * 解析逻辑本身在 [SpoofPolicyCodec] 里——那是零 Android 依赖的纯函数，
     * 由 `gms` 模块的单元测试直接覆盖格式契约。这里只负责最后一跳：
     * 把 DER 字节包成 `Signature`。
     */
    private fun readPolicy(file: File): List<Pair<String, Array<Signature>>> {
        if (!file.isFile || file.length() == 0L) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return SpoofPolicyCodec.parse(text).map { target ->
            target.packageName to arrayOf(Signature(target.der))
        }
    }
}
