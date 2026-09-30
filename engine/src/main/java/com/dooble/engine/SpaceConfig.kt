package com.dooble.engine

import kotlinx.serialization.Serializable

/**
 * 一个「空间」的配置。空间是 Dooble Double 的一等公民：
 * 每个空间拥有独立的虚拟 Android 环境（独立的 Application、AccountManager、
 * SharedPreferences、文件目录、进程与 uid 视图）。
 */
@Serializable
data class SpaceConfig(
    /** 空间唯一 id，同时作为磁盘目录名与虚拟 userId 派生种子 */
    val id: String,
    /** 展示名，例如「工作」「游戏 2 号」 */
    val name: String,
    /** 空间主题色序号，UI 用 */
    val accent: Int = 0,
    val createdAt: Long = 0L,
    /** 是否在该空间内启用谷歌环境（microG 栈） */
    val googleEnvironment: Boolean = true,
    /** 设备信息伪装（很多谷歌应用会校验设备指纹） */
    val deviceSpoof: DeviceSpoofProfile = DeviceSpoofProfile(),
    /** 虚拟定位，null 表示不伪装 */
    val fakeLocation: GeoPoint? = null,
    /** 是否开启存储重定向（把 /sdcard 写入落到空间私有目录） */
    val redirectStorage: Boolean = true,
    /** 是否隔离网络（独立 VPN fd / 独立 Cookie 空间） */
    val isolateNetwork: Boolean = false,
    /** 是否允许该空间内的应用读取宿主通讯录/短信（默认关闭，隐私优先） */
    val exposeHostPii: Boolean = false,
) {
    companion object {
        fun new(name: String, accent: Int = 0): SpaceConfig {
            val id = "sp_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            return SpaceConfig(id = id, name = name, accent = accent, createdAt = System.currentTimeMillis())
        }
    }
}

/**
 * 设备指纹伪装档位。
 * 默认使用 Pixel 8 Pro (husky) 的官方指纹——这是目前通过 GMS 设备认证
 * （SafetyNet/Play Integrity 基础档）成功率最高的公开指纹之一。
 */
@Serializable
data class DeviceSpoofProfile(
    val enabled: Boolean = true,
    val brand: String = "google",
    val model: String = "Pixel 8 Pro",
    val manufacturer: String = "Google",
    val device: String = "husky",
    val product: String = "husky",
    val board: String = "husky",
    val hardware: String = "husky",
    val fingerprint: String = "google/husky/husky:14/AP1A.240505.005/11688946:user/release-keys",
    val sdkInt: Int = 34,
    val serial: String = "DBSERIAL0000000",
) {
    companion object {
        val PIXEL_8_PRO = DeviceSpoofProfile()
        val PIXEL_8 = DeviceSpoofProfile(
            model = "Pixel 8",
            device = "shiba",
            product = "shiba",
            board = "shiba",
            hardware = "shiba",
            fingerprint = "google/shiba/shiba:14/AP1A.240505.005/11688946:user/release-keys",
        )
        val PIXEL_7 = DeviceSpoofProfile(
            model = "Pixel 7",
            device = "panther",
            product = "panther",
            board = "panther",
            hardware = "panther",
            fingerprint = "google/panther/panther:14/AP1A.240505.005/11688946:user/release-keys",
        )
        val PIXEL_6A = DeviceSpoofProfile(
            model = "Pixel 6a",
            device = "bluejay",
            product = "bluejay",
            board = "bluejay",
            hardware = "bluejay",
            fingerprint = "google/bluejay/bluejay:13/TQ3A.230901.001/10778935:user/release-keys",
            sdkInt = 33,
        )
        val XIAOMI_14 = DeviceSpoofProfile(
            brand = "Xiaomi",
            model = "23127PN0CC",
            manufacturer = "Xiaomi",
            device = "houji",
            product = "houji",
            board = "kalama",
            hardware = "qcom",
            fingerprint = "Xiaomi/houji/houji:14/UKQ1.231003.002/V816.0.5.0.UNCCNXM:user/release-keys",
            sdkInt = 34,
        )
    }
}

@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double)
