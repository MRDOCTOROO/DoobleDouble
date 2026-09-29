package com.dooble.space.service

import android.content.Context
import android.content.SharedPreferences

/**
 * 「最近使用」顺序表的**纯逻辑**：顺序维护 + 文本编解码。
 *
 * ## 为什么从 [WarmPool] 里拆出来
 *
 * [WarmPool] 本身要碰 `SharedPreferences`，在 JVM 单测里没法直接跑。但这张表
 * 真正会出错的地方**不是 I/O，是格式和顺序语义**：
 *
 * - 分隔符选得不好 → 空间 id 或包名里撞上分隔符 → 一行被拆成更多段 → 整行被丢弃
 * - 坏行处理不当 → 一行写坏，整张表读回来是空的
 * - 去重漏了 → 同一个应用出现多次，回收时"占两个名额"
 *
 * 而这张表**空了是要出事的**：它是内存回收的依据，读回空表意味着
 * "所有应用都没被打开过"，第一次内存告急就会把用户刚打开的应用回收掉——
 * 恰好是最伤体验的一种误判。所以顺序与格式这部分必须有单测钉住。
 *
 * 拆出来之后 [WarmPool] 只剩"读 → 交给这里 → 写回去"三行。
 */
object WarmOrder {

    /** 行分隔符用不可见控制字符，避免和空间 id / 包名撞车 */
    const val ROW = '\n'

    /** 列分隔符同理 */
    const val COL = '\u0001'

    /**
     * 只记最近这么多个。再多也没意义——真到内存告急时早就该杀了。
     *
     * 同时也是**日志大小上限**：每次 `touch` 都会把整张表重写一遍，
     * 没有上限的话这个字符串会随使用时长无限增长。
     */
    const val MAX_ENTRIES = 32

    /**
     * 把某个应用顶到队首。
     *
     * 先按 (spaceId, packageName) 去重再插入，否则同一个应用被反复打开时
     * 会在表里堆出多条——回收时它"占掉好几个名额"，把别人挤出去。
     */
    fun touch(
        current: List<WarmPool.Entry>,
        spaceId: String,
        packageName: String,
        now: Long,
    ): List<WarmPool.Entry> {
        if (spaceId.isBlank() || packageName.isBlank()) return current
        val rest = current.filterNot { it.spaceId == spaceId && it.packageName == packageName }
        return (listOf(WarmPool.Entry(spaceId, packageName, now)) + rest).take(MAX_ENTRIES)
    }

    /** 卸载应用时清理。留着会变成永远不会命中的幽灵条目，白占名额 */
    fun forget(
        current: List<WarmPool.Entry>,
        spaceId: String,
        packageName: String,
    ): List<WarmPool.Entry> =
        current.filterNot { it.spaceId == spaceId && it.packageName == packageName }

    /** 删除空间时清理。 */
    fun forgetSpace(current: List<WarmPool.Entry>, spaceId: String): List<WarmPool.Entry> =
        current.filterNot { it.spaceId == spaceId }

    fun encode(entries: List<WarmPool.Entry>): String =
        entries.joinToString(ROW.toString()) { "${it.spaceId}$COL${it.packageName}$COL${it.ts}" }

    /**
     * 解析顺序表。
     *
     * 单行解析失败**只丢那一行**，不让整张表归零——半行写入（进程被杀）或
     * 格式演进都可能产生坏行，此时保住其余条目远比"要么全对要么全空"有用。
     */
    fun decode(raw: String?): List<WarmPool.Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(ROW).mapNotNull { line ->
            val parts = line.split(COL)
            if (parts.size != 3) return@mapNotNull null
            val ts = parts[2].toLongOrNull() ?: return@mapNotNull null
            if (parts[0].isBlank() || parts[1].isBlank()) return@mapNotNull null
            WarmPool.Entry(parts[0], parts[1], ts)
        }
    }
}

/**
 * 「最近使用」的有序表（LRU）。
 *
 * 保活服务靠它回答一个非常具体的问题：**内存吃紧时，先牺牲谁？**
 *
 * 答案不是「最早装的」，也不是「包名最小的」，而是**最久没被打开过的**。
 * 所以每次用户从空间里启动一个应用，就在这里把它顶到队首；回收时从队尾开刀。
 *
 * ## 为什么落在 SharedPreferences 而不是内存
 *
 * 保活服务本身也会被系统杀掉然后重建。如果顺序表只存在内存里，重建后它
 * 是空的，第一次内存告急就可能把用户刚打开的应用当成"从没用过"给回收掉
 * ——这恰好是最伤体验的一种误判。顺序表的成本只有几十字节，落盘更划算。
 *
 * 存的是 `spaceId` + `packageName` 而不是进程名：同一个包在空间里可能有多个
 * 进程（`:remote`、`:push` 等），用户心智里它们是"一个应用"。
 *
 * 顺序与格式的实现在 [WarmOrder]，这里只做读写。
 */
object WarmPool {

    private const val PREFS = "dooble_keeper"
    private const val KEY_ORDER = "warm_order"

    data class Entry(val spaceId: String, val packageName: String, val ts: Long)

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 把某个应用顶到队首。启动应用、进入空间时调用 */
    @Synchronized
    fun touch(ctx: Context, spaceId: String, packageName: String) {
        if (spaceId.isBlank() || packageName.isBlank()) return
        write(ctx, WarmOrder.touch(order(ctx), spaceId, packageName, System.currentTimeMillis()))
    }

    /** 队首 = 最近使用。下标越小越"该留" */
    @Synchronized
    fun order(ctx: Context): List<Entry> =
        WarmOrder.decode(prefs(ctx).getString(KEY_ORDER, null))

    /** 卸载应用 / 删除空间时同步清理，否则会留下永远不会命中的幽灵条目 */
    @Synchronized
    fun forget(ctx: Context, spaceId: String, packageName: String) {
        write(ctx, WarmOrder.forget(order(ctx), spaceId, packageName))
    }

    @Synchronized
    fun forgetSpace(ctx: Context, spaceId: String) {
        write(ctx, WarmOrder.forgetSpace(order(ctx), spaceId))
    }

    @Synchronized
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_ORDER).apply()
    }

    private fun write(ctx: Context, entries: List<Entry>) {
        prefs(ctx).edit().putString(KEY_ORDER, WarmOrder.encode(entries)).apply()
    }
}
