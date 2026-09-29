package com.dooble.gms

import android.content.Context
import android.content.pm.PackageManager
import com.dooble.engine.VirtualEngine

enum class CheckStatus { PASS, WARN, FAIL, SKIP }

data class CheckItem(
    val group: String,
    val title: String,
    val status: CheckStatus,
    val detail: String,
)

data class SelfCheckReport(val spaceId: String, val items: List<CheckItem>) {
    val failures: List<CheckItem> get() = items.filter { it.status == CheckStatus.FAIL }
    val warnings: List<CheckItem> get() = items.filter { it.status == CheckStatus.WARN }

    val overall: CheckStatus
        get() = when {
            failures.isNotEmpty() -> CheckStatus.FAIL
            warnings.isNotEmpty() -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }

    val score: Int
        get() {
            if (items.isEmpty()) return 0
            var weight = 0
            for (item in items) {
                weight += when (item.status) {
                    CheckStatus.PASS -> 100
                    CheckStatus.WARN -> 50
                    CheckStatus.SKIP -> 100
                    CheckStatus.FAIL -> 0
                }
            }
            return weight / items.size
        }
}

/**
 * 谷歌环境自检。
 *
 * 参考 microG 官方 Self-Check 的分组方式，但针对"容器"场景做了调整：
 * microG 查的是 ROM 是否支持签名伪造，我们查的是**容器**是否支持；
 * microG 查系统里装没装 GmsCore，我们查的是**这个空间里**装没装。
 *
 * 面板上任何一项 FAIL，用户都不应该期望 Google 登录能成功——
 * 所以这个面板不是装饰，是排障入口。
 */
class GmsSelfCheck(
    private val context: Context,
    private val engine: VirtualEngine,
) {

    private val spoof = GmsSignatureSpoof(context, engine)
    private val provisioner = GmsProvisioner(context, engine)

    fun run(spaceId: String): SelfCheckReport {
        val items = mutableListOf<CheckItem>()

        // ---- 组 1：容器能力 ----
        val cap = engine.capability
        items += CheckItem(
            "容器能力", "虚拟化引擎",
            if (engine.name == "sandbox") CheckStatus.WARN else CheckStatus.PASS,
            if (engine.name == "sandbox")
                "当前运行在降级沙箱模式（SandboxEngine），只能管理空间元数据，无法运行未安装的 APK。接入 Bcore 后自动切换。"
            else "引擎：${engine.name}，nativeHook=${cap.nativeHook}，64 位=${cap.supports64Bit}",
        )
        items += CheckItem(
            "容器能力", "签名伪造支持",
            if (cap.supportsSignatureSpoof) CheckStatus.PASS else CheckStatus.FAIL,
            if (cap.supportsSignatureSpoof)
                "容器可在 PMS 层伪造包签名，microG 能通过应用的签名校验。"
            else "引擎未 hook PackageManagerService，microG 会被应用判定为伪造而拒绝。这是登录失败的根因之一。",
        )
        items += CheckItem(
            "容器能力", "IO 重定向",
            if (cap.nativeHook) CheckStatus.PASS else CheckStatus.SKIP,
            if (cap.nativeHook) "已启用 native PLT hook，/data/data/<pkg> 与 /sdcard 写入会落到空间私有目录。"
            else "未启用；部分写死绝对路径的应用可能读写到宿主数据。",
        )

        // ---- 组 2：包安装 ----
        val installedPkgs = engine.listApps(spaceId).map { it.packageName }.toSet()
        MicroGComponent.ALL.forEach { comp ->
            val present = comp.packageName in installedPkgs
            val status = when {
                present -> CheckStatus.PASS
                comp.required -> CheckStatus.FAIL
                else -> CheckStatus.SKIP
            }
            items += CheckItem(
                "组件安装", comp.packageName, status,
                if (present) "已装入空间（${comp.description}）"
                else if (comp.required) "缺失，必需组件。${comp.description}"
                else "未安装（可选组件，仅老应用需要）",
            )
        }

        // ---- 组 3：签名伪造 ----
        //
        // 这一组必须能把四种情况分开，因为它们的排障方向完全不同：
        //   1. 明确关闭               -> 不是问题，给 SKIP（且不影响主链路）
        //   2. 开了但证书没找到       -> 配置问题，给 WARN
        //   3. 开了、证书在、链路不通 -> 引擎问题，给 FAIL
        //   4. 链路通但证书不是官方的 -> 只能验证链路，给 WARN
        //
        // 早期版本把「签名伪造」一律当 FAIL 处理，结果本地测试时满屏红字，
        // 用户以为整个环境都坏了——而实际上登录、下载、多开全都是好的。
        val spoofMode = spoof.currentMode(spaceId)
        val cert = spoof.certInfoOf(spaceId)

        items += CheckItem(
            "签名伪造", "工作模式",
            when {
                spoofMode == SpoofMode.OFF -> CheckStatus.SKIP
                cert == null -> CheckStatus.WARN
                else -> CheckStatus.PASS
            },
            when {
                spoofMode == SpoofMode.OFF ->
                    "已关闭。这不影响登录 Google 账号、下载安装应用、多开——" +
                        "microG 与 Aurora Store 都不依赖签名伪造。" +
                        "只有主动校验 GMS 签名的第三方应用会报「需要 Google Play 服务」。"
                cert == null ->
                    "${spoofMode.label}：证书未就绪，容器不会伪造任何签名。"
                else ->
                    "${spoofMode.label} · 证书来源：${cert.source}"
            },
        )

        if (spoofMode != SpoofMode.OFF) {
            cert?.let {
                val official = it.sha1 == GmsSignatureSpoof.GMS_SHA1
                items += CheckItem(
                    "签名伪造", "证书指纹",
                    if (official) CheckStatus.PASS else CheckStatus.WARN,
                    if (official) "SHA-1 ${it.sha1} —— 与 Google 官方签名一致，microG 会认可。"
                    else "SHA-1 ${it.sha1}，**不是** Google 官方签名（官方为 ${GmsSignatureSpoof.GMS_SHA1}）。" +
                        "容器会如实返回这张证书，但 microG 的签名校验不会通过。" +
                        if (spoofMode == SpoofMode.TEST)
                            "这正是「本地测试证书」的预期表现：它只用于验证伪造链路是否打通。"
                        else "请确认你提供的证书来自合法的 GMS APK。"
                )
            }

            MicroGComponent.REQUIRED.forEach { comp ->
                val result = spoof.verifyDetailed(spaceId, comp.packageName)
                items += CheckItem(
                    "签名伪造", "伪造链路 · ${comp.packageName}",
                    if (result.chainOk) CheckStatus.PASS else CheckStatus.FAIL,
                    when {
                        result.chainOk && result.isOfficial ->
                            "容器返回 Google 官方签名（${result.actualSha1}），应用侧校验会通过。"
                        result.chainOk ->
                            "容器返回值已被替换（${result.actualSha1}），说明伪造链路是通的；" +
                                "只是证书不是官方的，microG 不会认可。"
                        result.actualSha1 == null ->
                            "查询不到该包。确认它已经装进这个空间。"
                        else ->
                            "容器返回的仍是 ${result.actualSha1}，与策略期望的 ${result.expectedSha1} 不一致——" +
                                "伪造没有生效。检查 Bcore 是否已按 tools/build-bcore.sh 打过补丁并重建。"
                    },
                )
            }
        }

        // ---- 组 4：设备指纹 ----
        val profile = DeviceProfileSpoofer(context, engine).profileOf(spaceId)
        items += CheckItem(
            "设备指纹", "指纹伪装已配置",
            if (profile != null) CheckStatus.PASS else CheckStatus.WARN,
            profile?.let {
                "对外呈现：${it.profile.manufacturer} ${it.profile.model}（${it.profile.device}），sdk=${it.profile.sdkInt}"
            } ?: "未配置指纹伪装。部分 Google 应用会因设备未认证而拒绝登录。",
        )

        // ---- 组 5：运行时权限 ----
        items += CheckItem(
            "运行时权限", "网络访问",
            if (hasPermission("android.permission.INTERNET")) CheckStatus.PASS else CheckStatus.FAIL,
            "容器内应用需要网络访问权限才能连上 Google 服务。",
        )
        items += CheckItem(
            "运行时权限", "账号读取（GET_ACCOUNTS）",
            if (hasPermission("android.permission.GET_ACCOUNTS")) CheckStatus.WARN else CheckStatus.WARN,
            "Android 8 起 GET_ACCOUNTS 不再需要用户授权，但部分老应用仍会查询。",
        )

        // ---- 组 6：账号 ----
        val accounts = GoogleAccountBridge(context, engine).accounts(spaceId)
        items += CheckItem(
            "谷歌账号", "已登录账号",
            if (accounts.isNotEmpty()) CheckStatus.PASS else CheckStatus.WARN,
            if (accounts.isNotEmpty()) accounts.joinToString { it.name }
            else "该空间尚未登录 Google 账号。安装完环境后可在空间详情页发起登录。",
        )

        return SelfCheckReport(spaceId, items)
    }

    private fun hasPermission(name: String): Boolean =
        context.packageManager.checkPermission(name, context.packageName) == PackageManager.PERMISSION_GRANTED

    /**
     * 环境是否达到"可以尝试登录 Google 账号"的门槛。
     *
     * 这里**刻意不包含**「签名伪造」：登录走的是 microG 自己的 OAuth 流程，
     * 与容器对外呈现的 GMS 签名无关。把它算作门槛的后果是——一个明确关闭了
     * 签名伪造的本地测试环境会被判定为"不能登录"，而实际上它能正常登录。
     */
    fun canAttemptSignIn(spaceId: String): Boolean {
        val report = run(spaceId)
        return report.items.none {
            it.status == CheckStatus.FAIL && it.group in setOf("容器能力", "组件安装")
        }
    }

    fun isProvisioned(spaceId: String): Boolean = provisioner.isProvisioned(spaceId)
}
