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

    private companion object {
        const val TAG = "DoobleSpaceStore"
    }

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
        val parsed = runCatching {
            json.decodeFromString<Map<String, Record>>(file.readText())
        }.getOrNull()
        if (parsed != null) return parsed.toMutableMap()

        // 解析失败绝不能当作"没有空间"处理：空表随后会被任意一次 put() 全量写回，
        // 旧内容永久丢失——连着 bcoreUserId 映射一起丢，allocateUserId 会把已占用
        // 的 userId 分给新空间，新空间直接继承上一个空间的应用与账号。
        // 把坏文件改名留档（人还能手工抢救），再从空表起步。
        val archive = java.io.File(root, "spaces.json.corrupt-${System.currentTimeMillis()}")
        runCatching {
            file.renameTo(archive)
            android.util.Log.e(TAG, "spaces.json 解析失败，已留档到 ${archive.name}")
        }
        return linkedMapOf()
    }

    @Synchronized
    fun flush() {
        runCatching {
            root.mkdirs()
            val tmp = java.io.File(root, "spaces.json.tmp")
            tmp.writeText(json.encodeToString(mapSerializer, cache.toMap()))
            // renameTo 失败时返回 false 而不是抛异常——不检查的话磁盘上还是旧内容，
            // 内存里却是新数据，两边静默分叉且无任何信号。失败就直接覆盖写兜底。
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(mapSerializer, cache.toMap()))
                tmp.delete()
            }
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

    // ------------------------------------------------------------------ userId 高水位
    //
    // spaces.json 是"哪些 userId 已经分配出去"的唯一账本，但它可能损坏
    // （损坏时本类会留档并从空表起步）。高水位文件是**第二本账**：只记录
    // 历史上发出去过的最大 userId，跨删除单调递增、永不回退——
    // 哪怕注册表从零重建，allocateUserId 也不会把旧 userId 发给新空间。

    private val userIdFloorFile = java.io.File(root, "user-id-floor")

    /** 历史上分配过的最大 userId。没有记录时返回 0（宿主保留值） */
    @Synchronized
    fun userIdFloor(): Int =
        runCatching { userIdFloorFile.readText().trim().toInt() }.getOrDefault(0)

    /** 记录"这个 userId 已经发出去过"。只升不降，失败静默（它只是兜底账本） */
    @Synchronized
    fun markUserIdUsed(id: Int) {
        if (id <= userIdFloor()) return
        runCatching {
            root.mkdirs()
            val tmp = java.io.File(root, "user-id-floor.tmp")
            tmp.writeText(id.toString())
            if (!tmp.renameTo(userIdFloorFile)) {
                // renameTo 目标已存在时在部分 JVM 上会失败；直接覆盖写兜底
                userIdFloorFile.writeText(id.toString())
            }
        }
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
