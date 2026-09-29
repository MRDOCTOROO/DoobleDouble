package com.dooble.gms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * microG 版本检测。
 *
 * 为什么要做这件事：microG 的 release 资产文件名里**内嵌了版本号**
 * （例如 `com.google.android.gms-252432032.apk`），所以硬编码的下载地址
 * 一旦上游发新版就会 404。这里改成运行时查 GitHub release，
 * 从 assets 列表里按前缀匹配真实文件名。
 *
 * 注意：GitHub 匿名 API 有速率限制（每小时 60 次/IP）。命中限流时
 * 返回失败，调用方回退到 [MicroGRepository] 里硬编码的已知可用版本——
 * 所以"能联网"和"能下载"这两件事是解耦的，限流不会让整个装配流程挂掉。
 */
object MicroGVersionChecker {

    /** 本检测器只查这一个仓库。GSF Proxy 在另一个仓库，见 [MicroGComponent.GSF_PROXY] */
    const val GMS_CORE_REPO = "microg/GmsCore"

    private const val API = "https://api.github.com/repos/$GMS_CORE_REPO/releases/latest"

    data class Release(
        val tag: String,
        val publishedAt: String,
        val assets: List<Asset>,
        /**
         * 这个 release 属于哪个仓库。
         *
         * 存在的理由：GSF Proxy 在另一个仓库，它的 tag 与资产名跟 GmsCore 完全无关。
         * 如果调用方把 GmsCore 的 release 传给 GSF Proxy，`assetFor` 会拿
         * GmsCore 的资产列表去匹配 gsf 前缀 —— 匹配不到是运气好，匹配到就是装错包。
         * 所以由 [MicroGRepository.candidates] 显式核对仓库后再用。
         */
        val repo: String = GMS_CORE_REPO,
    ) {
        data class Asset(val name: String, val url: String, val sizeBytes: Long)

        /** 按包名前缀找资产。hw 后缀是华为变体，需要显式指定 */
        fun assetFor(packageName: String, huawei: Boolean = false): Asset? {
            val candidates = assets.filter { it.name.startsWith("$packageName-") }
            if (candidates.isEmpty()) return null
            val wantHw = candidates.filter { it.name.contains("-hw.") }
            val wantStd = candidates.filter { !it.name.contains("-hw.") }
            val pool = if (huawei) wantHw.ifEmpty { wantStd } else wantStd.ifEmpty { wantHw }
            return pool.maxByOrNull { it.sizeBytes }
        }
    }

    sealed interface Result {
        data class Ok(val release: Release) : Result
        data class Failed(val reason: String) : Result
    }

    @Volatile
    private var cached: Release? = null

    @Volatile
    private var cachedAt: Long = 0

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    /** 查最新 release，带 6 小时内存缓存 */
    suspend fun latest(forceRefresh: Boolean = false): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cached?.let {
            if (!forceRefresh && now - cachedAt < CACHE_TTL_MS) return@withContext Result.Ok(it)
        }

        runCatching {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DoobleDouble/1.0")
            }
            try {
                val code = conn.responseCode
                if (code == 403 || code == 429) {
                    return@withContext Result.Failed("GitHub API 限流（HTTP $code），请稍后再试或改用离线包")
                }
                if (code !in 200..299) {
                    return@withContext Result.Failed("GitHub API 返回 HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name")
                val published = json.optString("published_at")
                val arr: JSONArray = json.optJSONArray("assets") ?: JSONArray()
                val assets = buildList {
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONObject(i)
                        val name = a.optString("name")
                        if (!name.endsWith(".apk")) continue
                        add(
                            Release.Asset(
                                name = name,
                                url = a.optString("browser_download_url"),
                                sizeBytes = a.optLong("size"),
                            )
                        )
                    }
                }
                if (tag.isBlank() || assets.isEmpty()) {
                    return@withContext Result.Failed("解析 release 失败（tag=$tag, assets=${assets.size}）")
                }
                val release = Release(tag, published, assets, GMS_CORE_REPO)
                cached = release
                cachedAt = System.currentTimeMillis()
                Result.Ok(release)
            } finally {
                conn.disconnect()
            }
        }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /** 缓存中已知的版本号，UI 可直接展示 */
    fun knownTag(): String = cached?.tag ?: MicroGRepository.RELEASE_TAG

    /**
     * 判断当前缓存的版本是否比基线新。
     * microG 的 tag 形如 `v0.3.16.252432`，按数字段比较。
     */
    fun isNewer(candidate: String, baseline: String = MicroGRepository.RELEASE_TAG): Boolean {
        fun parts(s: String) = s.trimStart('v', 'V').split('.').mapNotNull { it.toLongOrNull() }
        val a = parts(candidate)
        val b = parts(baseline)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
