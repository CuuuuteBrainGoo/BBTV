package top.bilitv.data.model

/** UGC 视频详情 */
data class VideoDetail(
    val bvid: String,
    val aid: Long,
    val cid: Long,
    val title: String,
    val cover: String,
    val desc: String,
    val durationSec: Int,
    val ownerName: String,
    val ownerMid: Long,
    val viewCount: Long,
    val danmakuCount: Long,
    val pages: List<VideoPage>,
    val copyright: Int = 0,
    val badge: String = "",
    val collectionTitle: String = "",
    val collection: List<VideoCollectionEpisode> = emptyList(),
)

/** 合集里的投稿；分P仍来自当前投稿的pages，二者不混排。 */
data class VideoCollectionEpisode(val bvid: String, val cid: Long, val title: String, val cover: String)

/** 分P */
data class VideoPage(
    val cid: Long,
    val index: Int,
    val title: String,
    val durationSec: Int,
)

/**
 * 列表页的一张卡片。
 *
 * 推荐流 / 热门 / 分区最新投稿 三个接口返回的条目结构高度重合，
 * 统一收敛成这一个模型 —— 界面层就不用为每个数据源写一套卡片了。
 *
 * `danmakuCount` 和 `pubDateSec` 是照 BT 的卡片设计补的：
 * 它的缩略图左下压着「播放量 + 弹幕数」，标题下面还有一行「UP主 + 日期」。
 * 少这两个字段，那两处就只能空着或者写假数据。
 */
data class FeedItem(
    val bvid: String,
    val title: String,
    val cover: String,
    val ownerName: String,
    val durationSec: Int,
    val viewCount: Long,
    val danmakuCount: Long = 0L,
    /** 投稿时间（Unix 秒）。0 表示接口没给，界面不显示日期 */
    val pubDateSec: Long = 0L,
    /** 分区名，如「健身」「纪录片」。卡片上不显示，留着给分区页和排查用 */
    val tname: String = "",

    /**
     * 卡片左上角的角标（少爷 2026-09-30 反馈 7：
     * 「把所有限定"特价""大会员"和"充电"的视频在视频卡右上角标出来，
     *  怎么标你直接参考B站官方页面」）。
     *
     * 空串 = 不显示。读取实际 badge 文本／充电字段；ugc_pay 仅表示付费，不能推导充电。
     */
    val badge: String = "",

    /**
     * **非 0 = 这是一张 PGC（番剧 / 影视）卡**，点击要去**剧集详情页**而不是视频详情页。
     *
     * ## 为什么要塞在 FeedItem 里
     *
     * 首页分区里既有 UGC（有 bvid）也有 PGC（只有 seasonId，**没有 bvid**）。
     * 两者要放进**同一个网格**。如果为 PGC 单独开一个 sealed 类型，
     * 那"无限加载 / 去重 / 焦点记忆 / 刷新"这四套逻辑都要各写一遍 —— 得不偿失。
     *
     * 所以做法是：**PGC 也转成 FeedItem**，只是 `bvid` 留空、`seasonId` 填上，
     * 由界面按 `seasonId > 0` 分流到 `Screen.PgcDetail`。
     *
     * ⚠️ 默认 `0` 是有意的：**所有老的 UGC 构造点一行都不用改**。
     */
    val seasonId: Long = 0L,
)

/** DASH 播放信息（B 站返回的是音画分离的两组流，不是标准 MPD） */
data class PlayInfo(
    val durationMs: Long,
    val videos: List<DashStream>,
    val audios: List<DashStream>,
    val qualityLabels: Map<Int, String> = emptyMap(),
) {
    /**
     * 选一条视频流：同清晰度下**优先 HEVC/AV1，避开 AVC**。
     *
     * 目标设备是中低端电视 SoC（Mali-G51 级），本轮实测这类芯片的 AVC 高分辨率硬解较弱，
     * 而 HEVC 硬解强（官方标称 4K@120）。因此同清晰度必须优先非 AVC 编码。
     * 最终是否可解，还要再过播放器的解码能力检测。
     */
    fun preferredVideo(qualityId: Int? = null): DashStream? {
        val pool = if (qualityId == null) videos else videos.filter { it.qualityId == qualityId }
        if (pool.isEmpty()) return null
        val maxQ = pool.maxOf { it.qualityId }
        val atMax = pool.filter { it.qualityId == maxQ }
        return atMax.firstOrNull { it.isHevc }
            ?: atMax.firstOrNull { !it.isAvc }
            ?: atMax.first()
    }
}

/** 单条 DASH 流 */
data class DashStream(
    val qualityId: Int,
    val codecs: String,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val baseUrl: String,
    val backupUrls: List<String>,
) {
    /** H.264 */
    val isAvc: Boolean get() = codecs.startsWith("avc", ignoreCase = true)

    /** H.265 / HEVC */
    val isHevc: Boolean get() =
        codecs.startsWith("hev", ignoreCase = true) || codecs.startsWith("hvc", ignoreCase = true)

    /** AV1 */
    val isAv1: Boolean get() = codecs.startsWith("av01", ignoreCase = true)
}
