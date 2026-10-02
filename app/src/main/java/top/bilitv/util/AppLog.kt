package top.bilitv.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 极简文件日志。
 *
 * ## 为什么自己写
 * - **不用 Logcat**：电视和手机上都没有 adb 可接，出问题时只能靠 App 自己把日志带出来。
 *   少爷实测反馈靠截图，日志靠「分享」导出。
 * - **不引 Timber / Logback**：这点需求不值得加依赖，也不值得为它铺一套初始化流程。
 *
 * ## 两块输出
 * - 内存里保留最近 [MAX_LINES] 条 → 播放页的日志面板直接显示
 * - 追加写 `Android/data/<包名>/files/logs/bilitv.log` → 「分享日志」导出给外部
 *
 * 内存记录即时更新，文件按相同顺序后台写入；错误和导出等待落盘。
 */
object AppLog {

    private const val MAX_LINES = 600
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val MAX_CAUSE_DEPTH = 5
    private const val MAX_MSG = 300

    /**
     * 每个异常打几帧调用栈。
     *
     * **这是用真机轮次换来的教训**：0.1.2 的日志只有异常链的**消息**，
     * 于是只看到「`IndexOutOfBoundsException: index (0) must be less than size (0)`」——
     * 一个可以来自任何地方的越界。真相埋在帧里（`HevcConfig.parseImpl`），
     * 却要再走一轮真机才能拿到，而少爷装一次包的成本很高。
     *
     * 4 帧足够：`HevcConfig` → `BoxParser` → `Mp4Extractor` → `BundledExtractorsAdapter`。
     */
    private const val MAX_FRAMES = 4

    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val recent = ArrayDeque<String>()
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BBTV-log").apply { isDaemon = true }
    }

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "logs")
        runCatching { if (!dir.exists()) dir.mkdirs() }
        val f = File(dir, "bilitv.log")
        // 超过上限直接重开，不做滚动历史 —— 排查要的是「这一次」的日志
        runCatching { if (f.length() > MAX_FILE_BYTES) f.delete() }
        file = f
        i("AppLog", "日志就绪 -> ${f.absolutePath}")
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String) = write("W", tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    /**
     * 把异常链摊平成多行。
     *
     * 本项目吃过这个亏：界面上只显示一个 `ERROR_CODE_IO_UNSPECIFIED` 时根本无从下手，
     * 真正的根因埋在 `cause` 链的第三层（`IndexOutOfBoundsException`）。
     *
     * 更进一步的教训是：**光有消息不够，必须带帧**。同一个消息可能来自几十处，
     * 只有 `类名.方法(文件:行号)` 才能一次定位。见 [MAX_FRAMES] 的说明。
     */
    fun throwableChain(t: Throwable, indent: String = "      "): String {
        val sb = StringBuilder()
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth < MAX_CAUSE_DEPTH) {
            sb.append(indent).append(if (depth == 0) "· " else "← ").append(c.javaClass.name)
            c.message?.let { sb.append(": ").append(it.take(MAX_MSG)) }
            sb.append('\n')
            c.stackTrace.take(MAX_FRAMES).forEach { f ->
                sb.append(indent).append("  at ").append(f.className).append('.')
                    .append(f.methodName).append('(').append(f.fileName).append(':')
                    .append(f.lineNumber).append(")\n")
            }
            c = c.cause
            depth++
        }
        return sb.toString().trimEnd('\n')
    }

    /**
     * 界面用的紧凑版：只给**根因**的最近几帧。
     *
     * 异常链的帧往往几百行，全塞进界面会把屏幕挤爆、把关键信息顶出去。
     * 根因才是"崩在哪一行"，而链上的 `UnexpectedLoaderException` 那种包装层
     * 帧全部指向 `Loader$LoadTask.run`，没有信息量。
     */
    fun rootFrames(t: Throwable, max: Int = 6): List<String> {
        val root = generateSequence(t) { it.cause }.last()
        return root.stackTrace.take(max).map { f ->
            "${f.className.substringAfterLast('.')}.${f.methodName}:${f.lineNumber}"
        }
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        synchronized(lock) {
            val head = "${time.format(Date())} $level/$tag: $msg"
            val block = if (t == null) head else "$head\n${throwableChain(t)}"
            recent.addLast(block)
            while (recent.size > MAX_LINES) recent.removeFirst()
            file?.let { f -> writer.execute { runCatching { f.appendText(block + "\n") } } }
        }
        if (level == "E") flush()
    }

    /** 界面显示用：最近日志全文 */
    fun snapshot(): String = synchronized(lock) { recent.joinToString("\n") }

    /** 分享导出用 */
    fun exportPath(): String? {
        flush()
        return file?.takeIf { it.exists() }?.absolutePath
    }

    private fun flush() {
        runCatching { writer.submit { }.get(2, TimeUnit.SECONDS) }
    }

    fun clear() = synchronized(lock) {
        recent.clear()
        file?.let { f -> writer.execute { runCatching { f.writeText("") } } }
    }
}
