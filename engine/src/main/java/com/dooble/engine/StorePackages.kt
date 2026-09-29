package com.dooble.engine

/**
 * 容器内可以充当「应用商店」的包名，**按优先级排列**。
 *
 * ## 为什么放在 engine 而不是 gms
 *
 * 这不是"谷歌环境"的知识，而是**容器**的知识：容器在解析
 * `market://` / `play.google.com` 链接时，需要知道该把链接交给谁。
 * 供给层（gms）决定"往空间里装哪个商店"，引擎决定"空间内的链接该路由到谁"，
 * 两者共用同一份名单才能保证不脱节——所以名单定义在这里，
 * `StoreProvisioner.PRIMARY_PACKAGE` 反过来引用它。
 *
 * ## 为什么 Aurora 优先于 FakeStore
 *
 * `com.android.vending` 在 microG 栈里是 **FakeStore**：它只提供"应用已安装"
 * 这个查询的占位实现，本身没有界面，也没有下载能力。把商店链接交给它，
 * 用户看到的是"点了没反应"。所以必须排在有真实界面的 Aurora Store 之后。
 */
object StorePackages {

    /** Aurora Store：Google Play 的开源客户端，本工程的主力商店 */
    const val AURORA = "com.aurora.store"

    /** microG 的 FakeStore 占位包，只在没有 Aurora 时兜底 */
    const val FAKE_STORE = "com.android.vending"

    val ALL: List<String> = listOf(AURORA, FAKE_STORE)
}
