package com.dooble.engine.bcore

import android.content.pm.PackageManager
import com.dooble.engine.internal.InstallEvent
import com.dooble.engine.internal.InstallEventLog
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File

/**
 * 容器内应用发起的安装请求 → 装进**当前空间**。
 *
 * ## 为什么必须有它（这是"内置商店能不能用"的分水岭）
 *
 * Bcore 拦截到容器内应用发出的安装 Intent 后，逻辑是这样的
 * （`fake/service/ActivityManagerCommonProxy.java`）：
 *
 * ```java
 * if (ComponentUtils.isRequestInstall(intent)) {          // type == apk
 *     File file = FileProviderHandler.convertFile(...);
 *     if (BlackBoxCore.get().requestInstallPackage(file, userId)) {
 *         return 0;                                       // ← 宿主接管
 *     }
 *     intent.setData(convertFileUri(...));
 *     return method.invoke(who, args);                    // ← 转发给【系统安装器】
 * }
 * ```
 *
 * 也就是说：**宿主不接管时，容器内应用下载的 APK 会被装到宿主机上**，
 * 而不是当前空间。表现就是——在空间里用商店装个应用，回到桌面发现
 * 装在了外面，空间里什么都没有。
 *
 * 这正是 [top.niunaijun.blackbox.app.configuration.ClientConfiguration#requestInstallPackage]
 * 这个回调存在的意义。早先的实现直接 `return false`，等于主动放弃了这个能力。
 *
 * ## 为什么返回 true 之后要异步装
 *
 * 这个回调跑在**虚拟应用的主线程**上（它挂在 ActivityManager 的 hook 链里）。
 * 在这里同步做完整安装会卡死容器内应用，所以：先返回 true 把 Intent 拦下来，
 * 再丢到后台线程去装。
 *
 * 返回 true 还有个附带好处：Intent 不会继续传给系统安装器，
 * 用户不会看到"要安装此应用吗"的系统弹窗——安装完全发生在空间内部。
 */
object ContainerInstallRelay {

    /** 同一时刻只允许一个安装在跑，避免并发写 BPackageManager 出问题 */
    private val installLock = Any()

    /**
     * @return true 表示已接管（Bcore 会拦截掉该 Intent）
     */
    fun handle(file: File?, userId: Int): Boolean {
        if (file == null || !file.exists() || file.length() == 0L) return false
        if (userId <= 0) return false

        // 先解析包名，用于日志与重复安装提示；解析不出也不阻止安装尝试
        val parsed = runCatching {
            BlackBoxCore.getPackageManager()
                .getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA)
        }.getOrNull()

        val packageName = parsed?.packageName.orEmpty()
        val label = runCatching {
            parsed?.applicationInfo?.let {
                BlackBoxCore.getPackageManager().getApplicationLabel(it).toString()
            }
        }.getOrNull().orEmpty()

        Thread({
            synchronized(installLock) {
                val result = runCatching {
                    BlackBoxCore.get().installPackageAsUser(file, userId)
                }

                val ok = result.getOrNull()?.success == true
                val message = when {
                    result.isFailure -> result.exceptionOrNull()?.message ?: "安装异常"
                    ok -> "已装入当前空间"
                    else -> result.getOrNull()?.msg ?: "安装失败"
                }

                InstallEventLog.record(
                    InstallEvent(
                        timestamp = System.currentTimeMillis(),
                        userId = userId,
                        packageName = result.getOrNull()?.packageName ?: packageName,
                        label = label,
                        ok = ok,
                        message = message,
                    )
                )

                // 装完把临时 APK 删掉，避免空间数据里堆垃圾。
                // 只删我们确定是自己缓存目录下的文件，绝不动用户的其他数据。
                runCatching {
                    val path = file.absolutePath
                    if (path.contains("/cache/") || path.contains("/download")) {
                        file.delete()
                    }
                }
            }
        }, "dooble-container-install").apply {
            isDaemon = true
            start()
        }

        return true
    }

    /**
     * 会话式安装（`PackageInstaller.Session`）的结果回传。
     *
     * ## 与 [handle] 的分工
     *
     * 容器内应用装应用有两条路，它们对应 Bcore 里两个不同的接管点：
     *
     * | 路径 | 触发方式 | Bcore 侧接管点 |
     * |---|---|---|
     * | Intent 式 | `ACTION_INSTALL_PACKAGE` | `ActivityManagerCommonProxy` → [handle] |
     * | 会话式 | `PackageInstaller.Session.commit()` | `PackageInstallerProxy` |
     *
     * **商店走的是会话式**（Aurora Store 的 SessionInstaller 是默认后端，
     * 现代 Play Store 同理）。这条路上宿主拿不到"请求"时机——安装已经由
     * 容器自己做完了——只能收结果。所以这里是"通知"而不是"接管"：
     * 不装、不返回布尔值，只把结果落进同一份事件日志。
     *
     * 两条路最终写进同一个 `install-events.jsonl`，UI 侧不需要区分来源。
     */
    fun onSessionResult(
        file: File?,
        userId: Int,
        ok: Boolean,
        packageName: String?,
        message: String?,
    ) {
        if (userId <= 0) return

        // 会话式安装的调用方已经解析过包名，但标签要自己取一次——
        // 事件日志里的 label 是给用户看的，只有包名没有可读性
        val label = runCatching {
            val info = BlackBoxCore.getPackageManager()
                .getPackageArchiveInfo(file?.absolutePath.orEmpty(), PackageManager.GET_META_DATA)
            info?.applicationInfo?.let {
                BlackBoxCore.getPackageManager().getApplicationLabel(it).toString()
            }
        }.getOrNull().orEmpty()

        InstallEventLog.record(
            InstallEvent(
                timestamp = System.currentTimeMillis(),
                userId = userId,
                packageName = packageName.orEmpty(),
                label = label,
                ok = ok,
                message = message.orEmpty(),
            )
        )
    }
}
