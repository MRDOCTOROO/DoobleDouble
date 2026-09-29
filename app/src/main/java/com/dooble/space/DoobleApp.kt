package com.dooble.space

import android.app.Application
import android.content.Context

class DoobleApp : Application() {

    /**
     * 虚拟化容器的进程伪装必须在这里完成——这是系统给应用的最后一次机会，
     * 晚于此刻，进程身份就已经固化成宿主了。详见 [DoobleContainer.bootstrapHost]。
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (base != null) {
            DoobleContainer.bootstrapHost(this, base)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 引擎探测 + native 加载 + 供给层装配，全部在这里异步完成。
        // 不要在 onCreate 里同步等待，否则冷启动会被 native 加载拖慢。
        DoobleContainer.bootstrap(this)
    }
}
