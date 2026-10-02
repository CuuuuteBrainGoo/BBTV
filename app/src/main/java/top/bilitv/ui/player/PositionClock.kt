package top.bilitv.ui.player

/**
 * 播放位置的连续时钟 —— 弹幕流畅的支点。
 *
 * ## 它解决的问题
 *
 * 弹幕是 60fps 的连续动画，但"现在播到哪儿了"这件事**只能低频问播放器**：
 * 问一次要穿过 JNI、要过播放器的锁，而且这个值本身还跟着音频时钟走（十几毫秒才真前进一次）。
 * 进度文字（精确到秒）、广告判断、弹幕分段预取也都只需要 250ms 这个量级。
 *
 * 于是就有了那句害了弹幕很久的注释 ——「250ms 一次：够弹幕/进度的观感」。
 * 按一个 250ms 才动一次的值去画动画，结果就是：**一秒 60 帧里只有 4 帧拿到新位置，
 * 中间 15 帧钉在原地，然后猛跳一下**。用户看到的就是"卡顿、不连贯"。
 *
 * ## 解法
 *
 * 把低频采样当成**基准点**（[sample]），中间按墙钟往前推（[read]）。
 * `read` 是纯算术，每帧调用也不要紧；误差只等于采样那一刻播放器自身的粒度（十几毫秒）。
 *
 * ## 为什么不干脆把 ticker 提到 60Hz
 *
 * 那会把进度文字、广告判断、分段预取全部跟着按 60Hz 重算。
 * 电视盒子常年开机，这种白烧电的事不能干。
 *
 * ## 为什么单独一个类
 *
 * 一是把设计集中在一处；二是 `SystemClock` 在 JVM 单测里是空壳，
 * 把时间源做成构造参数就能在单测里把时间攥在手里，逐帧断言"没有一帧原地不动"。
 */
internal class PositionClock(private val now: () -> Long) {

    /** 最近一次采样到的位置。低频值，给进度文字这类"看得出毫秒级差别才怪"的地方用 */
    var sampledMs = 0L
        private set

    private var anchorMs = -1L
    private var anchorAt = 0L

    /** 换视频时调用：基准置空，在第一次 [sample] 之前 [read] 一律返回 0 */
    fun reset() {
        sampledMs = 0L
        anchorMs = -1L
    }

    /** 记一次采样，同时把"当时是几点"钉下来，供 [read] 往后外推 */
    fun sample(ms: Long) {
        sampledMs = ms
        anchorMs = ms
        anchorAt = now()
    }

    /**
     * 当前播放位置 —— **每一帧都会被调用**。
     *
     * @param playing 暂停时直接返回采样值：暂停就该定住，不能继续往前爬。
     *   同时**把基准重新钉在"现在"** —— 这一点是单测逼出来的：
     *   不钉的话，恢复播放的瞬间会拿一个陈旧的 `anchorAt` 去算，
     *   把整段暂停时长当成"播放过去了"一次性补上，弹幕直接窜出去。
     *
     *   （实际上 ticker 暂停时也照常每 250ms 采一次，所以退一万步最多也只差 250ms。
     *   但这种事没必要靠"另一个组件碰巧也在干活"来兜底。）
     */
    fun read(playing: Boolean): Long {
        if (anchorMs < 0L) return sampledMs
        if (!playing) {
            anchorMs = sampledMs
            anchorAt = now()
            return sampledMs
        }
        return anchorMs + (now() - anchorAt)
    }
}
