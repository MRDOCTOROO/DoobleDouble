package com.dooble.gms

import com.dooble.engine.internal.BcoreStorageLayout
import java.io.File

/**
 * microG 首次运行前的预配置:打开「向 Google 注册设备」(checkin)。
 *
 * ## 为什么必须有这一步
 *
 * microG 的 checkin **默认关闭**,而登录流程会先等设备注册完成 —— 开关不开,
 * 登录页永远停在「正在建立到 Google 服务器的连接」(MuMu 实测卡死)。
 * 这个开关藏在 microG 设置的第三层,让用户自己找到并打开太隐蔽;
 * 装配谷歌环境时就地预写 SharedPreferences,赶在 microG 首次运行之前。
 *
 * ## 实现约束
 *
 * - 直接写 `<数据目录>/blackbox/data/user/<uid>/com.google.android.gms/shared_prefs/`
 *   下的 XML(路径由 [BcoreStorageLayout] 给出),键名 `checkin_enable_service`
 *   与 microG 0.3.16 的 `CheckinPreferences` 对应 —— **换 microG 版本时需复核**。
 * - 已存在的配置:已开启 → 不动;用户显式关闭 → 尊重现状;
 *   microG 运行过但从未写过该键 → 在既有配置中补写。
 * - 任何失败都不抛出:这只是锦上添花的预配置,失败时用户仍可手动开启。
 */
internal object MicroGCheckinBootstrap {

    /** microG 的 checkin 开关注册表键(microG 0.3.16 CheckinPreferences) */
    internal const val CHECKIN_KEY = "checkin_enable_service"

    /** Android SharedPreferences 对这个键的标准序列化行(开关两态) */
    internal val KEY_LINE_TRUE = "<boolean name=\"$CHECKIN_KEY\" value=\"true\" />"
    internal val KEY_LINE_FALSE = "<boolean name=\"$CHECKIN_KEY\" value=\"false\" />"

    private const val GMS_DATA_DIR = "com.google.android.gms"
    private const val PREFS_FILE = "com.google.android.gms_preferences.xml"

    /**
     * 确保指定 GMS 数据目录下的 checkin 开关已打开。
     *
     * @param gmsDataDir 容器内 microG 的数据目录
     *   (`BcoreStorageLayout.userDir(dataDir, uid)/com.google.android.gms`)。
     * @return true 表示"开关已处于打开状态"(含本次写入/补写成功)。
     */
    fun ensureEnabled(gmsDataDir: File): Boolean {
        val prefsDir = gmsDataDir.resolve("shared_prefs")
        val prefsFile = prefsDir.resolve(PREFS_FILE)
        if (!prefsFile.exists()) {
            return runCatching {
                prefsDir.mkdirs()
                prefsFile.writeText(checkinPrefsXml())
                true
            }.getOrDefault(false)
        }
        val content = runCatching { prefsFile.readText() }.getOrDefault("") ?: ""
        return when {
            KEY_LINE_TRUE in content -> true
            KEY_LINE_FALSE in content -> false // 用户显式关闭:尊重现状,不覆盖
            "</map>" in content -> runCatching {
                prefsFile.writeText(content.replace("</map>", "$KEY_LINE_TRUE\n</map>"))
                true
            }.getOrDefault(false)
            else -> false // 内容异常,不动,交给用户手动处理
        }
    }

    /** 计算容器内 microG 数据目录并确保 checkin 已开。SandboxEngine(userId=0)返回 false */
    fun ensureEnabled(dataDir: File, userId: Int): Boolean {
        if (userId <= 0) return false
        val gmsDataDir = BcoreStorageLayout.userDir(dataDir, userId).resolve(GMS_DATA_DIR)
        return ensureEnabled(gmsDataDir)
    }

    /** 与 Android SharedPreferences 的落盘格式一致,microG 可直接读 */
    internal fun checkinPrefsXml(): String =
        """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    $KEY_LINE_TRUE
</map>
"""
}
