# Dooble Double

> 一个「多开空间 + 完整谷歌环境」的 Android 容器化运行框架。
> 免 Root，可登录 Google 账号，复用成熟开源方案，宿主包体 **12.4MB**。

---

## 这是什么

Dooble Double 让你在一台手机上创建多个**互相隔离的虚拟 Android 环境**（下称「空间」），
每个空间里都能：

- 独立安装、运行应用，同一个应用可以同时开 N 份，数据互不干扰；
- 拥有一套完整的谷歌环境（microG 栈），应用以为自己在有 GMS 的设备上；
- 登录各自的 Google 账号，多个账号互不顶号；
- 对外呈现伪装后的设备指纹（品牌 / 型号 / 指纹），绕过未认证设备限制。

## 技术选型（结论先行）

| 层 | 选型 | 许可 |
|---|---|---|
| 虚拟化引擎 | 应用层虚拟化容器（Bcore / VirtualApp 血统） | Apache-2.0 |
| 谷歌环境 | microG（GmsCore + FakeStore + GsfProxy + MapsV1） | Apache-2.0 |
| 签名伪造 | 容器级 PackageManagerService Hook（**不需要 Root，不需要改 ROM**） | 自研 |
| Native 层 | 自研 PLT Hook + IO 重定向（可替换为 Dobby / xDL） | 自研 |
| 语言 | Kotlin（业务 / UI）+ Java（容器核心）+ C++/NDK（native hook）+ AIDL（IPC） | — |
| UI | Jetpack Compose，后现代 / 新粗野主义风格 | — |

完整的方案对比与选型推理见 **[docs/01-技术选型报告.md](docs/01-技术选型报告.md)**。

## 关键洞察：为什么这个组合能免 Root 提供谷歌服务

传统上要在无 GMS 的设备上用 Google 服务，只有两条路：**刷 ROM**（打 signature spoofing 补丁）
或者 **Root + Magisk 模块**。两者门槛都很高。

而**虚拟化容器本来就已经 Hook 了 PackageManagerService** —— 不然它没法让一个没安装的 APK
「看起来已安装」。既然 Hook 点已经存在，我们只需要在同一个点上多挂一条规则：

> 当查询目标是 `com.google.android.gms` / `com.android.vending` 时，
> 把返回的 `Signature[]` 替换成 Google 官方签名。

于是 microG 就能通过应用侧的签名校验，**签名伪造的成本降到零**。

## 模块结构

```
dooble double/
├── app/          宿主应用：空间管理 + 后现代 UI + 编排
├── engine/       虚拟化引擎抽象层 + Bcore 适配器 + 降级沙箱
│   ├── libs/         bcore.aar（由 tools/build-bcore.sh 产出）
│   └── src/bcore/    Bcore 编译期绑定实现（AAR 存在时才参与编译）
├── gms/          供给层：microG 下载/装配、应用商店供给、签名伪造、设备指纹、自检、账号桥
├── native/       NDK 层：PLT Hook、IO 重定向、JNI 出口
├── tools/        microG 离线包拉取 + Bcore 引擎构建脚本 + 证书导出工具
└── docs/         技术选型、架构设计、构建指南、路线图、GBox 机制解析、系统集成
```

## 快速开始

```bash
# 1. 环境：JDK 17 + Android SDK 35 + NDK 26.1.10909125
#    详见 docs/03-构建与使用.md

# 2. 构建虚拟化引擎（只需一次；需要 JDK 21 + NDK 29）
tools/build-bcore.sh

# 3. 构建应用
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleLiteDebug     # 精简版，宿主 ~12MB，microG 按需下载
./gradlew :app:assembleFullDebug     # 内置版，宿主 ~112MB，离线可用

# 4.（可选）预置 microG，实现离线装配谷歌环境
./tools/fetch-microg.sh              # macOS / Linux / Git Bash
```

产物：`app/build/outputs/apk/lite/debug/app-lite-debug.apk`

> **ABI 提示**：Bcore 只提供 `arm64-v8a` / `armeabi-v7a` 的 native 库，
> x86 模拟器上无法运行虚拟应用，请用真机测试。

## 当前状态

- ✅ 完整的四模块工程，可编译出 APK
- ✅ 空间生命周期、APK 装入、应用管理全链路打通
- ✅ **已接入真正的虚拟化引擎（Bcore）** —— 容器内运行未安装 APK、同应用多开
- ✅ microG 三级回退下载（内置 assets → 本地缓存 → 多镜像远端）+ 装配编排
- ✅ **从宿主已安装应用导入**：按包名读系统安装路径直接装入容器，
  带「包含系统应用 / 只看依赖 GMS」两个筛选开关（系统应用默认不显示）
- ⚠️ **拆分包（AAB）只装基础 APK** —— 容器侧 `installPackageAsUser(pkg, userId)`
  只透传 `sourceDir`，`splitSourceDirs` 被丢弃。Google 自家应用（Maps / Chrome /
  YouTube 等）全是拆分包，装入后会缺资源。修复方向见 `docs/09-应用安装与宿主克隆.md`
- ✅ **空间内应用商店**：一键装入 Aurora Store，可登录 Google 账号、下载应用
- ✅ **容器内安装路由（两条路都接管）**：
  - Intent 式（`ACTION_INSTALL_PACKAGE`）→ 宿主接管
  - **会话式（`PackageInstaller.Session`，商店实际走的那条）** → 容器自持一套
    `IPackageInstaller` 代理，会话号自己发、APK 落在容器缓存、结果经 `IntentSender` 回传
  - 两条路的结果都写进同一份事件日志，UI 不区分来源
- ✅ **空间保活 + 内存回收**：前台服务维持虚拟进程存活，按 LRU 主动回收闲置进程
- ✅ 设备指纹伪装（Pixel 8 Pro / Pixel 7 / Xiaomi 14 三档）
- ✅ native PLT Hook + IO 重定向（可编译、可运行）
- ✅ 后现代风格 UI（空间列表 / 容器桌面图标区 / 详情 / 自检 / 设置）
- ✅ 空间内应用显示**真实图标与应用名**（直接从容器虚拟目录里那份 APK 解析）
- ✅ 容器级签名伪造三态模式（`OFF` / `TEST` / `OFFICIAL`，证书需自行提供，见下）
- ✅ **官方证书一键导出**：`tools/extract-gms-cert.sh <你自己设备上的 gms.apk>`
  （v1/v2/v3 签名都能读，并自动核对指纹）
- ✅ **电源优化白名单 + 厂商自启管理**：一键申请加入 Doze 白名单；
  内置 MIUI / EMUI / ColorOS / OriginOS / One UI / Flyme 的自启管理入口
- ✅ **桌面快捷方式**：把空间或空间内的应用钉到系统桌面，
  点桌面图标直接进目标应用（透明跳板 Activity，无闪屏）
- ✅ **最近任务显示真实应用身份**：卡片标题是应用真名（不再带 `[B1]` 前缀）、
  图标是应用真图标（不再退化成宿主图标）；每个虚拟应用一个独立任务
- ✅ **单个应用可独立结束运行**：按 (包名, userId) 精确杀进程，
  同空间其他应用、其他空间的同名应用都不受影响；结束后它在最近任务里的
  卡片也会被一并收掉（引擎只杀进程，不清理 `TaskRecord`）
- ✅ **不可逆操作要二次确认**：删除空间、卸载空间内应用都会先弹确认框，
  列出具体代价（几个应用 / 多少 MB / 谷歌环境状态 / 已登录账号），
  并强制先勾选「我已了解…」才放行最终按钮——防的是手指偏几个像素
  或反射性连点导致的误删。判定标准：**用户数据会消失且没有撤销入口**
- ✅ **容器内商店路由**：`market://` / `play.google.com` 链接在容器内解析，
  不会跑到宿主机的浏览器上去
- ✅ **容器内的商店能正常打开**：修掉 Bcore `chooseBestActivity` 在候选同权时
  返回 `null` 的缺陷（原先会让容器内的商店拿到 `null` 后直接 NPE）
- ⏳ **待完成**：容器侧只支持单 APK 安装。AAB 拆分包**两条路都装不全**
  （文件装入与宿主导入都只拿基础 APK），需给 Bcore 的安装入口补
  `splitSourceDirs` 透传（`docs/09` 第 3 节有具体改法）

### 关于「内置商店」的路线选择

市面上的 GBox 类方案内置的是**原版 GMS + Play 商店**，靠伪造机型绕过 Google 的
设备认证。这条路有两个问题：原版 GMS 不可再分发（侵权），且设备认证是场
「封一批、修一批」的消耗战。

本工程换了一条路：**microG + Aurora Store（GPLv3 开源 Play 客户端）+ 容器级安装路由**。
用户体验一致（登录谷歌账号、搜索下载应用），但不踩合规红线，也不会被 Google 封掉。

完整的技术对比见 **[docs/05-GBox机制解析与商店方案.md](docs/05-GBox机制解析与商店方案.md)**。

### 关于空间保活

虚拟应用是从宿主进程 fork 出来的，宿主被回收 = 空间里所有应用一起消失。
设置页可开启前台服务保活，并在内存吃紧时按「最近使用」顺序主动回收闲置的
虚拟进程——主动丢掉一个后台应用，总好过被系统连宿主一起端掉。

实现细节（含一个关于 `TRIM_MEMORY_*` 档位废弃的坑）见
**[docs/03-构建与使用.md](docs/03-构建与使用.md)** 第六节。

**保活之外还必须做两件事**，它们管的是不同的层：

| 层 | 手段 | 解决的问题 |
|---|---|---|
| 进程优先级 | 前台服务保活 | 系统愿不愿意给你 CPU |
| AOSP Doze | 电池优化白名单 | 熄屏后系统会不会冻结整个进程组 |
| ROM 后台清理 | 厂商自启管理 | 国产 ROM 自己的冻结策略（**不读** AOSP 白名单） |

三件事都在设置页的「系统集成」区块里，详见
**[docs/08-系统集成与最近任务.md](docs/08-系统集成与最近任务.md)**。

### 关于登录谷歌账号

登录界面由**空间内**的 microG GmsCore 提供，所以入口必须在容器里解析和启动 ——
空间里的包对宿主 `PackageManager` 是不可见的。踩过的坑、正确组件名与完整实测记录见
**[docs/10-谷歌账号登录链路.md](docs/10-谷歌账号登录链路.md)**。

> 顺带说一句：模拟器上登录页的 WebView 可能因为 Chromium 的 tile 内存超限
> 而**画面空白**（`tile_manager.cc: tile memory limits exceeded`），
> 那是模拟器 GPU 的限制，不是本工程的 bug。真机上正常。

### 关于原版三件套与签名证书

本工程**不打包也不下载**原版 GMS / Play 商店（专有软件，不可再分发），
但都给了自助路径：从自己设备 `adb pull`、或走空间详情页的「从宿主克隆」。
证书同理（`tools/extract-gms-cert.sh`）。

**完整清单见 [docs/03-构建与使用.md](docs/03-构建与使用.md) 第九节**，
包含：证书两种类型的区别、原版组件的三条获取路径、
MuMu 自带的谷歌安装器、以及华为/荣耀（HMS / 纯血鸿蒙）的注意事项。

### 关于签名伪造

先给结论：**本地测试不需要它**。microG 自检 + 登录 + Aurora Store 下载安装
这条主链路，跟「容器向虚拟应用呈现什么 GMS 签名」无关。

只有当你**必须**让容器内的 microG 自检报「签名匹配」时才需要。比对方式是对证书的
**原始 DER 字节**做 SHA-1，与 `38918a453d07199354f8b19af05ec6562ced5788` 比对——
SHA-1 不可反推，所以**任何自签的临时证书都骗不过它**。

本工程提供三态模式（空间详情页可切）：

- `OFF`（默认）——本地测试用，自检 SKIP，不计失败
- `TEST` —— 用 `tools/gen-test-cert.sh` 生成的自签证书验证**整条链路通不通**
- `OFFICIAL` —— 投放自己合法持有的 Google 平台证书，真正通过校验

`TEST` 的价值是把「链路没通」和「证书不对」两个问题分开定位。
容器 PMS Hook 点、策略下发、跨进程读取、端到端自检都已就位，但**不内置证书**——
这与「不打包原版 GMS」是同一条合规红线。详见
**[docs/06-签名伪造与本地测试.md](docs/06-签名伪造与本地测试.md)**。

详见 **[docs/04-路线图.md](docs/04-路线图.md)**。

## 合规声明

本项目仅用于技术研究与个人多账号管理。microG 采用 Apache-2.0，允许再分发。
请遵守你所在地区的法律法规以及各应用的服务条款。不要用它做批量养号、刷量等行为。
