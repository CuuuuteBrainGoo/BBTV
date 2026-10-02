package top.bilitv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import top.bilitv.util.AppLog
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 地址容灾数据源：**在数据源内部**按候选列表依次换地址。
 *
 * ## 为什么这么做（这是踩过坑之后的结论）
 * 早先的版本把容灾放在 `Player.Listener.onPlayerError()` 里 —— 出错就 `setMediaSource()`
 * 换下一个地址。真机实测直接抛：
 *
 * ```
 * UnexpectedLoaderException: Unexpected IndexOutOfBoundsException: index (0) must be less than size (0)
 * ```
 *
 * 原因：`onPlayerError` 回调执行时 ExoPlayer 还在给这次失败收尾，内部 `mediaPeriodQueue`
 * 已经空了。此时换源，Loader 线程一进去就摸到空队列。
 *
 * 把换地址下沉到 `DataSource` 层之后，**ExoPlayer 完全不知道底下换过地址**，
 * 它看到的始终是一次普通的 HTTP 请求，内部状态不会被搅乱。
 *
 * ## 换地址的两种时机
 * - `open()` 失败（连不上 / 403 / 超时）→ 试下一个
 * - `read()` 中途失败（读到一半断了）→ 标记当前地址作废，下次 `open()` 从下一个开始
 *
 * ## [openedFlag]：一个很小的信号，但决定了降级走哪条路
 * `open()` 成功过一次就置位。上层靠它区分两类**长得一模一样的失败**：
 *
 * | 现象 | `openedFlag` | 真实原因 | 正确的补救 |
 * |---|---|---|---|
 * | CDN 连不上 / 403 / 超时 | false | 网络 | 换地址、关音频 |
 * | 数据拿到了但播放器崩 | **true** | 解析器 | **换编码**，换地址没用 |
 *
 * 没有这个信号时，HEVC 解析崩溃会被当成网络问题，白白浪费一轮"关音频重播"（实测 0.1.2 就是这样）。
 *
 * @param upstream 真正的 HTTP 数据源工厂（带 Referer / UA）
 * @param urls 候选地址，**调用方负责排好序**（正规 CDN 优先，P2P 节点垫底）
 * @param openedFlag 成功打开过任一条地址时置位；传 null 表示不需要这个信号
 */
class FailoverDataSource(
    private val upstream: HttpDataSource.Factory,
    private val urls: List<String>,
    private val openedFlag: AtomicBoolean? = null,
) : DataSource {

    private var index = 0
    private var delegate: DataSource? = null

    private val label: String = if (urls.isEmpty()) "-" else hostOf(urls[0])

    override fun open(dataSpec: DataSpec): Long {
        // ExoPlayer 自己会按 LoadErrorHandlingPolicy 重试；重试时允许从头发起新一轮
        if (index >= urls.size) {
            AppLog.w("Stream", "[$label] 全部 ${urls.size} 个地址已失败过一轮，重头再来")
            index = 0
        }

        var last: IOException? = null
        for (i in index until urls.size) {
            val ds = upstream.createDataSource()
            val host = hostOf(urls[i])
            try {
                val length = ds.open(dataSpec.withUri(Uri.parse(urls[i])))
                delegate = ds
                index = i
                openedFlag?.set(true)
                AppLog.i("Stream", "[$label] 打开成功 ${i + 1}/${urls.size} $host")
                return length
            } catch (e: IOException) {
                runCatching { ds.close() }
                last = e
                AppLog.w(
                    "Stream",
                    "[$label] 打开失败 ${i + 1}/${urls.size} $host -> ${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }

        AppLog.e("Stream", "[$label] 所有 ${urls.size} 个地址都失败", last)
        throw last ?: IOException("所有候选播放地址都失败（共 ${urls.size} 个）")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val ds = delegate ?: return C.RESULT_END_OF_INPUT
        return try {
            ds.read(buffer, offset, length)
        } catch (e: IOException) {
            // 读到一半断了：作废当前地址，下次 open 从下一个开始
            runCatching { ds.close() }
            delegate = null
            index++
            AppLog.w(
                "Stream",
                "[$label] 读取中断于 $index/${urls.size} -> ${e.javaClass.simpleName}: ${e.message}",
            )
            throw e
        }
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun close() {
        runCatching { delegate?.close() }
        delegate = null
    }

    override fun addTransferListener(transferListener: TransferListener) {
        // 不做传输统计，忽略即可
    }

    private companion object {
        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')
    }
}

/** [FailoverDataSource] 的工厂。`openedFlag` 在所有实例间共享，见数据源类注释 */
class FailoverDataSourceFactory(
    private val upstream: HttpDataSource.Factory,
    private val urls: List<String>,
    private val openedFlag: AtomicBoolean? = null,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        FailoverDataSource(upstream, urls, openedFlag)
}
