package com.dooble.engine.internal

import java.io.File

/**
 * Bcore 虚拟化引擎的落盘布局。
 *
 * ## 为什么单独抽一个文件
 *
 * 这里**猜错过一次，而且错得很安静**：磁盘占用统计曾经硬编码
 * `<filesDir>/dooble/virtual/<spaceId>`，看着很合理——BcoreEngine 确实会
 * 创建这个目录。但 Bcore 的真实数据根本不在那儿，而在它自己的虚拟根
 * `/data/data/<宿主包名>/blackbox/` 下（`BEnvironment` 的 `sVirtualRoot`），
 * 两者毫无关系。实测 `files/dooble/virtual/<spaceId>/` 只有 **1485 字节**，
 * 而真实占用是 **223MB**。
 *
 * 错的结果是恒返回 `0`，而 `0` 不抛异常、不打日志——UI 上就是安静地显示 0。
 * 抽成只依赖 [java.io.File] 的纯函数之后，可以用单元测试把每个路径钉死，
 * 不用再靠"读一遍源码觉得对"。
 *
 * ## 布局全貌（实测确认）
 *
 * ```
 * /data/data/<宿主包名>/blackbox/
 *   data/app/<包名>/              ← APK 本体。**按包名组织，没有 userId**
 *   data/user/<userId>/<包名>/    ← 应用数据。按 userId 组织
 *   data/user_de/<userId>/<包名>/ ← 设备加密存储
 *   cache/  system/  profiles/
 *
 * <外部私有目录>/blackbox/
 *   storage/emulated/<userId>/    ← 存储重定向后的 /sdcard
 * ```
 *
 * 注意 **APK 是按包名存的**，同一应用装进两个空间只会存一份。见 [spaceDirs]。
 */
object BcoreStorageLayout {

    /** Bcore 虚拟根的名字。来自 `BEnvironment.sVirtualRoot`。 */
    const val ROOT_NAME = "blackbox"

    /**
     * Bcore 的内部虚拟根。
     *
     * `BEnvironment` 里写的是 `new File(getCacheDir().getParent(), "blackbox")`，
     * 而 `getCacheDir()` 是 `<dataDir>/cache`，所以等价于 `<dataDir>/blackbox`。
     */
    fun internalRoot(dataDir: File): File = File(dataDir, ROOT_NAME)

    /**
     * Bcore 的外部虚拟根。
     *
     * `BEnvironment` 里是 `getExternalFilesDir("blackbox")`。外部存储可能不可用
     * （未挂载 / 权限问题），此时返回 null——调用方要能容忍。
     */
    fun externalRoot(externalFilesDir: File?): File? =
        externalFilesDir?.let { File(it, ROOT_NAME) }

    /** APK 本体所在目录。**按包名组织，不含 userId。** */
    fun appDir(dataDir: File, packageName: String): File =
        File(internalRoot(dataDir), "data/app/$packageName")

    /** 应用数据目录，按 userId 组织。 */
    fun userDir(dataDir: File, userId: Int): File =
        File(internalRoot(dataDir), "data/user/$userId")

    /** 设备加密存储目录，按 userId 组织。 */
    fun userDeDir(dataDir: File, userId: Int): File =
        File(internalRoot(dataDir), "data/user_de/$userId")

    /** 存储重定向后的 `/sdcard`，按 userId 组织。外部存储不可用时返回 null。 */
    fun externalUserDir(externalFilesDir: File?, userId: Int): File? =
        externalRoot(externalFilesDir)?.let { File(it, "storage/emulated/$userId") }

    // ------------------------------------------------------------------
    // Dooble 自己的空间私有元数据（不是 Bcore 的布局，但同属"落盘契约"）
    //
    // 下面这些写在 Dooble 的空间目录 `<filesDir>/dooble/virtual/<spaceId>/` 下：
    // gms 模块是写入方，容器服务进程（BcoreSignatureSpoofer 等）是读取方。
    // 路径必须**全部**从本文件取，禁止任何一端就地拼字符串 ——
    // 曾经读方比写方多拼了一段 `data/`，策略文件永远找不到，
    // 签名伪造整条链路静默失效且无任何报错（用户看到的是"开了没效果"）。
    // ------------------------------------------------------------------

    /** Dooble 引擎自身的目录名：`<filesDir>/dooble` */
    const val ENGINE_ROOT_NAME = "dooble"

    /** 空间私有元数据目录名（相对空间目录） */
    const val META_DIR_NAME = ".dooble"

    /**
     * 签名伪造策略文件，相对空间目录的路径。
     * 写入方：`gms.GmsSignatureSpoof.apply`；读取方：`bcore.BcoreSignatureSpoofer`。
     */
    const val SPOOF_POLICY_RELATIVE = "$META_DIR_NAME/spoof_policy.json"

    /** 设备指纹伪装配置文件，相对空间目录的路径（写入方 `gms.DeviceProfileSpoofer`） */
    const val DEVICE_PROFILE_RELATIVE = "$META_DIR_NAME/device_profile.json"

    /**
     * 签名伪造策略文件的**绝对落盘位置**——读写两端唯一允许的取法。
     *
     * @param filesDir 宿主 `Context.filesDir`（读取方手里若只有 `<filesDir>/dooble`
     *   这一层，请从 BcoreEngine 一路把 filesDir 传下来，不要自己再拼一层）
     */
    fun spoofPolicyFile(filesDir: File, spaceId: String): File =
        File(virtualSpaceDir(filesDir, spaceId), SPOOF_POLICY_RELATIVE)

    /** Dooble 的空间目录：`<filesDir>/dooble/virtual/<spaceId>` */
    fun virtualSpaceDir(filesDir: File, spaceId: String): File =
        File(File(filesDir, ENGINE_ROOT_NAME), "virtual/$spaceId")

    /**
     * 一个空间实际占用的全部目录。
     *
     * ## 关于 APK 会被重复计入
     *
     * [appDir] 按包名组织，同一应用装进两个空间**磁盘上只有一份**。这里按
     * "该空间装了哪些包"归集，所以同名应用出现在多个空间时会被重复计入，
     * 界面顶部的合计可能略高于实际占用。
     *
     * 这是有意的取舍：改成只统计 `data/user/<userId>/` 的话数字会小两个数量级
     * （实测 1.4MB vs 223MB），用户看到"装了 8 个应用只占 1.4MB"只会更困惑。
     * 对一个展示用指标来说，"偏大但量级正确"远好过"精确但恒为 0"。
     *
     * @param userId 空间的 `bcoreUserId`。`<= 0` 表示尚未分配，返回空列表
     *   ——宿主自己占用 userId 0，不能把它的数据算到某个空间头上。
     */
    fun spaceDirs(
        dataDir: File,
        externalFilesDir: File?,
        userId: Int,
        packages: List<String>,
    ): List<File> {
        if (userId <= 0) return emptyList()
        return buildList {
            add(userDir(dataDir, userId))
            add(userDeDir(dataDir, userId))
            packages.forEach { add(appDir(dataDir, it)) }
            externalUserDir(externalFilesDir, userId)?.let { add(it) }
        }
    }
}
