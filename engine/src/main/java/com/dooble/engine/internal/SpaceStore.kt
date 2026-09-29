package com.dooble.engine.internal

import com.dooble.engine.SpaceConfig
import com.dooble.engine.VirtualApp
import com.dooble.engine.VirtualEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 空间元数据的本地持久化。
 *
 * 落盘位置：`<宿主 filesDir>/dooble/spaces.json`
 * 之所以不放进数据库：空间元数据量级很小（几十条），JSON 文件足够，
 * 且可以整体导出/导入，方便用户迁移与备份。
 *
 * @param root 元数据目录。**这里只放 `spaces.json`，不放空间数据。**
 */
class SpaceStore(private val root: java.io.File) {

    @Serializable
    data class Record(
        val config: SpaceConfig,
        val apps: List<AppRecord> = emptyList(),
        /**
         * 该空间在虚拟化引擎里对应的 userId。
         *
         * 为什么必须持久化而不是用列表下标推算：用户删掉中间某个空间后，
         * 下标会整体前移，两个空间就会指向同一个 userId——表现是「两个空间
         * 的应用和账号互相串味」。这是数据正确性问题，不能靠推算。
         *
         * 0 表示尚未分配（宿主保留值，不分配给任何空间）。
         */
        val bcoreUserId: Int = 0,
    )

    @Serializable
    data class AppRecord(
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val isSystem: Boolean,
        val installedAt: Long,
        val apkPath: String,
        val targetSdk: Int,
        val usesGoogleApis: Boolean,
    ) {
        fun toApp() = VirtualApp(
            packageName = packageName,
            label = label,
            versionName = versionName,
            versionCode = versionCode,
            isSystem = isSystem,
            installedAt = installedAt,
            apkPath = apkPath,
            targetSdk = targetSdk,
            usesGoogleApis = usesGoogleApis,
        )
    }

    private val file = java.io.File(root, "spaces.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mapSerializer =
        MapSerializer(String.serializer(), Record.serializer())

    @Volatile
    private var cache: MutableMap<String, Record> = load()

    @Synchronized
    private fun load(): MutableMap<String, Record> {
        if (!file.exists()) return linkedMapOf()
        return runCatching {
            json.decodeFromString<Map<String, Record>>(file.readText()).toMutableMap()
        }.getOrElse { linkedMapOf() }
    }

    @Synchronized
    fun flush() {
        runCatching {
            root.mkdirs()
            val tmp = java.io.File(root, "spaces.json.tmp")
            tmp.writeText(json.encodeToString(mapSerializer, cache.toMap()))
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun all(): List<Record> = cache.values.toList()

    @Synchronized
    fun get(spaceId: String): Record? = cache[spaceId]

    @Synchronized
    fun put(record: Record) {
        cache[record.config.id] = record
        flush()
    }

    @Synchronized
    fun remove(spaceId: String) {
        cache.remove(spaceId)
        flush()
    }

    /**
     * 供 UI 显示用：累计磁盘占用。
     *
     * 刻意**不**自己猜路径，而是由调用方传入要统计的目录。原因：两个引擎的
     * 数据落盘位置完全不同，而且**都不在** [root] 底下——
     *   - `SandboxEngine`：`<root>/virtual/<spaceId>/`
     *   - `BcoreEngine`：`/data/data/<宿主包名>/blackbox/…`（Bcore 自己的布局，
     *     见 [BcoreStorageLayout]）
     *
     * 早期版本在 SpaceStore 里硬编码 `root/<spaceId>`，两个引擎都统计不到东西，
     * 于是恒返回 `0`。**`0` 是个"合法"数字**——不抛异常、不打日志，UI 上就是
     * 安静地显示 0，很容易被当成"还没统计到"而放过去（实测空间里装了 8 个
     * 应用、223MB，界面照旧写 0）。所以现在把"统计哪里"这个决定交回给
     * 最清楚这件事的调用方。
     */
    fun diskUsage(vararg dirs: java.io.File): Long = dirs.sumOf { dir ->
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
