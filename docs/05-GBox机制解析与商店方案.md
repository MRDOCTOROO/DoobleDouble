# GBox 机制解析，以及本工程的商店方案

> 本文回答一个问题：**GBox 是怎么做到「内置 Play 商店、能登录谷歌账号、能下载安装应用」的？
> 我们能不能照做？**

结论先行：**GBox 的做法在技术上有两处绕不过去的坎（一处法律、一处技术），本工程不采用；
但用户真正想要的能力——「在空间里登录谷歌账号、下载安装应用」——我们用另一条路完整实现了。**

---

## 一、GBox 到底做了什么

### 1.1 它内置的是**原版 GMS 和 Play 商店**

这是与我们方案最根本的区别。GBox 把 Google 官方的

- `com.google.android.gms`（Google Play 服务）
- `com.android.vending`（Google Play 商店）
- `com.google.android.gsf`（Google 服务框架）

这几个 APK **直接打包进自己的安装包**，放进虚拟化容器里运行。所以用户装完 GBox 就能直接
打开 Play 商店——不需要任何额外步骤。

而本工程用的是 **microG**：一套按 Apache-2.0 许可重新实现的 GMS 兼容层。
功能覆盖上它能满足绝大多数应用的依赖，但它**不是** Google 的原版实现，
Play 商店也不在里面。

### 1.2 它靠「虚拟机型」绕过 Google 的机型封禁

GBox 主要面向国内机型（尤其是没有 GMS 的华为/荣耀）。Google 对一批未认证机型
在服务端做了封禁——表现为「此设备未经 Play 保护机制认证」，商店无法下载应用。

GBox 的对策是**在容器里伪造一套设备指纹**（品牌 / 型号 / 指纹 / 构建号），
让 Google 服务端看到的是一个已认证的机型。这就是「通过虚拟机型的方式绕过封禁」的含义。

### 1.3 容器内应用的安装要被路由回容器

这一条容易被忽略，但它是「商店能不能用」的分水岭，**本工程也踩过这个坑**：

Play 商店（或任何应用）下载完 APK 后，会发出一个
`type = application/vnd.android.package-archive` 的安装 Intent。
如果容器不接管这个 Intent，它会被转发给**宿主系统的安装器**——
结果是应用装到了宿主机上，空间里什么都没有。

详见本文第三节，这是我们在本轮真正实现的东西。

---

## 二、为什么本工程不照抄

### 2.1 法律坎：原版 GMS 不可再分发

GMS 与 Play 商店是 Google 的**专有软件**。把它们打包进自己的 APK 再分发，
违反 Google 的许可条款，属于侵权行为。这也是 GBox 在 Google Play 上架不了、
需要靠第三方 APK 站点分发的原因。

本工程在技术选型阶段就把这一条写成了硬约束：

> **谷歌环境 = microG，不内置原版 GMS（不可再分发，法律风险）。**

### 2.2 技术坎：设备认证是场打不赢的消耗战

就算不管版权，把原版 GMS 塞进去，还要过 Play 保护机制认证（Play Integrity）。
这道校验不是简单的字段比对，而是一套**远程密钥认证**：

| 检测维度 | 说明 |
|---|---|
| `appIntegrity` | 应用证书 SHA-256 与 Play 服务端记录比对，防重打包 |
| `deviceIntegrity` | 依赖 TEE / StrongBox 硬件密钥，校验 `verifiedBootState`、`deviceLocked` |
| 模拟器/容器检测 | 纯软件环境拿不到 `MEETS_DEVICE_INTEGRITY` 标签 |
| `MEETS_STRONG_INTEGRITY` | 还要求系统在一年内收到过安全更新 |

GBox 能拿到的通常只是最弱的 `MEETS_BASIC_INTEGRITY`——它靠伪造机型信息蒙混过关。
但 Google 在服务端持续收紧，**这类方案是「封一批、修一批」的循环**：
网上每一篇 GBox 教程都带着「如果商店闪退 / 卡在加载中怎么办」的排障章节，
不是偶然。

本工程要的是「装一次、长期能用」，不适合押在这种对抗性路线上。

### 2.3 但"不内置"不等于"不支持" —— 用户可以自己装

上面两条说的是**我们不分发**，不是说容器做不到。这两件事必须分开讲，
否则用户会以为「原版三件套」是个被阉割掉的功能。

容器侧其实是**支持**的，依据是 Bcore 源码而不是猜测：

`BlackBoxCore.installPackageAsUser(String packageName, int userId)` 内部走
`InstallOption.installBySystem()`，也就是 **FLAG_SYSTEM 语义**。
它在 `BPackageManagerService` 里做的是：

```java
if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
    aPackage.applicationInfo =
        BlackBoxCore.getPackageManager().getPackageInfo(pkg, 0).applicationInfo;
}
```

也就是**直接引用宿主上的 `ApplicationInfo`**（含 `FLAG_SYSTEM` 与宿主的
`nativeLibraryDir`），并且 `CopyExecutor` 不复制 APK、不抽 native 库。

于是容器内的应用查询这个包时，看到的是一个**系统应用**。这正是 GMS / Play 商店
在容器里能工作的前提 —— 等价于社区方案里说的 "privileged factory slot"，
只不过是在容器命名空间内实现的。

所以本工程提供**三条合法的获取路径**，都建立在"这是用户自己设备上的副本"之上：

| 路径 | 适用 | 走的容器语义 | 谁提供文件 |
|---|---|---|---|
| microG 官方直链 | microG 系（可合法分发） | storage | 上游 GitHub release |
| 从宿主克隆 | 用户设备上的副本，**含系统应用** | **system** | 用户自己的设备 |
| 导入 APK | 用户自备的任意 APK | storage | 用户自己 |

> **关键约束**：原版 GMS / Play 商店必须走「从宿主克隆」。
> 它们带 native 库、且会读自己的 `ApplicationInfo.flags` 判断是不是系统应用，
> 而「导入 APK」走的是 storage 语义，两样都给不了 —— 装了也起不来。
> microG 版不受这个限制（不带 native 库、不检查系统身份），所以能走官方直链。

### 2.4 原版 Play 商店在免 root 容器里的实际结局

即使装上了、能打开，**下载功能大概率不可用**。社区有实测方案
（microG + 官方签名 Play Store 达到 `MEETS_DEVICE_INTEGRITY`），但它要求：

1. root + Zygisk 隐藏栈；
2. 官方签名的 `com.android.vending` 放进**特权工厂槽位**（不是 `/data/app`）；
3. Play Integrity 相关的 attestation 组件（Play Integrity Fork + Tricky Store 之类）；
4. 签名伪造切到 OFFICIAL 且自备 Google 官方证书。

第 3、4 条在**免 root 容器**里做不到 —— 拿不到硬件凭据。
而且即使全满足，`MEETS_DEVICE_INTEGRITY` 也是**服务端判定**，
Google 改规则就失效。

所以本工程的结论没有变：**主推 Aurora Store**。
它走同一套 Play 接口、能登录谷歌账号、官方明确兼容 microG，
且**不做设备认证**，因此实际可用。原版 Play 商店的入口保留，
但 UI 上如实写明上述限制（见 `GoogleStack.PLAY_STORE_VERDICT`）。

---

## 三、我们实现的东西

用户要的能力是「在空间里登录谷歌账号、下载安装应用」。拆开来是两件事：

| 需求 | 我们的方案 |
|---|---|
| 有个商店，能登录 Google 账号、能下载应用 | **Aurora Store**（GPLv3，开源） |
| 商店下载的应用要装进**当前空间**，不是宿主机 | **容器内安装路由**（本轮新实现） |

### 3.1 为什么 Aurora Store 是等价方案

Aurora Store 是 Google Play 的开源客户端，走的是**同一套 Play 接口**：

| 能力 | Play 商店 | Aurora Store |
|---|---|---|
| 登录 Google 账号 | ✅ | ✅（也支持匿名会话） |
| 从 Google 服务器下载应用 | ✅ | ✅ |
| 搜索 / 更新 / 已购列表 | ✅ | ✅ |
| 可再分发 | ❌ 专有软件 | ✅ GPLv3 |
| 需要设备认证 | ✅ 强制 | ❌ 不需要 |
| 与 microG 配合 | 冲突 | ✅ 官方明确支持 |

它不拥有、不分发任何应用，只是一个访问 Google Play 的客户端工具——
**和浏览器访问网页是同一性质**。

所以：**用户要的体验完全一样，且不踩任何合规红线。**

### 3.2 容器内安装路由（本轮的核心实现）

这是真正有技术含量的一块。

#### 问题

Bcore 拦截到容器内应用发出的安装 Intent 后，逻辑是这样的
（`fake/service/ActivityManagerCommonProxy.java`）：

```java
if (ComponentUtils.isRequestInstall(intent)) {          // type == apk
    File file = FileProviderHandler.convertFile(...);
    if (BlackBoxCore.get().requestInstallPackage(file, userId)) {
        return 0;                                       // ← 宿主接管，拦下来
    }
    intent.setData(convertFileUri(...));
    return method.invoke(who, args);                    // ← 否则转发给【系统安装器】
}
```

**宿主不接管时，APK 会被装到宿主机上。** 表现就是：在空间里用商店装个应用，
回到桌面发现装在了外面，空间里空空如也。

#### 解法

`ClientConfiguration.requestInstallPackage(file, userId)` 就是为这个场景准备的钩子。
早先的实现直接 `return false`——等于主动放弃了这个能力。

现在改成：

1. **返回 `true`**，把 Intent 拦下来（顺带好处：用户不会看到系统安装弹窗，
   安装完全发生在空间内部）
2. 在回调里用 `userId` 定位到对应空间，调 `installPackageAsUser(file, userId)`
   装进容器
3. **必须异步**——这个回调跑在虚拟应用的主线程上，同步做完整安装会卡死容器内应用
4. 结果写进事件日志，UI 侧消费后提示用户

#### 跨进程的事件回传

安装发生在**虚拟应用进程**，UI 跑在**主进程**，两者之间没有现成通道。
这里用文件做单向日志（`<filesDir>/dooble/install-events.jsonl`，JSONL 格式）：

- 不依赖 Binder 注册时序，进程被杀也不丢
- 写入方只追加，读取方消费后清空
- 日志里存的是引擎内部的 `userId`，由引擎侧用权威数据源反查成空间 id——
  因为 relay 跑在虚拟进程里，拿不到最新的空间列表（空间可能在安装期间被删掉）

#### 相关文件

| 文件 | 职责 |
|---|---|
| `engine/src/bcore/.../ContainerInstallRelay.kt` | 容器侧接管安装，异步执行 |
| `engine/src/main/.../internal/InstallEventLog.kt` | 跨进程事件日志（主源集，不依赖 Bcore） |
| `engine/src/main/.../VirtualEngine.kt` | `drainInstallEvents()` 接口 + `SpaceInstallEvent` |
| `gms/.../StoreProvisioner.kt` | 商店供给：多镜像下载 + 缓存 + 装入空间 |

### 3.3 会话式安装（商店真正走的那条路）

3.2 说的是 **Intent 式安装**。但现代商店装应用走的是**另一条路**：

```java
PackageInstaller installer = getPackageManager().getPackageInstaller();
int id = installer.createSession(params);
try (OutputStream out = installer.openSession(id).openWrite("base.apk", 0, size)) {
    apkFile.inputStream().copyTo(out);
}
installer.openSession(id).commit(pendingIntent.intentSender);
```

Aurora Store 的默认安装后端 `SessionInstaller` 就是它（Root / Shizuku / Native
都是备选）。Play Store 同理。**所以只补 3.2 那条路，商店是装不上东西的。**

#### 问题一：一启动就闪退

`AuroraApp.onCreate` 会清理遗留会话，调 `PackageInstaller.getMySessions()`。
这个方法直通宿主机系统服务，宿主上并没有 `com.aurora.store` 这个包，于是：

```
java.lang.SecurityException: Package com.aurora.store does not belong to 10051
  at android.app.AppOpsManager.checkPackage(AppOpsManager.java:9636)
  at com.android.server.pm.PackageInstallerService.getMySessions(PackageInstallerService.java:1384)
  at android.content.pm.PackageInstaller.getMySessions(PackageInstaller.java:935)
  at com.aurora.store.AuroraApp.onCreate(...)
```

表现是空间里的商店**一启动就闪退**，用户看到的只是"打不开"。

#### 问题二：装到宿主机上

`commit()` 一旦放行，系统会真的把 APK 装到**宿主机**，空间里什么都没有。

#### 解法：容器自持一套 PackageInstaller

Bcore 上游有 88 个 `fake/service` 代理，**没有 `IPackageInstaller` 代理**。
补丁 `0002-container-package-installer.patch` 补上了它。

关键设计：

| 决定 | 为什么 |
|---|---|
| **不替换 `package_installer` 系统服务** | 宿主机自己的安装流程还要用它。只挂 `IPackageManager.getPackageInstaller` 这一个钩子 |
| **会话号容器自己发** | 从 `0x40000001` 起自增，完全不走系统 `PackageInstallerService` |
| **`getMySessions` 恒返回空** | 容器里本来就没有"系统会话"。这不是没实现，是事实 |
| **用动态代理而不是实现 AIDL Stub** | `IPackageInstaller` / `IPackageInstallerSession` 都是 `@hide`，编译期拿不到 |
| **写入走管道** | 应用只知道"写一个流"，不知道长度；只有拿到 EOF 才知道写完 |
| **`commit` 必须异步** | 它在虚拟应用主线程上被调用，同步装完会卡死 |
| **结果通过 `IntentSender` 回传** | 与系统 `PackageInstallerService` 的做法一致，调用方的广播接收器靠这些 extra 判断成败 |

#### 会话回调：商店安装队列的状态机驱动点

Aurora 等商店除了收 `IntentSender` 广播，还用
`PackageInstaller.registerSessionCallback()` 驱动 UI 状态机：会话创建/完成时，
框架的 `SessionCallbackDelegate` 会回调 `onSessionCreated(int)` /
`onSessionFinished(int, boolean)`，商店据此把「正在安装中」推进到「已安装/失败」。

容器没有系统会话服务，回调永远不会由系统触发。补丁 `0014-session-callback-status.patch`
让 `PackageInstallerProxy` 自己补上这条链路：

- `registerSessionCallback` 捕获 `IPackageInstallerCallback` binder（`Set` 去重）；
- 新增 `GetSessionInfo` 钩子，反射构造真实的 `SessionInfo`（`onSessionCreated`
  后框架会立刻反查 `getSessionInfo(id)`）；
- `createSession` 在 `SESSIONS.put` 之后触发 `onSessionCreated`；
- `commit` 完成（含失败早退）在 `sendStatus` 之后触发 `onSessionFinished`——
  广播先到、回调后到，商店先刷新列表再推进队列，两个通道互不替代。

#### 安装结果的两条回传通道（补丁 0016 补全）

商店的安装完成要经过两条通道，缺一不可：

1. **IntentSender 广播**（会话安装器都依赖）：Aurora 的
   `InstallerStatusReceiver` 和 F-Droid 的 `SessionInstallManager` 都用
   `PendingIntent.getBroadcast()` 创建 commit 用的 IntentSender。容器
   `GetIntentSender` 钩子原来只包装 ACTIVITY 类型的 PendingIntent，
   广播类型的仍指向虚拟包接收器，真实 AMS 投不回来。0016 给
   `INTENT_SENDER_BROADCAST` 做同样的宿主包装，并在
   `ProxyBroadcastRecord.create` 里把实际广播携带的
   `EXTRA_STATUS` / `EXTRA_SESSION_ID` 等合并回原始 intent。
2. **`startActivityForResult` 结果**（F-Droid 旧安装器
   `DefaultInstallerActivity`）：容器拦截 `ACTION_INSTALL_PACKAGE` 后直接装
   完，原来不投递结果。0016 用 `BRActivityThread.sendActivityResult()` 把
   `RESULT_OK` 回传给调用方，`onActivityResult` 才会发
   `ACTION_INSTALL_COMPLETE`。

挂载点选得很取巧——不需要动系统服务表：

```java
// ApplicationPackageManager.getPackageInstaller() 内部是：
//   new PackageInstaller(mPM.getPackageInstaller(), ...)
// 而 mPM 已经被 IPackageManagerProxy.inject() 换成了我们的代理。
// 所以只要在代理上挂一个同名钩子，就精确地只影响"容器内应用拿到的 PackageInstaller"。
@ProxyMethod("getPackageInstaller")
public static class GetPackageInstaller extends MethodHook { ... }
```

#### 一处容易踩的坑：接口 Class 必须与框架同源

Bcore 的 `src/main/aidl/android/content/pm/` 下有上游留下的一份
`IPackageInstallerSession.aidl`，编译后 **app 的 dex 里也有这个类名**。
如果直接 `Class.forName(...)` 恰好命中 app 自己那份，拿到的 `Class` 对象
就和框架 `PackageInstaller.Session` 期望的不是同一个 —— 表现是
`new Session(proxy)` 抛 `ClassCastException`，而且**只在运行期炸，编译期毫无提示**。

所以实现里从框架的 `PackageInstaller$Session` 构造函数参数上取类型，
那是框架自己用的那个 `Class` 对象，一定一致。

#### 已知限制：容器只支持单 APK 安装

`BPackageManagerService.installPackageAsUserLocked()` 只处理一个 APK。
会话里如果落了多个文件（AAB 拆分包），实现会取体积最大的那个安装，
并打一条警告日志。这不是"假装支持"——UI 侧的「从已安装应用导入」
本来就是推荐路径，因为它能让容器读到系统的 `splitSourceDirs`。

#### 相关文件

| 文件 | 职责 |
|---|---|
| `tools/bcore-patch/0002-container-package-installer.patch` | 补丁本体（4 个文件） |
| `tools/bcore-patch/0014-session-callback-status.patch` | 补全 `registerSessionCallback` / `getSessionInfo` / `onSessionFinished` 会话回调，驱动商店安装队列前进 |
| `Bcore/.../fake/service/PackageInstallerProxy.java` | `IPackageInstaller` 代理 + 会话状态机 + `IPackageInstallerSession` 动态代理 |
| `Bcore/.../app/configuration/ClientConfiguration.java` | 新增 `onInstallResult` 回调 |
| `engine/src/bcore/.../ContainerInstallRelay.kt` | `onSessionResult()` 把结果落进同一份事件日志 |

> **两条安装路径最终写进同一个 `install-events.jsonl`**，
> UI 侧不需要区分来源 —— 用户只关心"装上了没有"。

---

## 四、使用流程

1. 新建空间 → 装配谷歌环境（microG）
2. 空间详情页 →「应用商店」→ 点「装入应用商店」
3. 装完后点「打开」，在 Aurora Store 里登录 Google 账号
4. 搜索并下载应用 → **下载的 APK 会自动装进当前空间**
5. 空间详情页的「空间内安装记录」能看到每次安装的结果

> 如果想用其它商店（F-Droid、APKMirror 客户端等），用「从文件装入 APK」装进去即可——
> 安装路由是容器级能力，对任何商店都生效。

---

## 五、组件槽位：每个槽位的真实来源

UI 上的"谷歌环境"是一排槽位。每个槽位**一个包名**，可以有三种来源
（官方直链 / 从宿主克隆 / 导入 APK），三种来源互相替换 —— 因为包名只能占一个。

| 槽位 | 包名 | 官方直链 | 说明 |
|---|---|---|---|
| GmsCore | `com.google.android.gms` | ✅ `microg/GmsCore` | 必需。103MB |
| FakeStore | `com.android.vending` | ✅ `microg/GmsCore` | 必需。仅占位，4MB |
| GSF Proxy | `com.google.android.gsf` | ✅ `microg/GsfProxy` | 可选。21.4KB |

### 5.1 GSF Proxy 是什么，为什么之前装不上

`com.google.android.gsf` 是 **Google Services Framework**。microG 提供的
`GsfProxy` 是它的转发层：老式 **C2DM / GCM** 客户端库（2016 年前的）会去调 GSF，
GsfProxy 把这些调用重定向到 GmsCore。它**不能独立工作**。

之前它显示"未安装（可选）"但点不动，原因是**它不在 GmsCore 仓库里** ——
它在 microG 的另一个独立仓库 `microg/GsfProxy`，最后一个 release 是 2016 年的
`v0.1.0`，资产只有一个 `GsfProxy.apk`（21.4 KB）。

本轮的修正：给 [MicroGComponent] 加了 `downloadRepo` / `releaseTag` / `remoteName`
三个字段，让每个组件自带自己的仓库与 tag（GmsCore 是 `v0.3.16.252432`，
GsfProxy 是 `v0.1.0`）。顺带修掉一个隐患：`candidates()` 之前会拿 GmsCore 的
资产列表去匹配任意组件的前缀，现在先核对 `release.repo == component.downloadRepo`
才用 —— 匹配不到只是运气好，匹配到就是装错包。

> 现代应用用的是 **FCM**（走 GmsCore 自己的通道），不再经过 GSF。
> 所以这个槽位对绝大多数人是**不需要**的，但成本只有 21KB，留着无妨。

---

## 六、一句话总结

GBox 用「打包原版 GMS + 伪造机型绕过认证」换来了开箱即用的体验，
代价是**合规风险 + 随时可能被 Google 封掉**。

本工程用「microG + 开源商店客户端 + 容器级安装路由」达到同样的用户体验，
代价是**用户需要自己点一下「装入应用商店」**。

考虑到前者随时可能失效、后者是可持续的，这个取舍是划算的。
