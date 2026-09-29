package com.dooble.gms

import com.dooble.engine.internal.BcoreStorageLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 「签名伪造策略文件」的**路径契约**测试。
 *
 * ## 为什么路径也要契约
 *
 * 这份文件写入方在 `gms`（GmsSignatureSpoof.apply），读取方在容器服务进程
 * （BcoreSignatureSpoofer.buildTable）。曾经两端**各拼一份路径字符串**：
 * 读方比写方多了一段 `data/`，策略文件永远找不到 → `readPolicy` 对不存在的
 * 文件返回空表 → `spoof()` 恒返回 null——**签名伪造整条链路静默失效**，
 * 编译过、单测绿（格式契约测的是内容不是路径）、运行时无任何报错。
 *
 * 现在两端都必须经过 [BcoreStorageLayout.spoofPolicyFile] 这**同一个函数**，
 * 漂移在结构上不可能；本测试再把绝对布局钉死，防止有人"顺手改简"路径。
 */
class SpoofPolicyPathContractTest {

    @Test
    fun `策略文件落在文档约定的绝对位置`() {
        val filesDir = File("/data/user/0/com.dooble.space/files")
        val file = BcoreStorageLayout.spoofPolicyFile(filesDir, "sp_contract01")

        assertEquals(
            File(
                "/data/user/0/com.dooble.space/files" +
                    "/dooble/virtual/sp_contract01/.dooble/spoof_policy.json",
            ),
            file,
        )
    }

    @Test
    fun `相对路径常量与目录名常量互洽`() {
        assertEquals(".dooble", BcoreStorageLayout.META_DIR_NAME)
        assertEquals(
            "${BcoreStorageLayout.META_DIR_NAME}/spoof_policy.json",
            BcoreStorageLayout.SPOOF_POLICY_RELATIVE,
        )
        // 设备指纹配置与策略文件同居一个 .dooble 目录（DeviceProfileSpoofer 用）
        assertEquals(
            "${BcoreStorageLayout.META_DIR_NAME}/device_profile.json",
            BcoreStorageLayout.DEVICE_PROFILE_RELATIVE,
        )
    }
}
