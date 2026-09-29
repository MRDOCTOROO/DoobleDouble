package com.dooble.engine.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 容器内安装事件。
 *
 * 为什么需要一条"事件"而不是直接回调：安装请求发生在**虚拟应用的进程**里
 * （`ActivityManagerCommonProxy` 拦截到 `application/vnd.android.package-archive`
 * 的 Intent 时回调宿主），而 UI 跑在主进程。两者之间没有现成的通道，
 * 用文件做单向日志是最稳的做法——不依赖 Binder 注册时序，进程被杀也不丢。
 */
@Serializable
data class InstallEvent(
    val timestamp: Long,
    /** 发起安装的空间在引擎里的 userId */
    val userId: Int,
    /** 解析出的包名，解析失败时为空 */
    val packageName: String = "",
    val label: String = "",
    val ok: Boolean,
    val message: String = "",
) {
    /** 空间 id 由读日志的一方按 userId 反查，日志里不冗余存，避免两边不一致 */
    val timeText: String
        get() = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
}

/**
 * 安装事件日志的**文件层**。
 *
 * ## 为什么是一个类，而不是继续塞在 [InstallEventLog] 里
 *
 * 两个原因，缺一不可：
 *
 * 1. **格式契约必须有测试**。这个文件的写入方在虚拟应用进程、读取方在主进程，
 *    两边之间只有"JSON 字段叫什么"这一个约定。写方改了字段名、读方照旧，
 *    结果是**编译通过、单测通过、运行时静默不生效**——装完应用 UI 上看不到。
 * 2. **`object` 单例没法测**。[InstallEventLog] 的 `file` 一旦 `init` 就固定了，
 *    第二个用例想换个临时目录再跑一遍是做不到的。把文件路径作为构造参数，
 *    每个用例开自己的临时目录，互不干扰。
 *
 * 所以路径是构造参数、不含任何静态状态，可以随便 new。
 */
class InstallEventFile(private val file: File) {

    /** 日志文件位置。暴露出来是为了让测试能直接往文件里塞坏行，模拟半行写入 */
    val path: File get() = file

    /** 追加一条事件。写失败静默吞掉——日志丢一条远好过让安装流程崩掉 */
    fun append(event: InstallEvent) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(ENCODE.encodeToString(InstallEvent.serializer(), event) + "\n")
            trim()
        }
    }

    /**
     * 读取全部事件并清空。
     *
     * 之所以"读后即清"而不是保留历史：这些事件只用于"刚刚装了什么"的即时反馈，
     * 空间内的应用列表本身就是持久状态，不需要日志再存一份。
     */
    fun drain(): List<InstallEvent> {
        val events = readAll()
        runCatching { file.writeText("") }
        return events
    }

    /** 只读快照，不清空。用于自检面板展示 */
    fun peek(): List<InstallEvent> = readAll()

    private fun readAll(): List<InstallEvent> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { decode(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * 只保留最近 [MAX_KEEP] 条，防止日志无限增长。
     *
     * 容器进程长期运行时会不断追加，而只有 UI 打开时才 `drain`——
     * 中间这段时间没人消费，没有上限就是无界增长。
     */
    private fun trim() {
        runCatching {
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.size <= MAX_KEEP) return
            file.writeText(lines.takeLast(MAX_KEEP).joinToString("\n", postfix = "\n"))
        }
    }

    companion object {
        const val FILE_NAME = "install-events.jsonl"

        /** 保留的最大条数 */
        const val MAX_KEEP = 200

        val ENCODE = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * 解析一行。任何一处不合法都返回 null 让调用方跳过那一条，绝不抛异常——
         * 一条写坏的日志不应该让整个"刚刚装了什么"的列表消失。
         */
        fun decode(line: String): InstallEvent? {
            if (line.isBlank()) return null
            return runCatching {
                ENCODE.decodeFromString(InstallEvent.serializer(), line)
            }.getOrNull()
        }
    }
}

/**
 * 安装事件的落盘日志（JSONL，一行一条）的进程内单例入口。
 *
 * 真正的读写都在 [InstallEventFile] 里，这里只负责"文件在哪"这件事。
 *
 * 写入方是容器进程（[com.dooble.engine.bcore.ContainerInstallRelay]），
 * 读取方是 UI。**只追加、由读者消费后截断**。
 */
object InstallEventLog {

    @Volatile
    private var impl: InstallEventFile? = null

    fun init(filesDir: File) {
        if (impl != null) return
        val dir = File(filesDir, "dooble").apply { mkdirs() }
        impl = InstallEventFile(File(dir, InstallEventFile.FILE_NAME))
    }

    /** 容器进程调用：追加一条事件 */
    fun record(event: InstallEvent) {
        impl?.append(event)
    }

    /** UI 调用：读取全部事件并清空 */
    fun drain(): List<InstallEvent> = impl?.drain().orEmpty()

    /** 只读快照，不清空 */
    fun peek(): List<InstallEvent> = impl?.peek().orEmpty()
}
