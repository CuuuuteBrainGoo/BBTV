package top.bilitv.data.model

import top.bilitv.R

/**
 * PGC 内容的一个「季」（番剧一季、一部电影、一部纪录片……）。
 *
 * PGC 和 UGC 是两套完全不同的东西：
 * - UGC（`FeedItem`）有 bvid，用 `view` 接口取详情、`playurl` 取流。
 * - PGC 只有 `season_id` / `ep_id`，要点播得先拉剧集列表再取 `ep` 的播放地址。
 *
 * 所以**不合并成一个模型** —— 合并了就要在每个使用处判断"这条到底是哪种"，
 * 那是把复杂度从类型系统搬到了运行期。
 */
data class PgcSeason(
    val seasonId: Long,
    /** 首话的 ep_id。详情/播放都要它，接口放在 `first_ep.ep_id` 里 */
    val epId: Long,
    val title: String,
    /** 副标题，如「爱与诅咒的物语」。没有时空串 */
    val subtitle: String,
    val cover: String,
    /**
     * 横版剧照，用来当影院主视觉。
     *
     * ★ 为什么不能拿 [cover] 凑合：PGC 的 `cover` 是**竖版海报（2:3）**，
     * 直接铺成 16:9 的主视觉会从中间裁一条，人脸被切掉一半。
     * 接口在 `first_ep.cover` 里另给了一张横版图，主视觉必须用它。
     * 拿不到时退回 [cover]，由界面做模糊/裁切处理。
     */
    val backdrop: String,
    /** 「全1话」「更新至第12话」这类进度描述 */
    val indexShow: String,
    /** 「1342.5万追番」「9.9分」这类排序描述 */
    val order: String,
    /** 评分字符串。接口给的是字符串（可能为 "0" 或空），不要转成数字 */
    val score: String,
    /** 「大会员」「独家」这类角标。没有时空串 */
    val badge: String,
    val seasonType: Int,
    val seasonStatus: Int = 0,
) {
    val hasScore: Boolean get() = score.isNotBlank() && score != "0" && score != "0.0"
    val accessBadge: String get() = when {
        badge.contains("大会员") || badge.contains("特价") || badge.contains("付费") -> badge
        badge.contains("独") -> "独播"
        seasonStatus == 1 -> "免费"
        else -> badge
    }
}

data class PgcFilterValue(val id: String, val label: String)
/** Pagination describes the raw server page, even when unusable cards are omitted. */
data class PgcIndexPage(val items: List<PgcSeason>, val hasMore: Boolean)
data class PgcFilterField(val id: String, val label: String, val values: List<PgcFilterValue>)

/**
 * PGC 分区。`id` 就是接口的 `season_type`。
 *
 * ⚠️ 编号**不是连续的**：实测 1/2/3/4/5/7/8 有内容，**6 返回 -400**。
 * 所以不能用 `entries[seasonType - 1]` 去查，必须按 id 匹配。
 * 编号含义是 2026-09-29 逐个探出来的，不是猜的（见 `tools/probe_feeds.py`）。
 */
enum class PgcType(val id: Int, val labelRes: Int) {
    MOVIE(2, R.string.section_movie),
    TV(5, R.string.section_tv),
    DOCUMENTARY(3, R.string.section_documentary),
    VARIETY(7, R.string.section_variety),
    BANGUMI(1, R.string.section_bangumi),
    GUOCHUANG(4, R.string.section_guochuang),
    SHORT_PLAY(8, R.string.section_short_play),
    ;

    companion object {
        fun fromId(id: Int): PgcType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 「每周必看」的一期。
 *
 * `name` 形如 `2026第392期 09.18 - 09.24`，是**给人看的那一行**；
 * `subject` 是本期主题词（如「宏大交响琵琶曲」），适合当标题。
 */
data class WeeklyIssue(
    val number: Int,
    val subject: String,
    val name: String,
)

/**
 * 一部 PGC 作品的详情（含全部剧集）。
 *
 * ## 字段坑
 *
 * `title` 和 `longTitle` 是**两个东西**：前者可能是序号（`"1"`、`"12"`）或版本（`"中文"`、`"原版"`），
 * 后者才是这一集的名字（`"柱训练"`）。界面上要显示的是后者，
 * 而前者用来做"第几集"的编号。搞混了就会出现"每一集都叫 1"。
 *
 * ## 游客态拿不到
 *
 * 实测（2026-09-29）：`/pgc/view/web/season` 在**未登录时 `code=0` 但 `data=null`** ——
 * 注意这跟报错不一样，`code` 是 0，只是没有数据。所以"没登录"这件事
 * **不能靠 code 判断，只能靠 data 是不是空**。这是它最容易踩的地方。
 */
data class PgcDetail(
    val seasonId: Long,
    val title: String,
    val cover: String,
    /** 简介，可能很长，界面自己截断 */
    val evaluate: String,
    val subtitle: String,
    /** 评分字符串（`"9.6"`），和 [PgcSeason.score] 一样不要转数字 */
    val score: String,
    val episodes: List<PgcEpisode>,
) {
    fun playbackTitle(episode: PgcEpisode): String =
        listOf(title, episode.displayName).filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

/** PGC 的一集 */
data class PgcEpisode(
    val epId: Long,
    /** 播放和弹幕都要它。PGC 的弹幕同样用 `cid` 当 oid */
    val cid: Long,
    /** 接口title：数字集序号、版本名或空（花絮、PV）。 */
    val number: String,
    /** 这一集的名字，如 `"柱训练"` */
    val longTitle: String,
    val cover: String,
    val durationSec: Int,
    val badge: String = "",
    val aid: Long = 0L,
    val bvid: String = "",
) {
    /** title也可能是“中文”“原版”等版本名，只有数字序号拼成第N集。 */
    val displayName: String
        get() = longTitle.ifBlank {
            when {
                number.isBlank() -> "正片"
                number.matches(Regex("\\d+(\\.\\d+)?")) -> "第 $number 集"
                else -> number
            }
        }
}

/**
 * 把 PGC 剧集转成一张**首页网格能用的卡**。
 *
 * ## 为什么封面用 [PgcSeason.backdrop] 而不是 [PgcSeason.cover]
 *
 * PGC 的 `cover` 是**竖版海报（2:3）**，首页网格是 **16:9** 的卡。
 * 直接塞进去会从中间裁一条，人脸切掉一半。`backdrop` 是接口另给的**横版剧照**，
 * 形状正好对得上 —— 这也是 `docs/31` 里那条"主视觉必须用横版图"的同一个道理。
 *
 * ## 元信息行用什么
 *
 * UGC 卡的元信息是「UP主 · 播放量 · 时长」，PGC 没有 UP 主。
 * 这里用 `indexShow`（「全1话」「更新至第12话」）—— 那正是用户在剧集卡上想看的。
 */
fun PgcSeason.toFeedItem(): top.bilitv.data.model.FeedItem =
    top.bilitv.data.model.FeedItem(
        bvid = "",
        badge = badge,
        title = title,
        cover = backdrop.ifBlank { cover },
        ownerName = indexShow.ifBlank { subtitle },
        durationSec = 0,
        viewCount = 0,
        seasonId = seasonId,
    )


/**
 * 一个**收藏夹**。
 *
 * ⚠️ [id] 是接口里的 `id` / `fid`（同一个值），**不是** `mid`。
 * 取夹内资源时要拿它当 `media_id`。
 */
data class FavFolder(
    val id: Long,
    val title: String,
    /** 里面有多少条。**接口给 0 和"取不到"是两回事**，所以类型是不可空 Int */
    val count: Int,
    val favored: Boolean? = null,
)

/** 是否还有页来自接口，与过滤后的可播放卡片数量无关。 */
data class FavResourcePage(val items: List<FeedItem>, val hasMore: Boolean)
