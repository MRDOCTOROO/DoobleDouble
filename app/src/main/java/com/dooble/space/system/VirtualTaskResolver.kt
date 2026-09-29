package com.dooble.space.system

/**
 * 「这个最近任务卡片属于哪个虚拟应用」的**纯逻辑**。
 *
 * ## 为什么要独立成一个对象
 *
 * 判定依赖 Bcore 写进 stub intent 的 extra，而读 extra 要 `Intent`——
 * JVM 单测里 `Intent` 是 `Stub!` 桩（`putExtra` 是空操作、`getParcelableExtra`
 * 恒返回 null），规则完全测不到。把"读"与"判"拆开之后，判定规则可以被钉死。
 *
 * ## 协议从哪来
 *
 * 见 Bcore 的 `proxy/record/ProxyActivityRecord.saveStub`：
 * ```
 * shadow.putExtra("_B_|_user_id_", userId);
 * shadow.putExtra("_B_|_activity_info_", activityInfo);
 * shadow.putExtra("_B_|_target_", target);
 * ```
 * 这三个字面量是**容器与宿主之间的协议**，改任意一边都要同步另一边。
 * 本对象是宿主侧唯一的持有处，不要再在别处硬编码。
 */
object VirtualTaskResolver {

    /** 虚拟 userId，等于空间记录里的 `bcoreUserId`。见 `ProxyActivityRecord.saveStub`。 */
    const val EXTRA_USER_ID = "_B_|_user_id_"

    /** 虚拟 `ActivityInfo`，其 `packageName` 就是空间内的包名。 */
    const val EXTRA_ACTIVITY_INFO = "_B_|_activity_info_"

    /** 用户原始 intent，其 `component.packageName` 也是空间内的包名。 */
    const val EXTRA_TARGET = "_B_|_target_"

    /**
     * 从 stub intent 反查出来的虚拟应用身份。
     *
     * @param userId 空间对应的虚拟 userId；`null` 表示 stub intent 里没有这个 extra
     *               （协议早期版本、或该 ROM 把 extra 洗掉了），**不等于** 0。
     */
    data class VirtualTask(val packageName: String, val userId: Int?)

    /**
     * 组装虚拟应用身份。两个来源都拿不到就返回 null ——
     * 说明这个任务不是容器建的（比如宿主自己的 MainActivity 任务），必须原样放过。
     *
     * 优先用 [EXTRA_ACTIVITY_INFO]：它是 Bcore 按虚拟 APK 的清单构造的，
     * `packageName` 不受用户启动方式影响。用户原始 intent 只是兜底 ——
     * 从快捷方式、通知、`market://` 链起时它可能指向别的包。
     */
    fun resolve(
        activityInfoPackage: String?,
        targetPackage: String?,
        userId: Int?,
    ): VirtualTask? {
        val pkg = activityInfoPackage?.takeIf { it.isNotEmpty() }
            ?: targetPackage?.takeIf { it.isNotEmpty() }
            ?: return null
        return VirtualTask(pkg, userId)
    }

    /**
     * 这张卡片是否属于「[packageName] 在 [userId] 这个空间里的实例」。
     *
     * ## 为什么"信息不全时宁可多清"
     *
     * 两种失败代价不对称：
     *  - **多清一张**：用户少了一张卡片，重开应用即可恢复，几乎无感；
     *  - **漏清一张**：用户点了"结束运行"，卡片还在，再点又能把应用拉起来 ——
     *    会直接认定这个功能是坏的。
     *
     * 所以只要 [userId] 为 null（调用方不关心空间）或 `task.userId` 为 null
     * （协议信息缺失），就按"包名相同即命中"处理。只有在两边都有 userId
     * 且确实不等时才放过。
     *
     * @param userId 目标空间的 `bcoreUserId`；`null` 表示不做空间过滤
     */
    fun matches(task: VirtualTask, packageName: String, userId: Int?): Boolean {
        if (task.packageName != packageName) return false
        if (userId == null || task.userId == null) return true
        return task.userId == userId
    }

    /**
     * 把空间的 `bcoreUserId` 转成过滤参数。
     *
     * `<= 0` 表示尚未分配（见 `SpaceStore.Record.bcoreUserId` 的默认值）。
     * 这时**必须传 null 而不是 0** —— 传 0 会变成"只匹配 userId 为 0 的卡片"，
     * 而真实卡片的 userId 是 10000 起的，结果是**一张都清不掉**，
     * 把"多清"的安全兜底反向变成"全漏"。
     */
    fun filterUserId(bcoreUserId: Int): Int? = bcoreUserId.takeIf { it > 0 }
}
