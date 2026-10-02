package top.bilitv.data.model

/**
 * 开播状态。
 *
 * ## 为什么有 `UNKNOWN` 这个值
 *
 * 因为**不是每个接口都告诉你**。实测（`tools/probe_live.py`，2026-09-29）：
 *
 * | 接口 | 给不给 `live_status` |
 * |---|---|
 * | `/room/v1/room/get_user_recommend`（推荐流） | ❌ **30 条全是 `null`** |
 * | `/room/v1/Area/getRoomList`（分区流） | ❌ **压根没这个字段** |
 * | `/xlive/web-room/v1/index/getRoomBaseInfo`（房间详情） | ✅ 给，数字 `1` |
 * | `/xlive/web-room/v2/index/getRoomPlayInfo`（取流） | ✅ 给，数字 `1` |
 *
 * 没有它的时候如果按老的写法 `optInt("live_status", 0)` 兜底，
 * 列表上**每一个房间**都会显示「未开播」—— 编译过、单测绿、功能全死。
 * 2026-09-29 模拟器实测就是这么发现的第一版 bug。
 *
 * 所以取值只有两种来源：**接口给的** 或者 **我们推断的**。
 * 推断规则写在 `Parsers.liveStatusOf()` 里，两头都不要在这儿猜。
 */
object LiveStatus {
    /** 正在直播 */
    const val LIVING = 1

    /** 轮播 —— 房间开着，但放的是录像 */
    const val RERUN = 2

    /** 未开播。**必须是接口明确说的**，不要拿它当"没数据"的默认值 */
    const val OFFLINE = 0

    /** 不清楚。界面遇到它**什么都不画**，不许编一个结论出来 */
    const val UNKNOWN = -1
}

/**
 * 一个直播间。
 *
 * ## 为什么单独一个模型，不复用 [FeedItem]
 *
 * [FeedItem] 是"一个**视频**"：有 16:9 封面、有时长、有播放量。
 * 直播间的时长是**无限**、播放量是**在线人数**、还有"开播中/没开播"这个
 * 视频根本没有的状态。硬塞的话，卡片上又会到处是"这条到底是不是直播"的判断。
 *
 * ## ⚠️ 字段类型：实测是**数字**，但解析要两种都容得下
 *
 * 2026-09-29 实测（`tools/probe_live.py`）：`roomid` / `online` / `uid`
 * 在推荐流和分区流里**都是 JSON 数字**（`545068`，不是 `"545068"`）。
 * 早先这里写过"全是字符串"，那句是错的 —— 已改。
 *
 * 但解析层仍然走 `num()`（数字/字符串都能读）：B 站这类老接口历史上
 * 换过形态，而**读错了的表现是"人气永远是 0"**，不报错、不崩，
 * 只有肉眼能看出来。容错成本几乎为零，就这么留着。
 *
 * @param roomId 直播间号（长号）。**不是短号** —— 短号（如 7777）也能播，
 *   但接口给的一律是长号，用长号就不用担心两者不一致。
 * @param online 在线人数（"人气"）。0 表示接口没给。
 * @param liveStatus 见 [LiveStatus]。默认值是 [LiveStatus.UNKNOWN] 而**不是**
 *   `OFFLINE` —— 默认"不知道"不会撒谎，默认"没开播"会。
 * @param cover 16:9 封面。优先 `user_cover`（主播自己传的），退回 `system_cover`（系统截帧）。
 * @param face 主播头像。`getRoomBaseInfo` **没有**这个字段，那里留空。
 */
data class LiveRoom(
    val roomId: Long,
    val title: String,
    val uname: String,
    val uid: Long = 0L,
    val cover: String = "",
    val face: String = "",
    val areaName: String = "",
    val parentAreaName: String = "",
    val online: Long = 0L,
    val liveStatus: Int = LiveStatus.UNKNOWN,
) {
    /** 正在直播。列表上画不画那个红点全看它 */
    val isLiving: Boolean get() = liveStatus == LiveStatus.LIVING
}

/**
 * 直播分区。
 *
 * B 站的直播分区是**两层**：大区（网游/手游/娱乐/电台…）下面挂子区
 * （英雄联盟/王者荣耀/颜值…）。列表接口按**子区 id** 过滤，
 * 所以界面上的分区标签必须带着子区 id，不能只带大区名。
 */
data class LiveArea(val id: Int, val name: String, val subs: List<LiveAreaSub> = emptyList())

/** 直播子区。`id` 就是列表接口要的 `area_id` */
data class LiveAreaSub(val id: Int, val name: String)

/**
 * 一条直播播放线路。
 *
 * ## 为什么是"线路"而不是一条 URL
 *
 * 直播取流接口一次给 6 条组合（2 种协议 × 3 种封装 × 2 种编码），
 * 而**只有一部分真的能播**。实测结论（`tools/probe_live.py`）：
 *
 * | 协议/封装 | 实测 |
 * |---|---|
 * | `http_stream` / `flv` | 通（但本机网络下 20 秒超时，CDN 不通） |
 * | `http_hls` / `ts` | 通 |
 * | **`http_hls` / `fmp4`** | **通，且最稳 —— 我们选它** |
 *
 * @param protocol `http_stream` 或 `http_hls`
 * @param format `flv` / `ts` / `fmp4`
 * @param codec `avc` / `hevc` / `av1`
 * @param qn 清晰度编号。游客态实测只有 250（高清），`accept_qn` 里能看到
 *   10000（原画）/400（蓝光）是登录后才拿得到的。
 * @param urls 已拼好的**完整**地址。拼接规则见 [LivePlayInfo] 的说明 ——
 *   这里存成品，是不想让"怎么拼"这件事散落在播放器和界面两处。
 */
data class LiveStreamLine(
    val protocol: String,
    val format: String,
    val codec: String,
    val qn: Int,
    val urls: List<String>,
) {
    val isHls: Boolean get() = protocol == "http_hls"

    /**
     * HEVC。**两种写法都要认**（`hevc` 和 `hvc1`）—— 和点播那边的
     * [DashStream.isHevc] 是同一套判据，别在这里少写一半。
     *
     * 直播实测给的是 `hevc`，`hvc1` 是 ISO 那套命名，暂时没见到 ——
     * 但这是单测（`LiveTest`）抓出来的漏判，补上比"等它出现再说"便宜。
     */
    val isHevc: Boolean get() =
        codec.startsWith("hev", ignoreCase = true) || codec.startsWith("hvc", ignoreCase = true)

    /**
     * HLS + fMP4 —— **实测唯一在本机网络下稳定可播的组合**。
     *
     * 为什么不要 `flv`：它是 `http_stream`，没有分片概念，播放器只能顺序读；
     * 而且实测本机到那个 CDN 域名直接 20 秒超时。
     * 为什么不要 `ts`：能播，但 TS 封装对 HEVC 的支持不如 fMP4 完整，
     * 而且没有独立的初始化段，起播要多等一个分片。
     */
    val isPreferred: Boolean get() = isHls && format == "fmp4"
}

/**
 * 直播取流结果。
 *
 * ## ★★ URL 是怎么拼出来的（这里踩过一次）
 *
 * 三层嵌套，每一层的作用都不一样：
 *
 * ```
 *   data.playurl_info.playurl.stream[].format[].codec[].url_info[]
 *                    protocol_name  format_name  codec_name  { host, extra }
 * ```
 *
 * **`base_url` 在 `codec` 层，不在 `url_info` 层** —— 我第一次把它读成
 * `url_info.base_url`，得到一串空字符串，拼出来的地址全 404。
 * 而 `url_info` 里只有 `host` / `extra` / `stream_ttl` 三个字段。
 *
 * 正确的拼法：
 *
 * ```
 *   完整地址 = url_info.host + codec.base_url + url_info.extra
 * ```
 *
 * ⚠️ **`base_url` 自己就以 `?` 结尾**（`/live-bvc/xxx/index.m3u8?`）。
 * 再补一个 `?` 会变成 `??expires=…`，服务端直接 403 —— 这个也实测过。
 *
 * ## 分片地址还带不带签名
 *
 * 不带。m3u8 里的分片是**相对路径**（`423409323.m4s`），播放器按 RFC 相对解析时
 * 会把 query 丢掉，实测**照样 200**（签名只管播放列表本身）。
 * 所以 HLS 这条链路可以直接交给系统的 HLS 解析器，不需要自己改写分片地址。
 *
 * @param liveStatus 见 [LiveStatus]。取流接口**会给**这个字段。
 *   `playurl_info` 为空 + 这里是 [LiveStatus.RERUN] 就是"主播在放录像"，
 *   界面要说人话，不能报"播放失败"。
 *   判"能不能播"一律写 `!= LiveStatus.LIVING`，**不要**写 `== OFFLINE` ——
 *   这样万一哪天接口不给了（拿到 `UNKNOWN`），行为退化成"不播 + 说没开播"，
 *   而不是"当作在播、然后黑屏"。
 * @param lines 所有线路。界面/播放器按 [LiveStreamLine.isPreferred] 挑。
 */
data class LivePlayInfo(
    val roomId: Long,
    val liveStatus: Int,
    val lines: List<LiveStreamLine>,
    val qualities: Map<Int, String> = emptyMap(),
) {
    /**
     * 挑一条线路来播。
     *
     * 优先级：
     * 1. **HLS + fMP4**（实测唯一稳的）
     * 2. HLS + 其它封装（`ts`）
     * 3. 剩下的（`http_stream`/flv）—— 只在前面全没有时才用
     *
     * 同一档内**优先 HEVC**：目标设备（华为智慧屏 S75 / 鸿鹄 818）的
     * HEVC 硬解比 AVC 强得多（`docs/05` §4）。直播只有 250 一档清晰度，
     * 不像点播还能靠降清晰度绕开，所以编码选对更重要。
     */
    fun preferredLine(): LiveStreamLine? {
        if (lines.isEmpty()) return null
        val tier1 = lines.filter { it.isPreferred }
        val tier2 = lines.filter { it.isHls }
        val pool = when {
            tier1.isNotEmpty() -> tier1
            tier2.isNotEmpty() -> tier2
            else -> lines
        }
        return pool.firstOrNull { it.isHevc } ?: pool.first()
    }
}
