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

    /**
     * 跑一次完整自检。
     *
     * `suspend`：最后一步要**真的发一次网络请求**（见 [NetworkProbe]）。
     * 这是刻意的 —— 用户点开自检的场景，十有八九是"登录失败"，
     * 而登录失败最常见的原因就是代理没生效。只检查本地配置的"自检"
     * 在这种场景下毫无价值。
     */
    suspend fun run(spaceId: String): SelfCheckReport {
        val items = collectLocalItems(spaceId)

        // ---- 组 7：网络 ----
        //
        // 放在最后是有意的：它是唯一一个**会真的发网络请求**的检查项（最多 6 秒），
        // 前面的本地检查要先把结果给出来，用户不用干等。
        //
        // 为什么必须有这一项：microG 登录第一步是 checkin（连
        // android.clients.google.com）。这一步失败时 microG 只弹一句
        // 「与 Google 服务器通信时发生问题」，用户完全无从判断是自己的网络
        // 还是容器坏了 —— 实测中真的有人因此一路怀疑到容器实现上。
        val net = NetworkProbe.run()
        items += CheckItem(
            "网络", "能否连上 Google",
            when (net.verdict) {
                NetworkProbe.Verdict.OK -> CheckStatus.PASS
                // 两种情况都算 FAIL：用户要的是"能不能登录"，不是"哪一层坏了"。
                // 区别写在 detail 里。
                NetworkProbe.Verdict.GOOGLE_BLOCKED,
                NetworkProbe.Verdict.NO_NETWORK,
                -> CheckStatus.FAIL
            },
            net.detail,
        )

        return SelfCheckReport(spaceId, items)
    }

    /**
     * 只做**本地**检查（不联网）。
     *
     * 单独抽出来给 [canAttemptSignIn] 用：那个函数是在 UI 里同步调用的，
     * 不能因为"要不要禁用登录按钮"就每次都发一次网络请求。
     */
    private fun collectLocalItems(spaceId: String): MutableList<CheckItem> {
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
        //
        // ⚠️ 这里**不能**只看"配置文件在不在"。
        // DeviceProfileSpoofer 只是把意图写成一个 JSON；要让它真的生效，
        // 必须由引擎在虚拟进程里改写 Build 的静态字段。之前没有能力开关，
        // 于是这条检查永远 PASS、面板上还写着"对外呈现：Pixel 8 Pro" ——
        // 而容器实际对外呈现的是真机。用户据此以为设备认证没问题，排查方向全错。
        //
        // 现在的判据：**引擎是否声明支持**（capability.supportsDeviceSpoof），
        // 配置内容只作为附注展示。
        val profile = DeviceProfileSpoofer(context, engine).profileOf(spaceId)
        val spoofSupported = engine.capability.supportsDeviceSpoof
        val configuredText = profile?.let {
            "已配置：${it.profile.manufacturer} ${it.profile.model}（${it.profile.device}），sdk=${it.profile.sdkInt}"
        } ?: "未配置指纹伪装。"
        items += CheckItem(
            "设备指纹", "指纹伪装生效",
            if (spoofSupported) CheckStatus.PASS else CheckStatus.WARN,
            if (spoofSupported) {
                "$configuredText —— 引擎已支持改写容器内的 Build，容器对外呈现的是上面这套指纹。"
            } else {
                "$configuredText 但**当前引擎（${engine.name}）不支持改写容器内的 Build**，" +
                    "所以这份配置只是一份意图，容器对外呈现的仍是真机指纹。" +
                    "影响：需要设备认证的 Google 应用会判定为未认证。" +
                    "不影响的：microG 登录、Aurora Store 下载安装、多开 —— " +
                    "这条主链路本来就不依赖设备认证。"
            },
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

        return items
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
        // 用**本地**检查，不联网：这个函数是在 UI 里同步调用的，
        // 只为了决定"登录按钮要不要置灰"，不该每次都发网络请求。
        val items = collectLocalItems(spaceId)
        return items.none {
            it.status == CheckStatus.FAIL && it.group in setOf("容器能力", "组件安装")
        }
    }

    fun isProvisioned(spaceId: String): Boolean = provisioner.isProvisioned(spaceId)
}
