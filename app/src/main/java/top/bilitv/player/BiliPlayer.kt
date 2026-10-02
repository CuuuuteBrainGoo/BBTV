package top.bilitv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import okhttp3.OkHttpClient
import top.bilitv.data.model.DashStream
import top.bilitv.data.settings.SettingsStore
import top.bilitv.util.AppLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B 站播放器。
 *
 * ## 音画分离
 * B 站给的是音/视频**两条独立的 fMP4**（不是标准 MPD），所以用两条
 * [ProgressiveMediaSource] 加 [MergingMediaSource] 合成一路播放。
 * 用 `ProgressiveMediaSource` 而不是 `DashMediaSource`，是因为拿到的就是可直接
 * Range 请求的碎片 MP4，自己造 MPD 反而多一层出错机会。
 *
 * ## 数据源用 OkHttp
 * 自带的 `DefaultHttpDataSource` 基于 `HttpURLConnection`，不做 IPv4/IPv6 双栈竞速。
 * 手机上如果 CDN 域名有 AAAA 记录而 IPv6 实际不通，它会先连 IPv6 干等到超时。
 * OkHttp 有 Happy Eyeballs，两栈竞速。连接池行为在国产 ROM 上也更可预测。
 *
 * **独立 OkHttp 实例，不带任何 B 站 Cookie** —— CDN 是第三方域名，没理由拿到登录凭据。
 *
 * ## 地址容灾放在数据源层（不是播放器层）★
 * 候选地址交给 [FailoverDataSource]，换地址对 ExoPlayer 完全透明。
 *
 * 早先的版本是在 `onPlayerError` 里 `setMediaSource()` 换地址，真机直接抛：
 * `UnexpectedLoaderException: Unexpected IndexOutOfBoundsException: index (0) must be less than size (0)`
 * —— 回调执行时 ExoPlayer 还在给失败收尾、内部队列已清空，此时换源就会踩空。
 *
 * ## 降级链（由内到外三级）
 * ```
 *  ① 换播放地址        FailoverDataSource 内部消化，播放器无感
 *  ② 关音频、静音播视频   音频那一路彻底拿不到时（MergingMediaSource 是全有或全无）
 *  ③ 换编码重播          视频这一路"数据拿到了但解析不了"时 —— 交给上层
 * ```
 * 第 ③ 级**不在这个类里做**，因为换编码要重新选流，那属于 ViewModel 的活。
 * 这里只负责**判断该不该走到第 ③ 级**，然后通过 [onUnrecoverable] 上报。
 *
 * **为什么必须区分 ② 和 ③**：两者表面上都是 `ERROR_CODE_IO_UNSPECIFIED` 加一堆
 * CDN 域名，但真实原因完全相反 —— ② 是网络，③ 是解析器。
 * 用 `videoOpened`（数据源有没有成功打开过）就能一眼分开，见 [isUnparsable]。
 * 0.1.2 就是因为分不清，白白浪费一轮去关音频，而关音频对解析崩溃毫无作用。
 *
 * ## 内存
 * 低内存电视上把缓冲目标显式压到 48 MiB，并优先服从容量目标。
 */
class BiliPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val httpFactory: HttpDataSource.Factory =
        OkHttpDataSource.Factory(okHttp)
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    // B 站 CDN 校验来源，缺 Referer 会被拒
                    "Referer" to "https://www.bilibili.com/",
                    "Origin" to "https://www.bilibili.com",
                )
            )

    private val extractors = DefaultExtractorsFactory()
        // 让进度条拖动更准（碎片 MP4 常缺索引，靠码率估算）
        .setConstantBitrateSeekingEnabled(true)

    /**
     * 设置页「解码器」档位 —— 每次挑解码器时**现读**，改完设置下次起播即生效。
     *
     * ★ 接在 **`DefaultRenderersFactory`** 上，不是 `ExoPlayer.Builder`。
     * `ExoPlayer.Builder.setMediaCodecSelector()` **不存在**（1.4.1 和 1.5.1 都没有，
     * 已用 javap 逐个核过）—— 别照着记错的结论去写。
     */
    private val settings = SettingsStore(appContext)

    private val renderersFactory = DefaultRenderersFactory(appContext)
        .setMediaCodecSelector(DecoderSelector.live { settings.decoderName })

    val exo: ExoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(20_000, 40_000, 1_500, 3_000)
                .setTargetBufferBytes(48 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        )
        .build()

    // ---- 状态

    private var selection: StreamSelector.Selection? = null
    fun currentSelection(): StreamSelector.Selection? = selection
    private var videoUrls: List<String> = emptyList()
    private var audioUrls: List<String> = emptyList()

    /**
     * 视频这一路的数据源**成功打开过**吗。
     *
     * 每次 [play] 清零。用来把"网络不通"和"数据到手但解析不了"分开 ——
     * 见 [isUnparsable]。这是降级链第 ② 级和第 ③ 级的分水岭。
     */
    private val videoOpened = AtomicBoolean(false)

    /**
     * 会话号。每次 [play] 自增。
     * 延迟执行的重试动作靠它判断"这期间用户是不是已经换了视频"，避免串台。
     */
    private var session = 0

    /** 有降级/重试动作正在排队，防止错误重复回调时叠加执行 */
    private var retryPending = false

    /** 音频已放弃，正在静音播视频 */
    var audioDisabled: Boolean = false
        private set

    /**
     * 这一轮是不是**已经在不用 P2P 节点的模式下**起播了。
     *
     * 「自动跳过 P2P 再试一轮」这条退路靠它防重复：已经滤过一次还失败，
     * 说明问题不在 P2P，再来一轮只会让用户多看一次黑屏。
     */
    private var p2pAlreadySkipped = false

    /** 当前在播的视频编码（界面显示用，让"换编码降级"看得见） */
    var currentVideoCodecs: String = ""
        private set

    /**
     * 播放器自己已经无计可施，需要上层决定怎么办。
     *
     * 两种情形会触发：
     *  - 音频关了、视频这一路还是失败
     *  - 判定为**解析类错误**（数据拿到了、播放器解不了）—— 此时关音频毫无意义
     *
     * 上层（ViewModel）通常的做法是**换个编码重播**（见 `docs/08` §8）。
     */
    var onUnrecoverable: ((PlaybackException) -> Unit)? = null

    /** 音频彻底拿不到、已切成静音播视频时回调（界面层提示一下，免得用户以为坏了） */
    var onAudioDisabled: (() -> Unit)? = null
    var onPlaybackStutter: ((Int, Long, Float) -> Unit)? = null

    init {
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long,
            ) {
                AppLog.i("PlaybackPerf", "解码器=$decoderName 初始化=${initializationDurationMs}ms")
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long,
            ) {
                AppLog.w("PlaybackPerf", "视频掉帧=$droppedFrames 采样=${elapsedMs}ms 缓冲=${exo.totalBufferedDuration}ms")
                if (exo.isPlaying) onPlaybackStutter?.invoke(droppedFrames, elapsedMs, exo.videoFormat?.frameRate ?: 0f)
            }

            override fun onVideoDisabled(
                eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters,
            ) {
                decoderCounters.ensureUpdated()
                AppLog.i("PlaybackPerf", "本次渲染=${decoderCounters.renderedOutputBufferCount} " +
                    "掉帧=${decoderCounters.droppedBufferCount} " +
                    "连续掉帧=${decoderCounters.maxConsecutiveDroppedBufferCount}")
            }
        })
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleError(error)

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) exo.videoDecoderCounters?.let { counters ->
                    counters.ensureUpdated()
                    AppLog.i("PlaybackPerf", "暂停／缓冲时渲染=${counters.renderedOutputBufferCount} " +
                        "掉帧=${counters.droppedBufferCount} 连续掉帧=${counters.maxConsecutiveDroppedBufferCount}")
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    // 时长和实际编解码是最容易出意外的两项，单独记一条
                    AppLog.i(
                        "Player",
                        "READY 时长=${exo.duration}ms 画面=${exo.videoSize.width}x${exo.videoSize.height} " +
                            "视频编码=${exo.videoFormat?.codecs} 音频编码=${exo.audioFormat?.codecs}"
                    )
                    return
                }
                AppLog.i("Player", "状态 -> " + when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> playbackState.toString()
                })
            }

            override fun onRenderedFirstFrame() {
                AppLog.i("Player", "首帧已渲染")
            }
        })
    }

    /**
     * 换流起播。同一时刻只播一路，直接 setMediaSource 即可
     *
     * @param skipP2p 设置页的「跳过 P2P 加速节点」。默认 false —— 正常路径下
     *   P2P 只是被排到候选末尾，不用调用方操心。见 [CdnOrder.order]
     * @param cdnPreference 设置页的「指定 CDN 关键词」。空串 = 不干预
     */
    fun play(sel: StreamSelector.Selection, skipP2p: Boolean = false, cdnPreference: String = "") {
        session++
        selection = sel
        videoUrls = candidates(sel.video, skipP2p, cdnPreference)
        audioUrls = sel.audio?.let { candidates(it, skipP2p, cdnPreference) }.orEmpty()
        audioDisabled = audioUrls.isEmpty()
        videoOpened.set(false)
        currentVideoCodecs = sel.video.codecs
        // 用户本来就选了「跳过 P2P」的话，这条退路从第一轮起就已经用掉了
        p2pAlreadySkipped = skipP2p
        // 上一次可能是直播；不还回去的话，点播页的错误提示会按直播的规则解释
        isLive = false

        AppLog.i("Player", "== 起播 session=$session ==")
        AppLog.i(
            "Player",
            "解码器档位：${DecoderSelector.label(settings.decoderName)}（过滤后为空会自动回落系统默认）"
        )
        AppLog.i(
            "Player",
            "选中视频：${sel.video.codecs} ${sel.video.width}x${sel.video.height} " +
                "bw=${sel.video.bandwidth} q=${sel.video.qualityId}"
        )
        sel.audio?.let { AppLog.i("Player", "选中音频：${it.codecs} bw=${it.bandwidth}") }
            ?: AppLog.w("Player", "选流阶段就没有可用音频")
        AppLog.i("Player", "视频 ${videoUrls.size} 个候选：" + videoUrls.joinToString(" | ") { hostOf(it) })
        AppLog.i("Player", "音频 ${audioUrls.size} 个候选：" + audioUrls.joinToString(" | ") { hostOf(it) })
        if (audioDisabled) AppLog.w("Player", "没有可用音频流，直接静音起播")

        open()
    }

    /** 换视频先结束旧会话，取消迟到重试并释放旧媒体缓冲；复用播放器实例。 */
    fun stop() {
        session++
        main.removeCallbacksAndMessages(null)
        retryPending = false
        exo.stop()
        exo.clearMediaItems()
    }

    fun release() {
        session++
        main.removeCallbacksAndMessages(null)
        retryPending = false
        onUnrecoverable = null
        onAudioDisabled = null
        onPlaybackStutter = null
        exo.release()
        val dispatcher = okHttp.dispatcher
        dispatcher.executorService.execute {
            // TLS close 会写网络；退出页主线程只释放播放器，连接在后台收尾。
            try {
                dispatcher.cancelAll()
                okHttp.connectionPool.evictAll()
            } finally {
                dispatcher.executorService.shutdown()
            }
        }
    }

    /**
     * 退路：**把候选表里的 P2P 节点全部去掉，原地重开一次**。
     *
     * ## 为什么需要它（和上层"换编码重播"的区别）
     *
     * 上层那条 `onUnrecoverable` → 换 AVC 的路，解决的是"**编码**这个视频解不了"。
     * 还有一类失败跟编码无关：候选表里排前面的几条 P2P / 镜像地址在这条宽带上就是不通，
     * 而 [FailoverDataSource] 已经按顺序全试过一遍了。
     * 这时**唯一还有意义的一步**是把整个候选表换成另一批地址重来 ——
     * 换编码是白费，因为地址压根没连上，播放器根本没机会碰到编码。
     *
     * ## 为什么是"原地重开"而不是重取流
     *
     * 播放地址的签名有效期是 1 小时（`docs/19` 那条同理），几秒钟的重试完全在有效期内，
     * 重问接口只会多一次网络往返。所以直接用手上这份 `selection` 重排。
     *
     * @return 还有没有值得试的地址。**false = 没得试了**，交给上层报错
     */
    fun retryWithoutP2p(skipP2p: Boolean, cdnPreference: String = ""): Boolean {
        val sel = selection ?: return false
        if (isLive) return false            // 直播地址是单条、不带 P2P 表，不适用
        if (p2pAlreadySkipped) return false // 已经滤过一轮，再来一轮没意义

        val newVideo = candidates(sel.video, skipP2p = true, cdnPreference = cdnPreference)
        // 滤完和原来一模一样（说明候选表里本来就没有 P2P）→ 这一轮等于重播，不做
        val before = videoUrls
        if (!before.isNotEmpty() && newVideo.isEmpty()) return false
        if (newVideo == before && before.none { CdnOrder.isP2pNode(it) }) return false

        p2pAlreadySkipped = true
        videoUrls = newVideo
        audioUrls = sel.audio?.let { candidates(it, skipP2p = true, cdnPreference = cdnPreference) }.orEmpty()
        videoOpened.set(false)
        AppLog.w("Player", "退路：去掉 P2P 节点重开（视频 ${newVideo.size} 个候选：" +
                newVideo.joinToString(" | ") { hostOf(it) } + "）")
        open()
        return true
    }

    // ---------------------------------------------------------------- 直播

    /**
     * 起播一路**直播**。
     *
     * ## 和点播（[play]）的三处不同
     *
     * | | 点播 | 直播 |
     * |---|---|---|
     * | 形态 | 音画分离的两条 fMP4 | **一条 HLS**（音画都在里面） |
     * | 时长 | 有 | **没有** —— `duration` 是 `C.TIME_UNSET` |
     * | 容灾 | [FailoverDataSource] 多地址轮换 | **不能用**，见下 |
     *
     * ## ★ 为什么直播不能用 FailoverDataSource
     *
     * 那个数据源干的事是"把请求的 URI 替换成候选列表里的一条"：
     *
     * ```kotlin
     * ds.open(dataSpec.withUri(Uri.parse(urls[i])))   // ← 无条件换成我们的地址
     * ```
     *
     * 点播这么写没问题 —— 整条流只有一个地址、一次 open。
     * 但 **HLS 不是**：播放器要先拉 m3u8，再按里面的相对路径去拉**几十上百个分片**。
     * 用了那个数据源，所有分片请求都会被换成 m3u8 的地址，
     * 表现是"播放列表读到了，但一个分片都解不出来"。
     *
     * 所以直播**直接用 [httpFactory]**，让它老老实实按 URI 请求。
     * 代价是没有多地址轮换 —— 但直播的地址是服务端按房间现算的，
     * 失败时正确的做法是**重新问一次接口拿新地址**，而不是拿旧地址重试。
     * 那一层由 ViewModel 负责（见 `PlayerScreen` 的直播分支）。
     *
     * @param url 播放地址。**必须是完整地址**，拼接规则见 `LivePlayInfo` 的注释
     * @param hls true 走 HLS 解析器；false 走普通渐进式（`flv` 属于这类）。
     *   实测 HLS 那条才稳，所以正常都会传 true
     */
    fun playLive(url: String, hls: Boolean = true) {
        session++
        selection = null
        videoUrls = listOf(url)
        audioUrls = emptyList()
        /*
         * ★ 直播**不能**把 audioDisabled 置为 true。
         *
         * 那个标志的含义是"音频那一路彻底拿不到"，点播里它会让 `open()`
         * 走"只挂视频源"的分支；而直播是**单条流、音画都在里面**，
         * 它下面的降级链（关音频 → 换编码）整套都不适用。
         * 置 true 会让 [describeState] 报一句"音频已放弃"，
         * 排查时把自己带沟里。
         */
        audioDisabled = false
        videoOpened.set(false)
        currentVideoCodecs = ""
        isLive = true

        AppLog.i("Player", "== 起播直播 session=$session ==")
        AppLog.i("Player", "直播地址协议=${if (hls) "HLS" else "渐进式"} host=${hostOf(url)}")

        val factory = httpFactory
        val source: MediaSource = if (hls) {
            HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))
        } else {
            ProgressiveMediaSource.Factory(factory, extractors).createMediaSource(MediaItem.fromUri(url))
        }
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * 这一路是不是直播。
     *
     * 上层要它来判断两件事：**要不要画进度条**（直播没有时长），
     * 以及**出错时该不该走换编码降级**（直播换编码要重新取流，不是重选流）。
     */
    var isLive: Boolean = false
        private set

    /** 已加载进播放器的清晰度（界面显示用） */
    fun currentQuality(): DashStream? = selection?.video

    /**
     * 设置播放倍速（2026-09-29）。
     *
     * Media3 的 `setPlaybackSpeed` 在 `STATE_IDLE` 也能调 —— 值会被记住，
     * 起播后自动生效。所以播放页可以在"还没起播"时就调，不用等 READY。
     *
     * ⚠️ 注意一个**已知行为**：换 `MediaSource`（切集/切清晰度）**不会**重置倍速 ——
     * 它是 ExoPlayer 实例级的状态。这对自动连播是好事（下一集延续当前倍速），
     * 但也意味着"从 1.5x 的那一集自动连播过去，下一集还是 1.5x"。
     * 这是刻意的（用户选了倍速就是想一路快看），不额外重置。
     */
    fun setSpeed(speed: Float) {
        runCatching { exo.setPlaybackSpeed(speed) }
            .onFailure { AppLog.w("Player", "设置倍速失败：${it.javaClass.simpleName}") }
    }

    /** 当前倍速（排查用） */
    fun currentSpeed(): Float = runCatching { exo.playbackParameters.speed }.getOrDefault(1f)

    /**
     * 出错时给排查用的状态快照。
     * 只报**域名**不报完整 URL —— 播放地址里带着时效签名参数，没必要进截图（见 SOUL.md 密钥红线）。
     */
    fun describeState(): String = buildString {
        if (isLive) {
            append("当前是「直播」（单条 HLS 流，音画在一起）")
            append("\n地址域名：").append(videoUrls.joinToString(" / ") { hostOf(it) })
            append("\n完整日志：播放页「日志」按钮")
            return@buildString
        }
        append("当前编码 ").append(currentVideoCodecs.ifBlank { "?" })
        append("（视频数据源").append(if (videoOpened.get()) "已成功打开过" else "从未打开成功").append("）")
        append("\n视频候选 ").append(videoUrls.size).append(" 个：")
        append(videoUrls.joinToString(" / ") { hostOf(it) })
        if (audioDisabled) {
            append("\n音频已放弃，正在静音播放")
        } else {
            append("\n音频候选 ").append(audioUrls.size).append(" 个：")
            append(audioUrls.joinToString(" / ") { hostOf(it) })
        }
        append("\n完整日志：播放页「日志」按钮")
    }

    // ---------------------------------------------------------------- 内部

    private fun open() {
        if (videoUrls.isEmpty()) {
            AppLog.e("Player", "没有任何视频候选地址，无法起播")
            return
        }
        val videoSource = progressive(videoUrls, videoOpened)
        val audioSource = if (audioDisabled || audioUrls.isEmpty()) null else progressive(audioUrls, null)

        AppLog.i("Player", "setMediaSource（音频：" + (if (audioSource == null) "关" else "开") + "）")
        exo.setMediaSource(if (audioSource == null) videoSource else MergingMediaSource(videoSource, audioSource))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun progressive(urls: List<String>, openedFlag: AtomicBoolean?): MediaSource =
        ProgressiveMediaSource.Factory(FailoverDataSourceFactory(httpFactory, urls, openedFlag), extractors)
            .createMediaSource(MediaItem.fromUri(urls.first()))

    /**
     * 播放出错。
     *
     * ## 这里踩过一个大坑（真机实测，见 `docs/08`）
     * 早先的版本在这个回调里**同步** `setMediaSource()` 换地址，结果抛：
     * `UnexpectedLoaderException: Unexpected IndexOutOfBoundsException: index (0) must be less than size (0)`。
     *
     * 原因：回调执行时 ExoPlayer 还在给这次失败收尾，内部 `mediaPeriodQueue` 已经清空。
     * 此时换源，Loader 线程一进去就摸到空队列。
     *
     * ## 现在的防护
     * 1. `postDelayed` 挪到消息队列 —— 绝不在回调体内换源
     * 2. 延迟 [RETRY_DELAY_MS]，等 ExoPlayer 从失败里彻底收敛回 IDLE
     * 3. `session` + [retryPending] 双重防重，避免错误重复回调时动作叠加
     *
     * 地址级的重试已经由 [FailoverDataSource] 在数据源内部消化，轮不到这里。
     *
     * ## 走哪条降级路
     * 先看 [isUnparsable]：
     *  - **是**（数据拿到了、播放器解不了）→ 直接上报 [onUnrecoverable]，
     *    关音频纯属浪费一轮，因为问题根本不在音频
     *  - **否**（真是网络/资源问题）→ 还有音频就关音频试试，没有就上报
     */
    private fun handleError(error: PlaybackException) {
        val mySession = session
        AppLog.e("Player", "播放错误 ${error.errorCodeName}（session=$mySession）", error)

        if (retryPending) {
            AppLog.w("Player", "已有待执行的降级动作，忽略本次重复回调")
            return
        }
        retryPending = true

        main.postDelayed({
            retryPending = false
            if (mySession != session) {
                AppLog.w("Player", "忽略过期 session=$mySession 的错误（当前 $session）")
                return@postDelayed
            }

            /*
             * 直播直接上报，不走下面那套降级。
             *
             * 理由：那套降级（关音频 → 换编码）是**为点播的音画分离结构设计的**。
             * 直播只有一条流、音画都在里面，关掉音频就等于把整条流关了；
             * 换编码则要重新问接口取流，那是 ViewModel 的事。
             * 硬套的话，用户会看到画面反复起停，而真正该做的是重取地址。
             */
            if (isLive) {
                AppLog.e("Player", "直播流失败 -> 交给上层重新取流")
                onUnrecoverable?.invoke(error)
                return@postDelayed
            }

            if (isUnparsable(error)) {
                AppLog.e(
                    "Player",
                    "判定为解析类错误（视频数据已成功打开过，不是网络问题）" +
                        "-> 跳过静音降级，交给上层换编码",
                )
                onUnrecoverable?.invoke(error)
                return@postDelayed
            }

            if (!audioDisabled && audioUrls.isNotEmpty()) {
                audioDisabled = true
                AppLog.w("Player", "音频路彻底失败 -> 降级为静音播视频")
                onAudioDisabled?.invoke()
                open()
            } else {
                AppLog.e("Player", "视频路也失败，已无降级手段，上报上层")
                onUnrecoverable?.invoke(error)
            }
        }, RETRY_DELAY_MS)
    }

    /**
     * 播放器层**自己**能做的最后一条退路：去掉 P2P 节点重开。
     *
     * 放在 [handleError] 之外、由上层决定何时调用，是因为它要读设置
     * （「起播卡住时自动跳过 P2P 再试）」—— 播放器不认识设置页，那属于上层的事。
     *
     * 上层调用时机：**网络类**失败上报之前。解析类失败（换编码那条路）不叫它，
     * 那类问题换地址没用。
     */
    fun canRetryWithoutP2p(): Boolean = !isLive && selection != null && !p2pAlreadySkipped

    /**
     * 是不是「数据已经拿到手了，但播放器解析不了」。
     *
     * 两个条件同时满足才算：
     *
     * 1. **错误码是 [PlaybackException.ERROR_CODE_IO_UNSPECIFIED]**。
     *    `ExoPlaybackException` 的映射规则是：
     *      - 原始异常是 `HttpDataSourceException` → 具体的 `ERROR_CODE_IO_NETWORK_*` / `IO_HTTP_*`
     *      - 是 `ParserException` → `ERROR_CODE_PARSING_*`
     *      - **其它 RuntimeException → `ERROR_CODE_IO_UNSPECIFIED`**
     *    所以"什么都没匹配上"恰恰是解析器抛无关异常的指纹 ——
     *    HEVC 解析崩溃（`IndexOutOfBoundsException` 被 `Loader` 包成
     *    `UnexpectedLoaderException`）正是这一类。
     *
     * 2. **视频数据源成功打开过**（[videoOpened]）。
     *    否则就是"一个 CDN 地址都没连上"，那还是网络问题，该走静音降级/换地址。
     */
    private fun isUnparsable(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED && videoOpened.get()

    /**
     * 候选地址：**正规 CDN 优先，P2P 加速节点垫底**。
     *
     * 排序规则本体搬到了 [CdnOrder]（纯逻辑、有单测）；这里只负责把
     * `baseUrl` + `backupUrl` 拼成一张表。详见 [CdnOrder.isP2pNode] 的说明。
     *
     * @param cdnPreference 高级模式的「指定 CDN 关键词」。空串 = 不干预。
     *   在 P2P 排序**之后**应用：用户指定的是"用哪条 CDN"，
     *   而我们自己那条"P2P 垫底"的安全判断优先级更高。
     */
    private fun candidates(
        stream: DashStream,
        skipP2p: Boolean,
        cdnPreference: String = "",
    ): List<String> {
        val ordered = CdnOrder.order(
            buildList {
                add(stream.baseUrl)
                addAll(stream.backupUrls)
            },
            skipP2p = skipP2p,
        )
        return PlayTolerance.orderByPreference(ordered, cdnPreference)
    }

    private fun hostOf(url: String): String = CdnOrder.hostOf(url)

    private companion object {
        /** 出错后等这么久再动播放器，让 ExoPlayer 从失败里彻底收敛回 IDLE */
        const val RETRY_DELAY_MS = 250L

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
