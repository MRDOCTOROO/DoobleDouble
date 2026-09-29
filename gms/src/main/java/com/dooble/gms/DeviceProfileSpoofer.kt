package com.dooble.gms

import android.content.Context
import com.dooble.engine.DeviceSpoofProfile
import com.dooble.engine.VirtualEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 设备指纹伪装。
 *
 * GMS 侧会读取 `Build.BRAND / MODEL / DEVICE / FINGERPRINT` 等字段来判定
 * 设备是否"通过认证"。未认证设备的表现是：Google Play 能开但无法登录、
 * 或提示 "This device isn't Play Protect certified"。
 *
 * 因此空间必须能把一套**完整且自洽**的指纹（brand/manufacturer/device/
 * product/board/hardware/fingerprint/sdkInt 必须互相对得上）注入容器内的
 * `android.os.Build` 静态字段。
 *
 * 注入点：容器 native 层在目标进程 `fork` 后、`Application.onCreate` 之前，
 * 通过 hook `android.os.Build` 的类初始化，或直接改 `SystemProperties` 的
 * `ro.product.*` 读取路径。真引擎（Bcore）走的是前者。
 */
class DeviceProfileSpoofer(
    private val context: Context,
    private val engine: VirtualEngine,
) {

    @Serializable
    data class ProfileFile(
        val profile: DeviceSpoofProfile,
        /** 需要覆盖的系统属性（key → value），native 层逐条 apply */
        val systemProperties: Map<String, String>,
        /** 需要覆盖的 Build 静态字段 */
        val buildFields: Map<String, String>,
    )

    fun apply(spaceId: String, profile: DeviceSpoofProfile): File {
        val dir = spaceDir(spaceId).resolve(".dooble")
        dir.mkdirs()

        val file = ProfileFile(
            profile = profile,
            systemProperties = buildSystemProperties(profile),
            buildFields = buildFields(profile),
        )
        val out = dir.resolve("device_profile.json")
        out.writeText(JSON.encodeToString(ProfileFile.serializer(), file))
        return out
    }

    fun profileOf(spaceId: String): ProfileFile? = runCatching {
        val f = spaceDir(spaceId).resolve(".dooble/device_profile.json")
        if (!f.exists()) null else JSON.decodeFromString<ProfileFile>(f.readText())
    }.getOrNull()

    /**
     * 生成 `ro.product.*` 系列属性。这一组必须**整体一致**，
     * 只改 model 不改 device 反而更容易被判定为异常设备。
     */
    private fun buildSystemProperties(p: DeviceSpoofProfile): Map<String, String> = linkedMapOf(
        "ro.product.brand" to p.brand,
        "ro.product.manufacturer" to p.manufacturer,
        "ro.product.model" to p.model,
        "ro.product.name" to p.product,
        "ro.product.device" to p.device,
        "ro.product.board" to p.board,
        "ro.product.hardware" to p.hardware,
        "ro.build.fingerprint" to p.fingerprint,
        "ro.build.product" to p.product,
        "ro.build.description" to "${p.product}-user ${p.sdkInt} ${p.fingerprint.substringAfterLast('/')}",
        "ro.serialno" to p.serial,
        "ro.boot.serialno" to p.serial,
    )

    private fun buildFields(p: DeviceSpoofProfile): Map<String, String> = linkedMapOf(
        "BRAND" to p.brand,
        "MANUFACTURER" to p.manufacturer,
        "MODEL" to p.model,
        "PRODUCT" to p.product,
        "DEVICE" to p.device,
        "BOARD" to p.board,
        "HARDWARE" to p.hardware,
        "FINGERPRINT" to p.fingerprint,
        "DISPLAY" to p.fingerprint.substringAfter('/').substringBefore('/'),
        "ID" to p.fingerprint.substringAfterLast('/').substringBefore(':'),
        "SDK_INT" to p.sdkInt.toString(),
    )

    private fun spaceDir(spaceId: String): File =
        File(context.filesDir, "dooble/virtual/$spaceId")

    companion object {
        private val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
