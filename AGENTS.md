# AGENTS.md — Dooble Double 工程须知

「多开空间 + 谷歌环境」的 Android 容器化框架(Bcore/VirtualApp 血统 + microG)。**交流、注释、提交信息一律中文**(提交遵循 `fix:/feat:/docs:/test:/ci:` 常规前缀)。

## 模块与依赖方向

```
app(宿主 UI/服务/编排) → gms(microG 供给/签名伪造/自检) → engine(引擎抽象+Bcore适配) → native(NDK PLT hook)
```

- `gms` 通过 `api(project(":engine"))` 依赖 engine;**engine 不得反向依赖 gms**。
- `engine/src/bcore/` 源集仅在 `engine/libs/bcore.aar` 存在时参与编译(compileOnly);AAR 由 `tools/build-bcore.sh`(JDK 21 + 本地源码 `~/android-dev/nbb-src` + `tools/bcore-patch/*.patch`)构建,改补丁后**必须重建并提交 AAR**,CI 用的是入库的 AAR。
- AAR 缺失时降级 `SandboxEngine`。引擎选择走 `EngineRegistry` SPI（注册顺序：`BcoreEngineFactory` → `SandboxEngineFactory`），见 docs/12。

## 硬性契约(有专门的契约测试钉着,改动必须同步)

- **落盘路径**:gms 与容器服务进程(BcoreSignatureSpoofer)共享的文件路径一律取自 `BcoreStorageLayout`(曾因两端各拼路径导致签名伪造整链静默失效)。改路径要同步跑 `SpoofPolicyPathContractTest`。
- 策略 JSON 格式:`SpoofPolicyCodec`(零 Android 依赖),gms 侧 `SpoofPolicyContractTest` 直接喂真实序列化输出。
- 容器补丁:`tools/bcore-patch/*.patch` 被 `BcorePatchContractTest` 在运行期读取;`gms/build.gradle.kts` 把补丁目录声明为测试输入(防 UP-TO-DATE 假绿)。
- 空间元数据:`SpaceStore`(spaces.json)——损坏必须留档不许静默清空;userId 有单调高水位文件,复用 = 数据串味。
- UI 不得在 Composable 里同步调引擎 API(重 IO);引擎调用一律 `Dispatchers.IO`。

## 构建 / 测试 / 设备工作流

```bash
# 本机环境:JDK17 在 /c/Users/admin/android-dev/jdk/jdk-17.0.20.1+1(bash 调用必须显式 export JAVA_HOME,unix 风格路径)
./gradlew :engine:testDebugUnitTest :gms:testDebugUnitTest :app:testLiteDebugUnitTest --no-daemon
./gradlew :app:assembleLiteDebug            # 产物 app/build/outputs/apk/lite/debug/
tools/run-on-mumu.sh                        # 连 MuMu(adb 端口 16384,装 dist/dooble-lite-debug.apk + 启动 + 抓日志)
python tools/mumu-ui.py dump|tap|shot|wait  # MuMu UI 自动化;uiautomator 的属性是 content-desc;文本节点不可点,工具会找 clickable 祖先
tools/real-device-diag.sh                   # 真机诊断(产物含隐私,勿提交)
```

- `dist/dooble-lite-debug.apk` 是推设备的约定位置(构建后手动 cp)。
- **`python3` 在本机是坏的 Windows Store 别名**(静默 exit 49),用 `python`;提取证书用 `PYTHON=python bash tools/extract-gms-cert.sh <gms.apk>`。
- Git Bash 会把设备路径转成本地路径,adb push/pull 设备绝对路径前加 `MSYS_NO_PATHCONV=1`。
- MuMu 是 x86_64 + ARM 翻译层(libnb.so),ABI 必须含 arm64-v8a;模拟器网络**不走宿主 Clash TUN**,需要代理时才用 `adb shell settings put global http_proxy 10.0.2.2:7897`(清掉用 `:0`)。**别默认挂代理**:宿主本身能直连 Google 时,挂上代理反而会让 Aurora 的 Play 认证 TLS 握手直接 EOF(`SSLHandshakeException: connection closed` → 弹登录页)。
- 容器应用多为竖屏,而 MuMu 的 Lawnchair 会把显示强制转到横屏;显示方向与窗口方向不一致时 **uiautomator 的 bounds 和 input 的坐标不在同一空间**,自动化会「点了没反应」。操作容器应用前先 `settings put system user_rotation 0`,并用 `dumpsys input | grep "Viewport INTERNAL"` 确认 orientation 与目标窗口一致。
- 磁盘敏感:构建前确认 C 盘余量;`tmp-real*/`、`local/`(除已跟踪子目录)、keystore/ 不入库,诊断产物严禁提交。

## 已知关键事实(避免重查)

- Bcore 的 `getInstalledPackages` 带 GET_ACTIVITIES|GET_META_DATA 会超 Binder 事务上限,查询 flags 用 **0**;容器包列表混着宿主全部系统包,列表类调用要考虑量级。
- `BcoreEngine.listApps` 的 label 走 `labelFromApk`(宿主 PM 查不到虚拟包),已有 (path,mtime) 缓存。
- `BlackBoxCore.deleteUser` 返回 void、失败不抛错——删空间必须 `getUsers()` 回查。
- 容器内应用发起的安装走 `ContainerInstallRelay`(Intent 式)+ 会话式补丁,结果统一进 `InstallEventLog`(JSONL,`install-events.jsonl`)。
- **容器内 WorkManager/JobScheduler 已打通**(`0006`):客户端泛化到任意参数位置的 JobInfo,服务端解析失败时用 JobInfo 自带组件兜底并「登记不 fork」。验证手段:`adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler com.dooble.space.debug <jobId>` 强制触发,看容器进程有没有 `WM-SystemJobService onStartJob`。microG 的 checkin 依赖这条链,链路通后设备注册已完成(有 Android ID)。
- **Android 12+ 的 `DomainVerificationManager` 不走 IPackageManager**,它直连独立的 `domain_verification` 服务,容器钩子拦不到 → 虚拟包查状态抛 `NameNotFoundException`,把调用方整段逻辑静默打断(Aurora 点「安装」无反应的最初原因)。补丁 `0007` 代理该服务。**同类问题一律先确认服务名再挂钩子**,挂在 IPackageManagerProxy 上是无效的。
- Aurora 安装:会话式安装(PackageInstaller.Session)在 MuMu 上卡 `Failed to execute fsync() across bridge`(libhoudini 限制);**已实测可用的是「原生安装程序(已废弃)」**——Aurora 设置→安装→安装方式 切过去后走意图式安装,配合补丁 `0010`(`isRequestInstall` 识别 `ACTION_INSTALL_PACKAGE`)可正常装入当前空间。
- **F-Droid 下载不启动已修复（补丁 `0012`）**:F-Droid 的 `DownloaderService`/`InstallerService` 继承 `androidx.core.app.JobIntentService`,容器 `createJobService` 强转抛 `ClassCastException`。修法是回退路径直接反射 `JobIntentService.mJobImpl` 字段拿 `JobServiceEngine`,调**公开类** `android.app.job.JobServiceEngine.onStartJob(JobParameters)`。**不要**去反射 `IJobService` binder——那是 `@hide` 接口,Android 9+ hidden API 拦截会让 `getMethod("onStartJob",...)` 抛 `NoSuchMethodException`(实测 Android 15 正是如此)。
- **F-Droid「正在安装…」卡住已修复（补丁 `0013`）**:F-Droid 的原生安装器用 `PendingIntent.getActivity()` + 广播启动 `DefaultInstallerActivity`,而 Bcore 的 `IActivityManagerProxy.SendIntentSender` 钩子无条件返回 0,把容器内所有 `PendingIntent.send()` 吞掉,`DefaultInstallerActivity` 永远不启动。修法:`SendIntentSender` 改为 `method.invoke(who, args)` 放行(创建侧 `GetIntentSender` 已把目标路由到宿主 `ProxyPendingActivity`,放行是安全的)。**经验**:容器内"点了没反应"类问题,查 `SendIntentSender` 是否在静默吞 PendingIntent。F-Droid 装 KISS 已在 MuMu 端到端验证(桌面自动出现 KISS)。
- 启动路由:补丁 `0011` 让 `getLaunchIntentForPackage` 在多 launcher activity 时优先选「label 与应用名一致」的入口,修复 F-Droid 打开成防偷看计算器的问题。
- microG/Aurora 的网络遵循 Android 全局 http_proxy;自检网络探测见 `NetworkProbe`(verdict 判定是纯函数 `decideVerdict`,有单测)。
- microG 登录:checkin 通后 `accounts.google.com/EmbeddedSetup` 能在容器内加载完(`GmsAuthLoginBrowser pageFinished`),但停在「请稍等…」;`E/chromium ... tile memory limits exceeded` 是 MuMu GPU tile 内存预算被打满。**已实测定论(2026-09-30)**:`hardwareAccelerated=false`(manifest)、`setLayerType(LAYER_TYPE_SOFTWARE)`(日志确认已应用,补丁已移除)、`wm size 540x960`、webview 命令行均无效——MuMu 的 WebView 无视这些信号,始终走 GPU 合成器。这是模拟器环境限制,**需真机验证**。
- **商店装完应用要直接出现在「容器桌面」**:宿主详情页 `ON_RESUME` 已并入 `refreshApps()`(重读容器应用列表),详情页常驻时每 4s 轮询 `peekInstallEvents()`(轻量只读,有新事件才真正 `listApps`)。改 `VirtualEngine` 接口记得同步 BcoreEngine 实现。
- UI 对话框内容可能超高,**必须 verticalScroll**(横屏曾把按钮裁出屏幕)。
- toast 用 StateFlow 承载,连续相同文案会被去重。

## 文档索引(docs/ 均为中文)

01 技术选型 · 03 构建与使用(保活细节) · 04 路线图 · 05 GBox/商店方案 · 06 签名伪造与测试 · 08 系统集成 · 09 应用安装与宿主克隆(AAB 拆分包限制) · 10 谷歌账号登录链路。改动敏感区(供给链路/容器补丁/安装路由)前先读对应文档。

## 合规红线

不打包、不分发、不提供下载渠道给原版 GMS / Play 商店(专有软件);官方证书不入库、不随包分发(`local/signature/` 已 gitignore)。用户自带文件走「导入 APK」路径。
