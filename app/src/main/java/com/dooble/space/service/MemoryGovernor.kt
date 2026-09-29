package com.dooble.space.service

import android.content.ComponentCallbacks2
import com.dooble.engine.RunningProcess

/**
 * 内存压力下的虚拟进程回收决策。
 *
 * ## 为什么要独立成一个纯函数
 *
 * 「杀谁」这件事错一次用户就会明显感知到（切回应用发现重新冷启了），
 * 而它又完全依赖系统回调的时机，真机上很难复现调参。把它做成
 * `(压力档位, 运行中进程, 使用顺序, 钉住的空间) -> 保留/回收清单`
 * 的纯函数，就可以脱离 Android 直接写单元测试穷举各种组合。
 *
 * ## 档位选择：为什么不是教科书上那几个
 *
 * 常见写法是监听 `TRIM_MEMORY_RUNNING_LOW` / `RUNNING_CRITICAL`。但查过
 * `android.jar`（API 35）后会发现：**这两个常量连同 `MODERATE` / `COMPLETE`
 * 都已经被标记为 `@Deprecated`**，只有 `TRIM_MEMORY_UI_HIDDEN` 和
 * `TRIM_MEMORY_BACKGROUND` 还是有效档位。也就是说，在新系统上照抄教科书
 * 会得到一个**永远不会触发**的回收器。
 *
 * 所以这里的主触发点是：
 *
 *  - `TRIM_MEMORY_UI_HIDDEN`（用户刚离开 App）——这是最可靠、也最合适的时机：
 *    用户看不见空间里的应用了，此时瘦身不会有任何观感损失。
 *  - `TRIM_MEMORY_BACKGROUND`（进程已被列入后台待回收名单）——更急一档，
 *    再不主动动手，系统可能会连宿主一起端掉。
 *
 * 废弃的四个档位仍然保留映射，只为兼容旧设备（它们在那里是真的会派发的）。
 *
 * ## 保留预算
 *
 * | 档位 | 含义 | 预算 |
 * |---|---|---|
 * | `UI_HIDDEN` / 手动 | 用户已离开，从容瘦身 | 2 |
 * | `BACKGROUND` | 已被列入待回收，得赶紧 | 1 |
 * | `RUNNING_LOW` / `MODERATE`（旧系统） | 内存偏低 | 2 |
 * | `RUNNING_CRITICAL` / `COMPLETE`（旧系统） | 已经影响前台 | 1 |
 * | 其余 | 只是通知一下，不必动手 | 不处理 |
 *
 * 预算之内按 LRU 保留，预算之外全部回收。
 *
 * ## 「钉住」机制
 *
 * 用户当前正打开着某个空间的详情页，那个空间里的应用是"随时会被点开"的，
 * 回收它等于直接让用户看到冷启动。因此钉住的空间**不参与**预算计算，
 * 它占用的名额从预算里额外扣除，剩下的名额才分给其他空间。
 */
object MemoryGovernor {

    /** 手动触发（设置页按钮）。用一个负数当档位，避免和系统常量撞车 */
    const val LEVEL_MANUAL = -1

    data class Plan(
        /** 保留的进程 */
        val keep: List<RunningProcess>,
        /** 建议回收的进程（已按"最该杀"排在前面） */
        val evict: List<RunningProcess>,
        /** 人类可读的档位说明，用于通知与 UI 提示 */
        val note: String,
    ) {
        val changed: Boolean get() = evict.isNotEmpty()
    }

    /**
     * @param trimLevel    `ComponentCallbacks2` 的档位常量，或 [LEVEL_MANUAL]
     * @param running      引擎报告的当前运行中虚拟进程
     * @param lru          最近使用顺序，队首最新
     * @param pinnedSpaceId 用户当前打开的空间；null 表示没有
     */
    fun plan(
        trimLevel: Int,
        running: List<RunningProcess>,
        lru: List<WarmPool.Entry>,
        pinnedSpaceId: String? = null,
    ): Plan {
        if (running.isEmpty()) return Plan(emptyList(), emptyList(), noteFor(trimLevel))

        val budget = budgetFor(trimLevel)
            ?: return Plan(running, emptyList(), noteFor(trimLevel))

        // 空间 + 包名 → 使用顺序下标。缺失表示"从没被打开过"，排到最后
        val rank = HashMap<Pair<String, String>, Int>(lru.size * 2)
        lru.forEachIndexed { index, entry ->
            rank[entry.spaceId to entry.packageName] = index
        }

        val pinned = running.filter { it.spaceId == pinnedSpaceId }
        val candidates = running.filter { it.spaceId != pinnedSpaceId }

        val ordered = candidates.sortedWith(
            compareBy(
                { rank[it.spaceId to it.packageName] ?: Int.MAX_VALUE },
                { it.spaceId },
                { it.pid },
            )
        )

        // 钉住的进程先占名额；名额不够时宁可超预算也不动它们
        val freeSlots = (budget - pinned.size).coerceAtLeast(0)
        val keep = pinned + ordered.take(freeSlots)
        val evict = ordered.drop(freeSlots)

        return Plan(keep, evict, noteFor(trimLevel))
    }

    /** 返回 null 表示「这一档不需要动手」 */
    private fun budgetFor(trimLevel: Int): Int? = when (trimLevel) {
        LEVEL_MANUAL,
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
        LEVEL_RUNNING_LOW,
        LEVEL_MODERATE,
        -> 2

        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
        LEVEL_RUNNING_CRITICAL,
        LEVEL_COMPLETE,
        -> 1

        else -> null
    }

    fun noteFor(trimLevel: Int): String = when (trimLevel) {
        LEVEL_MANUAL -> "主动回收"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "已切到后台"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "已被列入后台待回收"
        LEVEL_RUNNING_LOW -> "内存偏低"
        LEVEL_MODERATE -> "内存中等压力"
        LEVEL_RUNNING_CRITICAL -> "内存严重不足"
        LEVEL_COMPLETE -> "后台即将被回收"
        else -> "内存回收"
    }

    // ------------------------------------------------------------------
    // 以下四个档位在 API 35 的 android.jar 里已带 @Deprecated 标记，
    // 但在旧设备上仍会被真实派发，所以保留映射并显式压制警告。
    // 刻意不用字面量 10/15/60/80：那样一旦平台调整语义，我们不会有任何提示。
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private const val LEVEL_RUNNING_LOW = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW

    @Suppress("DEPRECATION")
    private const val LEVEL_RUNNING_CRITICAL = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL

    @Suppress("DEPRECATION")
    private const val LEVEL_MODERATE = ComponentCallbacks2.TRIM_MEMORY_MODERATE

    @Suppress("DEPRECATION")
    private const val LEVEL_COMPLETE = ComponentCallbacks2.TRIM_MEMORY_COMPLETE
}
