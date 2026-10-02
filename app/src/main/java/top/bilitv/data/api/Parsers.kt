package top.bilitv.data.api

import org.json.JSONArray
import org.json.JSONObject
import top.bilitv.data.model.CODE_UNPARSEABLE
import top.bilitv.data.model.DashStream
import top.bilitv.data.model.DynamicFeed
import top.bilitv.data.model.DynamicItem
import top.bilitv.data.model.FavFolder
import top.bilitv.data.model.FeedItem
import top.bilitv.data.model.LiveArea
import top.bilitv.data.model.LiveAreaSub
import top.bilitv.data.model.LivePlayInfo
import top.bilitv.data.model.LiveRoom
import top.bilitv.data.model.LiveStatus
import top.bilitv.data.model.LiveStreamLine
import top.bilitv.data.model.MyProfile
import top.bilitv.data.model.MyProfileResult
import top.bilitv.data.model.MyStat
import top.bilitv.data.model.PgcDetail
import top.bilitv.data.model.PgcEpisode
import top.bilitv.data.model.PgcSeason
import top.bilitv.data.model.PlayInfo
import top.bilitv.data.model.UpUser
import top.bilitv.data.model.VideoDetail
import top.bilitv.data.model.VideoPage
import top.bilitv.data.model.VideoCollectionEpisode
import top.bilitv.data.model.WeeklyIssue

/**
 * 响应解析（纯函数，可单测）。
 *
 * 设计原则：**宽松解析**。
 * B 站接口字段会随时增删改类型，任何一处强类型假设都可能让整套崩掉。
 * 因此这里全部用 `optXxx`，缺失字段一律给默认值，绝不抛异常；
 * 接口一变只需改这一层。
 */

private fun JSONObject.objOrNull(key: String): JSONObject? =
    if (has(key) && !isNull(key)) optJSONObject(key) else null

private fun JSONObject.arrOrNull(key: String): JSONArray? =
    if (has(key) && !isNull(key)) optJSONArray(key) else null

private fun JSONObject.str(key: String, def: String = ""): String =
    optString(key, def).takeIf { it != "null" } ?: def

private fun JSONObject.num(key: String, def: Long = 0L): Long = optLong(key, def)

/** 取 `data` 层。code != 0 或结构异常返回 null。 */
private fun dataOf(json: String): JSONObject? =
    runCatching { JSONObject(json) }.getOrNull()
        ?.takeIf { it.optInt("code", -1) == 0 }
        ?.let { it.objOrNull("data") ?: it.objOrNull("result") }

/**
 * 取 `data` 层，但**允许 data 本身就是数组**。
 *
 * ## 为什么必须单独一个（不是洁癖）
 *
 * 直播的两个列表接口（`get_user_recommend` / `Area/getRoomList`）返回的是
 * `{"code":0,"data":[{...},{...}]}` —— `data` **直接是数组**，没有外层容器名。
 * 用 [dataOf] 会得到 null，然后解析出 0 条，表现是"接口通了但列表是空的"，
 * 极难查。实测结构见 `tools/probe_live.py`。
 */
private fun dataArrayOf(json: String): JSONArray? =
    runCatching { JSONObject(json) }.getOrNull()
        ?.takeIf { it.optInt("code", -1) == 0 }
        ?.arrOrNull("data")

/** 视频详情 `/x/web-interface/view` */
fun parseVideoDetail(json: String): VideoDetail? {
    val d = dataOf(json) ?: return null
    val pages = d.arrOrNull("pages")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            VideoPage(
                cid = p.num("cid"),
                index = p.optInt("page", i + 1),
                title = p.str("part"),
                durationSec = p.optInt("duration"),
            )
        }
    }.orEmpty()

    val owner = d.objOrNull("owner")
    val stat = d.objOrNull("stat")
    val firstCid = pages.firstOrNull()?.cid ?: d.num("cid")
    val season = d.objOrNull("ugc_season")
    val collection = buildList {
        val sections = season?.arrOrNull("sections") ?: return@buildList
        for (i in 0 until sections.length()) {
            val episodes = sections.optJSONObject(i)?.arrOrNull("episodes") ?: continue
            for (j in 0 until episodes.length()) {
                val e = episodes.optJSONObject(j) ?: continue
                val bv = e.str("bvid")
                if (!bv.startsWith("BV")) continue
                val arc = e.objOrNull("arc")
                add(VideoCollectionEpisode(bv, e.num("cid"), e.str("title").ifBlank { arc?.str("title").orEmpty() }, arc?.str("pic").orEmpty()))
            }
        }
    }.distinctBy { it.bvid }

    return VideoDetail(
        bvid = d.str("bvid"),
        aid = d.num("aid"),
        cid = firstCid,
        title = d.str("title"),
        cover = d.str("pic"),
        desc = d.str("desc"),
        durationSec = d.optInt("duration"),
        ownerName = owner?.str("name").orEmpty(),
        ownerMid = owner?.num("mid") ?: 0L,
        viewCount = stat?.num("view") ?: 0L,
        danmakuCount = stat?.num("danmaku") ?: 0L,
        pages = pages,
        copyright = d.optInt("copyright"),
        badge = badgeOf(d),
        collectionTitle = season?.str("title").orEmpty(),
        collection = collection,
    )
}

/** 相关推荐的data直接是数组；失败与有效空列表分开。 */
fun parseRelatedVideos(json: String): List<FeedItem>? = dataArrayOf(json)?.let { arr ->
    (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::feedItemOf) }
}

fun parseRelatedSeasons(json: String): List<FeedItem>? {
    val d = dataOf(json) ?: return null
    val arr = d.arrOrNull("season") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val s = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = s.num("season_id")
        if (id <= 0) null else FeedItem("", s.str("title"), s.str("cover"), "", 0, 0, badge = badgeOf(s), seasonId = id)
    }
}

/**
 * 首页推荐 `/x/web-interface/wbi/index/top/feed/rcmd`
 *
 * 注意：该接口的条目里 `bvid` 可能出现在 `bvid` 或 `id` 字段，且**广告卡没有 bvid**，
 * 这里直接跳过无 bvid 的条目（界面层广告过滤的第一道）。
 */
fun parseFeedRecommend(json: String): List<FeedItem> {
    val arr = dataOf(json)?.arrOrNull("item") ?: return emptyList()
    val out = ArrayList<FeedItem>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        // goto=ad 的推广卡跳过
        if (o.str("goto").equals("ad", ignoreCase = true)) continue
        val bvid = o.str("bvid").ifBlank { o.str("id") }
        if (bvid.isBlank() || !bvid.startsWith("BV")) continue

        out.add(
            FeedItem(
                bvid = bvid,
                badge = badgeOf(o),
                title = o.str("title"),
                cover = o.str("pic"),
                ownerName = o.objOrNull("owner")?.str("name") ?: o.str("owner"),
                durationSec = o.optInt("duration"),
                viewCount = o.objOrNull("stat")?.num("view") ?: o.num("play"),
                danmakuCount = o.objOrNull("stat")?.num("danmaku") ?: 0L,
                pubDateSec = o.num("pubdate"),
                tname = o.str("tname"),
            )
        )
    }
    return out
}

/**
 * 从一条「视频型」条目里读 [FeedItem]。
 *
 * 热门（`popular`）和分区最新（`newlist`）的条目字段几乎一样：
 * `pic / title / duration / pubdate / owner.name / stat.view / stat.danmaku / tname`。
 * 差异只在**外层容器名**（`list` vs `archives`）和个别可选字段上，
 * 所以字段读取合成一份 —— 两边共用，改了不会漏改一边。
 *
 * @return bvid 不合法的条目返回 null（广告卡、专栏卡、直播卡都会走到这里）
 */
/** 只使用实际角标和充电标志；推荐理由／作者 VIP 不是视频付费身份。 */
private fun badgeOf(o: JSONObject): String {
    fun text(value: Any?): String = when (value) {
        is String -> value.takeUnless { it == "null" }.orEmpty().trim()
        is JSONObject -> value.str("text").trim()
        else -> ""
    }
    fun flag(key: String): Boolean = o.optBoolean(key) || o.optInt(key) == 1
    val direct = sequenceOf(o.opt("badge"), o.opt("badge_info"), o.opt("badgeInfo"), o.opt("elec_arc_badge"))
        .map(::text).firstOrNull { it.isNotBlank() }.orEmpty()
    val charge = flag("is_charging_arc") || flag("is_upower_exclusive") || o.optInt("elec_arc_type") == 1
    return when {
        direct.contains("充电") || charge -> "充电视频"
        direct.contains("抢先看") -> "抢先看"
        direct.contains("特价") -> "特价"
        direct.contains("会员") -> "大会员"
        direct.isNotBlank() -> direct.take(32)
        o.objOrNull("rights")?.optInt("is_ugc_pay") == 1 -> "付费视频"
        else -> ""
    }
}

private fun feedItemOf(o: JSONObject): FeedItem? {
    val bvid = o.str("bvid").ifBlank { o.str("id") }
    if (bvid.isBlank() || !bvid.startsWith("BV")) return null
    return FeedItem(
        bvid = bvid,
        badge = badgeOf(o),
        title = o.str("title"),
        cover = o.str("pic"),
        ownerName = o.objOrNull("owner")?.str("name") ?: o.str("author"),
        durationSec = o.optInt("duration"),
        viewCount = o.objOrNull("stat")?.num("view") ?: 0L,
        danmakuCount = o.objOrNull("stat")?.num("danmaku") ?: 0L,
        pubDateSec = o.num("pubdate"),
        tname = o.str("tname"),
    )
}

/** 从 `list` 或 `archives` 这类数组里批量读条目 */
private fun itemsFrom(json: String, vararg keys: String): List<FeedItem> {
    val d = dataOf(json) ?: return emptyList()
    val arr = keys.firstNotNullOfOrNull { d.arrOrNull(it) } ?: return emptyList()
    val out = ArrayList<FeedItem>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        feedItemOf(o)?.let { out.add(it) }
    }
    return out
}

/**
 * 热门 `/x/web-interface/popular`。
 *
 * 游客态可用，不用签名（2026-09-29 探针实测）。
 */
fun parsePopular(json: String): List<FeedItem> = itemsFrom(json, "list")

/**
 * 分区最新投稿 `/x/web-interface/newlist`。
 *
 * ★ 为什么不用 `dynamic/region`：那个接口现在**所有 rid 都返回 -404「啥都木有」**，
 * 是接口下线的表现（不是参数写错 —— 8 个分区编号逐个试过）。
 * `newlist` 是替代品，实测可用，返回结构基本一致（外层叫 `archives`）。
 */
fun parseRegionNewList(json: String): List<FeedItem> = itemsFrom(json, "archives", "list")

/** 「每周必看」某一期的视频列表 `/x/web-interface/popular/series/one` */
fun parseWeeklyOne(json: String): List<FeedItem> = itemsFrom(json, "list")

/**
 * 搜索 `/x/web-interface/wbi/search/type`（`search_type=video`）。
 *
 * 三处和别的接口不一样，都得单独处理：
 * 1. 结果在 `data.result`，不是 `list` / `item` / `archives`；
 * 2. `title` 带关键词高亮标记（`<em class="keyword">…</em>`），**不剥掉就会把尖括号
 *    原样显示在电视上**；
 * 3. `duration` 是 **`"04:47"` 这样的字符串**，不是秒数；
 * 4. 结果里混着用户、番剧、专栏等各种类型，只有 `type == "video"` 才是视频。
 */
fun parseSearchVideo(json: String): List<FeedItem> {
    val arr = dataOf(json)?.arrOrNull("result") ?: return emptyList()
    val out = ArrayList<FeedItem>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (!o.str("type").equals("video", ignoreCase = true)) continue
        val bvid = o.str("bvid")
        if (bvid.isBlank() || !bvid.startsWith("BV")) continue
        out.add(
            FeedItem(
                bvid = bvid,
                badge = badgeOf(o),
                title = stripTags(o.str("title")),
                cover = o.str("pic"),
                ownerName = o.str("author"),
                durationSec = parseClock(o.str("duration")),
                viewCount = o.num("play"),
                danmakuCount = o.num("video_review"),
                pubDateSec = o.num("pubdate"),
                tname = o.str("typename"),
            )
        )
    }
    return out
}

/**
 * 热搜词 `/x/web-interface/search/square`。
 *
 * 结构藏在三层里：`data.trending.list[].keyword`。
 * 没有这一串词的话，电视上的搜索页就只能靠用户用遥控器敲字 ——
 * 输入一个词要按几十次方向键。热搜让"不打字也能搜"成为可能，
 * 这是电视端搜索页最重要的一块。
 */
fun parseHotSearch(json: String, limit: Int = 12): List<String> {
    val arr = dataOf(json)?.objOrNull("trending")?.arrOrNull("list") ?: return emptyList()
    val out = ArrayList<String>(minOf(limit, arr.length()))
    for (i in 0 until arr.length()) {
        if (out.size >= limit) break
        val o = arr.optJSONObject(i) ?: continue
        val kw = o.str("keyword").ifBlank { o.str("show_name") }
        if (kw.isNotBlank()) out.add(kw)
    }
    return out
}

/*
 * ════════════════════════════════════════════════════════════════════════════
 * 关注页（2026-09-29 新增）
 * ════════════════════════════════════════════════════════════════════════════
 *
 * 这两个接口的性质和前一批**完全不同**，见 `tools/probe_follow.py` 的实测：
 *
 * | 接口 | 游客态表现 |
 * |---|---|
 * | `/x/relation/followings` | `code=-101 账号未登录`（干净、可判别） |
 * | `/x/space/wbi/arc/search` | `code=-352 风控校验失败`（带 buvid3 也一样） |
 *
 * ★ 两个结论值得写死在这里：
 * 1. **`-101` 是可判别信号**，不是"没数据" —— 界面必须说"要先登录"，
 *    而不是"暂时没有内容"。用户对这两句话要做的事完全不同。
 * 2. **`-352` 在游客态无解**。UP 主投稿列表加了 `buvid3`/`buvid4`、WBI 签名、
 *    `platform=web` 都还是 -352，再试就变 `-412 request was banned`。
 *    所以这一页**必须登录才能用** —— 这不是我们没写对参数。
 */

/**
 * 「我关注的人」`/x/relation/followings`。
 *
 * 结构：`data.list[] = { mid, uname, face, sign, official_verify{type,desc},
 *                        vip{...}, live{live_status,roomid}, attribute }`
 * `data.total` 是总数。
 *
 * @param liveFilter `liveStatus == 1` 才算正在直播。少数情况下接口会给
 *   `live` 对象但 `live_status` 为 0（没在播），不能只看对象在不在。
 */
fun parseFollowings(json: String): List<UpUser> {
    val arr = dataOf(json)?.arrOrNull("list") ?: return emptyList()
    val out = ArrayList<UpUser>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val mid = o.num("mid")
        if (mid <= 0L) continue
        val live = o.objOrNull("live")
        out.add(
            UpUser(
                mid = mid,
                name = o.str("uname"),
                face = o.str("face"),
                sign = o.str("sign"),
                officialDesc = o.objOrNull("official_verify")?.str("desc").orEmpty(),
                liveRoomId = if (live?.optInt("live_status", 0) == 1) live.num("roomid") else 0L,
            )
        )
    }
    return out
}

/** `data.total` —— 关注总数。取不到返回 0（界面就不显示"共 N 个"）。 */
fun parseFollowingTotal(json: String): Long = dataOf(json)?.num("total") ?: 0L

/**
 * 某个 UP 主的投稿列表 `/x/space/wbi/arc/search`。
 *
 * ## 三处和别的列表接口不一样
 *
 * 1. 数组在 **`data.list.vlist`**（`list` 是个对象不是数组，先 `list` 再 `vlist`）；
 * 2. **`length` 是 `"12:34"` 这样的字符串**，不是秒数 —— 和搜索接口一个毛病；
 * 3. 弹幕数字段叫 **`video_review`**（和搜索接口一样），不叫 `danmaku`。
 *
 * 这三条任何一条写错，表现都是"接口通了但列表是空的"，很难查。
 */
fun parseUpVideos(json: String): List<FeedItem> {
    val vlist = dataOf(json)?.objOrNull("list")?.arrOrNull("vlist") ?: return emptyList()
    val out = ArrayList<FeedItem>(vlist.length())
    for (i in 0 until vlist.length()) {
        val o = vlist.optJSONObject(i) ?: continue
        val bvid = o.str("bvid")
        if (bvid.isBlank() || !bvid.startsWith("BV")) continue
        out.add(
            FeedItem(
                bvid = bvid,
                badge = badgeOf(o),
                title = o.str("title"),
                cover = o.str("pic"),
                ownerName = o.str("author"),
                durationSec = parseClock(o.str("length")),
                viewCount = o.num("play"),
                danmakuCount = o.num("video_review"),
                pubDateSec = o.num("created"),
                tname = o.str("typename"),
            )
        )
    }
    return out
}

/** `data.page.count` —— 该 UP 一共有多少投稿。取不到返回 0。 */
fun parseUpVideoCount(json: String): Long = dataOf(json)?.objOrNull("page")?.num("count") ?: 0L

/**
 * `/x/relation/stat` 里的 `data.following`（**我关注了多少人**）。
 *
 * ## 为什么需要它 —— 一个"看起来一样"的问题
 *
 * 关注列表接口失败和"真的一个都没关注"**都返回空列表**。
 * 界面要说的两句话完全不同（"接口出问题了" vs "你还没关注谁"），
 * 但列表本身分不出来。
 *
 * `/x/relation/stat` 是**游客态可用**的公开计数接口（同期实测 `code=0`），
 * 所以拿它当"第二条独立证据"：列表空 + 关注数 > 0 → 说明是接口那边出了问题。
 *
 * @return **null 表示取不到**（≠0）。0 和 null 的含义在这里完全不同：
 *   0 是"确实没关注"，null 是"这条证据也没拿到，别乱下结论"。
 */
fun parseFollowingCount(json: String): Long? {
    val d = dataOf(json) ?: return null
    if (!d.has("following")) return null
    return d.optLong("following", 0L)
}

/**
 * ════════════════════════════════════════════════════════════════════════════
 * 「我的」页（2026-09-29 新增）
 * ════════════════════════════════════════════════════════════════════════════
 *
 * ## ★ 全项目唯一一个**刻意绕开 [dataOf]** 的解析器
 *
 * 实测（`tools/probe_mine.py`，游客态）：
 *
 * ```
 * GET /x/web-interface/nav  →  HTTP 200  bcode=-101  msg=账号未登录
 *   data = { isLogin:false, wbi_img:{img_url,sub_url}, ip_region:"…" }
 *                            ↑ data **不是 null**，它照样给了结构
 * ```
 *
 * 也就是说未登录时它**既报错、又给一份 data**。而 [dataOf] 要求 `code == 0`，
 * 用它就会直接拿到 null —— 于是"没登录"和"接口坏了"在代码里变成同一件事，
 * 而用户对这两件事能做的完全不同（去登录 vs 等我们修）。
 *
 * 所以这里**不看 code，只看 `data.isLogin`**。
 *
 * > 规矩：凡是绕过 [dataOf] 的地方，注释里必须写清为什么，
 * > 否则下一个人会"顺手改回去"。
 *
 * ## 未登录的三种表达方式（同一个站内，实测）
 *
 * | 接口 | 游客态 |
 * |---|---|
 * | `nav` | `code=-101` + `data{isLogin:false}` ← 本函数处理的就是这个 |
 * | `nav/stat` / `space/myinfo` / `history/cursor` / `toview` | `code=-101` + `data=null` |
 * | `fav/folder/created/list-all` | `code=-400`（参数错，与登录无关） |
 *
 * 所以「我的」页的判据是 **本机 `isLoggedIn()` 优先，服务端 `isLogin` 兜底**：
 * 本机没凭证 → 根本不发请求；本机有凭证但服务端说没登录 → 那是**凭证过期**，
 * 文案要说"登录已过期"，不能和"还没登录"混成一句。
 */
fun parseMyProfile(json: String): MyProfileResult {
    // 注意：这里**不能**用 dataOf()，理由见上面的注释块
    val data = runCatching { JSONObject(json) }.getOrNull()?.objOrNull("data")
        ?: return MyProfileResult.Unsupported

    // 判据是接口自己给的 isLogin —— 不是 code，也不是"有没有 uname"。
    // 没这个字段说明接口结构变了，按 Unsupported 处理，别猜。
    if (!data.has("isLogin")) return MyProfileResult.Unsupported
    if (!data.optBoolean("isLogin", false)) return MyProfileResult.NotLoggedIn

    return MyProfileResult.Ok(
        MyProfile(
            mid = data.num("mid"),
            name = data.str("uname"),
            face = data.str("face"),
            level = data.objOrNull("level_info")?.optInt("current_level", 0) ?: 0,
            // vipStatus：1 = 大会员，0 = 不是。字段缺失时按"不是"，因为这一项
            // **只用来多画一个小角标** —— 猜错的代价是多一个角标或少一个角标，
            // 不像"没登录/没关注"那种会误导用户的结论。
            vip = data.optInt("vipStatus", 0) == 1,
            // ★ 硬币余额（`nav` 的 `money`，小数）。少爷要求「我的」页显示它
            coins = data.optDouble("money", 0.0),
        )
    )
}

/**
 * 我的关注 / 粉丝 / 动态计数 `/x/web-interface/nav/stat`。
 *
 * ```json
 * {"code":0,"data":{"mid":123,"following":100,"follower":50,"dynamic":10}}
 * ```
 *
 * @return 取不到（未登录 / `data=null` / 接口变了）返回 **null**。
 *   三个字段各自也可能为 null —— 见 [MyStat] 的说明。
 */
fun parseMyStat(json: String): MyStat? {
    val d = dataOf(json) ?: return null
    return MyStat(
        following = if (d.has("following")) d.optLong("following", 0L) else null,
        follower = if (d.has("follower")) d.optLong("follower", 0L) else null,
        dynamic = if (d.has("dynamic")) d.optLong("dynamic", 0L) else null,
    )
}

/**
 * ════════════════════════════════════════════════════════════════════════════
 * 直播（2026-09-29 新增）
 * ════════════════════════════════════════════════════════════════════════════
 *
 * ## 和前几页最大的不同：**换域名了**
 *
 * 直播的接口全在 `api.live.bilibili.com`，和点播的 `api.bilibili.com`
 * 不是一个站。所以这一族接口**不走 WBI 签名**，也不吃点播那套 cookie。
 *
 * ## -352 风控：新接口全挂，老接口全活
 *
 * 实测（`tools/probe_live.py`）：
 *
 * | 接口 | 游客态 |
 * |---|---|
 * | `/xlive/web-interface/v1/second/getList`（新版推荐流） | `-352` |
 * | `/xlive/web-interface/v1/second/getListByArea`（新版分区流） | `-352` |
 * | `/xlive/web-room/v1/index/getInfoByRoom`（新版房间详情） | `-352` |
 * | **`/room/v1/room/get_user_recommend`**（老推荐流） | **✅ 30 条** |
 * | **`/room/v1/Area/getRoomList`**（老分区流） | **✅ 30 条** |
 * | **`/room/v1/Area/getList`**（老分区表） | **✅ 12 个大区** |
 * | **`/xlive/web-room/v1/index/getRoomBaseInfo`**（房间详情） | **✅** |
 * | **`/xlive/web-room/v2/index/getRoomPlayInfo`**（取流） | **✅** |
 *
 * 结论：**能用老的就用老的**。这和 `dynamic/region` 下线时正好相反 ——
 * 那次是"新的没出来、旧的先死"，这次是"新的被风控、旧的反而活着"。
 * 所以不要凭"接口名字里没有 v2 就是过时"这种直觉去改。
 *
 * ## ⚠️ 列表接口**不给开播状态**，角标要自己判
 *
 * 两个老列表接口都**没有** `live_status` 字段（实测：推荐流 30 条全是 `null`，
 * 分区流连 key 都没有）。所以卡片上那个「直播中 / 轮播 / 未开播」的角标
 * 不能直接读字段 —— 规则在 [liveStatusOf] 里，一句话：
 * **给了就用，没给就按人气推断，人气也是 0 就记"不知道"（不画角标）**。
 *
 * ## ⚠️ 数字实测是**数字**，但读取照旧走 [num]
 *
 * `roomid` / `online` / `uid` 实测返回 JSON 数字（`545068`，不是 `"545068"`）。
 * 早先这里的注释写"全是字符串"，是错的。仍然走 [num] 是因为它对两种形态都成立，
 * 而**读错的表现是"人气永远是 0"** —— 不报错、不崩，只有肉眼能发现。
 */

/**
 * 推荐直播 `/room/v1/room/get_user_recommend`。
 *
 * `data` **直接是数组**（见 [dataArrayOf]）。
 *
 * 字段：`roomid / uid / title / uname / face / user_cover / system_cover / online /
 * watched_show{num,text_large}`。**没有 `live_status`**（见本节的说明）。
 *
 * ★ `area` / `areaName` 在这个接口里是**废的**（实测恒为 `0` / `""`）——
 * 想要分区名得用 [parseLiveAreaRooms]。
 */
fun parseLiveRecommend(json: String): List<LiveRoom> {
    val arr = dataArrayOf(json) ?: return emptyList()
    return liveRoomsOf(arr)
}

/**
 * 按分区取直播列表 `/room/v1/Area/getRoomList`。
 *
 * `data` 也是**直接数组**。真正多出来的东西是**分区名**：
 * `area_name`（子区，如「颜值」）和 `parent_name`（大区，如「娱乐」）。
 *
 * ★ 实测这个接口的封面字段有三个候选：`user_cover`（主播传的）、
 * `cover`（一般等于 user_cover）、`system_cover`（系统截帧）。
 * 按这个顺序取第一个非空的。
 */
fun parseLiveAreaRooms(json: String): List<LiveRoom> {
    val arr = dataArrayOf(json) ?: return emptyList()
    return liveRoomsOf(arr)
}

/**
 * 「我关注的、正在直播的房间」
 * （`/xlive/web-ucenter/v1/xfetter/GetWebList`）。
 *
 * ★ 2026-09-30 少爷（截图批注）：「直播第一个是关注，第二个是推荐」。
 *
 * ⛔ **形状和上面两个都不一样**，不能直接复用 [liveRoomsOf]：
 * - 上面两个的 `data` 是**裸数组**；这个是**对象**，列表在 `data.list`
 * - 每个条目**外面包了一层** `room_info`，真正的内容在里面
 *
 * ⚠️ 这个接口没有官方文档，形状只能实测。所以拿不到 `list` 时返回空，
 * 由调用方把**原始响应前 300 字**打进日志（`docs/99` §C：不打原文就只能靠猜）。
 */
fun parseLiveFollowing(json: String): List<LiveRoom> {
    val d = dataOf(json) ?: return emptyList()
    val arr = d.optJSONArray("list") ?: return emptyList()
    val flat = ArrayList<JSONObject>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        // 有 room_info 就用它（实测形状）；没有就按扁平结构兜底
        flat.add(o.objOrNull("room_info") ?: o)
    }
    return liveRoomsOf(JSONArray(flat))
}

/** 两个列表接口的条目字段几乎一样，读法合成一份 —— 改一处不会漏改另一边 */
private fun liveRoomsOf(arr: JSONArray): List<LiveRoom> {
    val out = ArrayList<LiveRoom>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val roomId = o.num("roomid").takeIf { it > 0L } ?: continue
        out.add(
            LiveRoom(
                roomId = roomId,
                title = o.str("title"),
                uname = o.str("uname"),
                uid = o.num("uid"),
                cover = o.str("user_cover")
                    .ifBlank { o.str("cover") }
                    .ifBlank { o.str("system_cover") },
                face = o.str("face"),
                areaName = o.str("area_name"),
                parentAreaName = o.str("parent_name"),
                online = o.num("online"),
                liveStatus = liveStatusOf(o),
            )
        )
    }
    return out
}

/**
 * 一条列表条目"是不是在播"。
 *
 * ## ★ 为什么不能直接 `optInt("live_status", 0)`
 *
 * 因为**这两个接口根本不返回这个字段**。实测（`tools/probe_live.py`，2026-09-29）：
 *
 * ```
 *   推荐流 30 条 → live_status 全是 null
 *   分区流 30 条 → 响应里没有这个 key
 * ```
 *
 * 老写法 `optInt("live_status", 0)` 于是对**每一个房间**都兜底成 0，
 * 卡片角标清一色「未开播」—— 有封面、有人气、点进去还能播，
 * 就是角标在撒谎。编译过、单测绿、功能全死（`LiveTest` 当时的假数据
 * 里也没放这个字段，所以测试一点都没拦住）。
 *
 * ## 拿不到的时候怎么办：用「人气」当证据
 *
 * - `live_status` **在** → 用接口给的（房间详情 / 取流接口会给真值）。
 * - `live_status` **不在** → `online > 0` 记 [LiveStatus.LIVING]，
 *   否则记 [LiveStatus.UNKNOWN]（**不猜**，界面不画角标）。
 *
 * 敢用 `online` 推断，三条理由：
 * 1. 这两个接口的语义就是"**在播**房间的列表"（推荐位 / 分区热榜）；
 * 2. 实测 60/60 条 `online > 0`，没有反例；
 * 3. **失败方向是安全的** —— 最坏情况是把轮播房间说成「直播中」，
 *    而它确实有画面可看；绝不会把在播的说成「未开播」，
 *    后者才是用户点进去踩空的原因。
 */
private fun liveStatusOf(o: JSONObject): Int {
    if (o.has("live_status")) {
        val v = o.optInt("live_status", LiveStatus.UNKNOWN)
        // 真给了就照收。`-1` 只有"字段不在"这一条路会产生
        if (v != LiveStatus.UNKNOWN) return v
    }
    return if (o.num("online") > 0L) LiveStatus.LIVING else LiveStatus.UNKNOWN
}

/**
 * 直播分区表 `/room/v1/Area/getList`。
 *
 * `data` 是数组：`[{ id, name, list:[{ id, parent_id, name, ... }] }]`
 * —— 大区里套子区。`name` 全是中文（「网游」「英雄联盟」）。
 *
 * 界面上的分区标签要的是**子区 id**（列表接口按子区过滤），
 * 所以这里必须两层都留着，不能只存名字。
 */
fun parseLiveAreas(json: String): List<LiveArea> {
    val arr = dataArrayOf(json) ?: return emptyList()
    val out = ArrayList<LiveArea>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optInt("id")
        val name = o.str("name")
        if (id <= 0 || name.isBlank()) continue
        val subs = o.arrOrNull("list")?.let { sub ->
            (0 until sub.length()).mapNotNull { k ->
                val s = sub.optJSONObject(k) ?: return@mapNotNull null
                val sid = s.optInt("id")
                val sname = s.str("name")
                if (sid <= 0 || sname.isBlank()) null else LiveAreaSub(sid, sname)
            }
        }.orEmpty()
        out.add(LiveArea(id, name, subs))
    }
    return out
}

/**
 * 房间基础信息 `/xlive/web-room/v1/index/getRoomBaseInfo`。
 *
 * ## ★ 结构是个"以房间号为键的字典"
 *
 * ```
 *   data.by_room_ids = { "545068": { room_id, title, uname, cover, live_status, ... } }
 *   data.by_uids     = { "8739477": { ... } }
 * ```
 *
 * 不是数组！所以不能 `arr.optJSONObject(0)`。请求用 `room_ids` 就要读
 * `by_room_ids`，用 `uids` 就要读 `by_uids` —— 两个都找一遍，省得调用方记这件事。
 *
 * ## 为什么不用 `/xlive/web-room/v1/index/getInfoByRoom`
 *
 * 那个（更常见的那个）在游客态**稳定 -352**，实测过。这个能通。
 *
 * @return 取不到返回 null。**null ≠ 房间不存在** —— 也可能是接口变了，
 *   界面要按"拿不到房间信息"说，不要下"这个直播间不存在"的结论。
 */
fun parseLiveRoomInfo(json: String): LiveRoom? {
    val d = dataOf(json) ?: return null
    val map = d.objOrNull("by_room_ids") ?: d.objOrNull("by_uids") ?: return null
    val first = map.keys().asSequence().firstOrNull() ?: return null
    val o = map.optJSONObject(first) ?: return null
    val roomId = o.num("room_id").takeIf { it > 0L } ?: first.toLongOrNull() ?: return null
    return LiveRoom(
        roomId = roomId,
        title = o.str("title"),
        uname = o.str("uname"),
        uid = o.num("uid"),
        cover = o.str("cover"),
        // 这个接口**没有** face 字段；头像要用主播接口拿，这里留空
        areaName = o.str("area_name"),
        parentAreaName = o.str("parent_area_name"),
        online = o.num("online"),
        // 这个接口**会给** `live_status`（实测数字 1），但同样走 [liveStatusOf] ——
        // 万一哪天不给，行为跟列表一致（退到按人气推断），不会两处不一样
        liveStatus = liveStatusOf(o),
    )
}

/**
 * 直播取流 `/xlive/web-room/v2/index/getRoomPlayInfo`。
 *
 * ## ★★ 三层嵌套，`base_url` 的位置是坑
 *
 * ```
 *   data.playurl_info.playurl.stream[]
 *        ├ protocol_name = "http_stream" | "http_hls"
 *        └ format[]
 *             ├ format_name = "flv" | "ts" | "fmp4"
 *             └ codec[]
 *                  ├ codec_name = "avc" | "hevc" | "av1"
 *                  ├ current_qn / accept_qn
 *                  ├ base_url          ← ★ 在这一层！
 *                  └ url_info[] { host, extra, stream_ttl }
 * ```
 *
 * **完整地址 = `url_info.host` + `codec.base_url` + `url_info.extra`**。
 *
 * 两个都实测踩过：
 * 1. `base_url` 读错成 `url_info.base_url` → 得到空串 → 拼出来全 404；
 * 2. `base_url` 自己**以 `?` 结尾**，再补一个 `?` 变成 `??expires=…` → **403**。
 *
 * ## playurl_info 为 null 是正常的
 *
 * 房间没开播时它整个是 null（数据实测：`live_status=2`）。
 * 这不是解析失败，所以这里返回 [LivePlayInfo] 而不是 null —— 让调用方
 * 能区分"没开播"和"没解析出来"。
 */
fun parseLivePlayInfo(json: String): LivePlayInfo? {
    val d = dataOf(json) ?: return null
    val roomId = d.num("room_id")
    // 实测这个接口**会给** `live_status`（数字）。缺失时记"不知道"而不是
    // "未开播" —— 调用方的判据是 `!= LIVING`，两条路都会走"不播 + 说没开播"，
    // 但日志和界面上"未开播"和"拿不到状态"不是一回事，别提前把它合并了
    val liveStatus = d.optInt("live_status", LiveStatus.UNKNOWN)
    val lines = ArrayList<LiveStreamLine>()

    val streams = d.objOrNull("playurl_info")?.objOrNull("playurl")?.arrOrNull("stream")
    if (streams != null) {
        for (i in 0 until streams.length()) {
            val st = streams.optJSONObject(i) ?: continue
            val protocol = st.str("protocol_name")
            val formats = st.arrOrNull("format") ?: continue
            for (j in 0 until formats.length()) {
                val f = formats.optJSONObject(j) ?: continue
                val format = f.str("format_name")
                val codecs = f.arrOrNull("codec") ?: continue
                for (k in 0 until codecs.length()) {
                    val c = codecs.optJSONObject(k) ?: continue
                    val urls = liveUrlsOf(c)
                    if (urls.isEmpty()) continue
                    lines.add(
                        LiveStreamLine(
                            protocol = protocol,
                            format = format,
                            codec = c.str("codec_name"),
                            qn = c.optInt("current_qn"),
                            urls = urls,
                        )
                    )
                }
            }
        }
    }
    val descriptions = d.optJSONObject("playurl_info")?.optJSONObject("playurl")?.optJSONArray("g_qn_desc")
        ?: d.optJSONArray("g_qn_desc") ?: JSONArray()
    val accepted = streams?.let { stream ->
        (0 until stream.length()).flatMap { i ->
            val formats = stream.optJSONObject(i)?.optJSONArray("format") ?: JSONArray()
            (0 until formats.length()).flatMap { j ->
                val codecs = formats.optJSONObject(j)?.optJSONArray("codec") ?: JSONArray()
                (0 until codecs.length()).flatMap { k ->
                    val qns = codecs.optJSONObject(k)?.optJSONArray("accept_qn") ?: JSONArray()
                    (0 until qns.length()).map { qns.optInt(it) }
                }
            }
        }.toSet()
    }.orEmpty() + lines.map { it.qn }
    val qualities = (0 until descriptions.length()).mapNotNull { i ->
        descriptions.optJSONObject(i)?.let { o ->
            val qn = o.optInt("qn")
            val label = o.optJSONObject("media_base_desc")?.optJSONObject("detail_desc")?.optString("desc").orEmpty().ifBlank { o.optString("desc") }
            if (qn in accepted && qn > 0 && label.isNotBlank()) qn to label else null
        }
    }.toMap()
    return LivePlayInfo(roomId = roomId, liveStatus = liveStatus, lines = lines, qualities = qualities)
}

/**
 * 把一个 codec 节点里的地址**全部拼好**。
 *
 * 拼接规则（见 [parseLivePlayInfo]）：`host + base_url + extra`。
 *
 * 这里不假设 `base_url` 一定以 `?` 结尾 —— 万一哪天改了，
 * 靠"两段之间的问号由 `?` 有没有出现过决定"来兜底，比硬编码安全。
 * 实测当前形态是 `base_url` 带 `?`、`extra` 不带，所以两者直接粘。
 */
private fun liveUrlsOf(codec: JSONObject): List<String> {
    val base = codec.str("base_url")
    if (base.isBlank()) return emptyList()
    val infos = codec.arrOrNull("url_info") ?: return emptyList()
    val out = ArrayList<String>(infos.length())
    for (i in 0 until infos.length()) {
        val ui = infos.optJSONObject(i) ?: continue
        val host = ui.str("host")
        if (host.isBlank()) continue
        val extra = ui.str("extra")
        val head = host + base
        val full = when {
            extra.isBlank() -> head
            head.contains('?') -> head + extra
            extra.startsWith("?") -> head + extra
            else -> "$head?$extra"
        }
        out.add(full)
    }
    return out
}

/** 去掉接口为关键词高亮塞进来的 HTML 标签，顺带解几个常见实体 */
internal fun stripTags(raw: String): String =
    raw.replace(Regex("""<[^>]*>"""), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()

/** `"04:47"` / `"1:02:03"` → 秒。解析不了返回 0（界面会显示 `--:--`） */
internal fun parseClock(text: String): Int {
    val parts = text.trim().split(":").mapNotNull { it.trim().toIntOrNull() }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        else -> 0
    }
}

/**
 * 「每周必看」期号列表 `/x/web-interface/popular/series/list`。
 *
 * 界面只需要最近若干期，但解析层不做截断 —— 截多少是界面的事。
 */
fun parseWeeklySeries(json: String): List<WeeklyIssue> {
    val arr = dataOf(json)?.arrOrNull("list") ?: return emptyList()
    val out = ArrayList<WeeklyIssue>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val n = o.optInt("number")
        if (n <= 0) continue
        out.add(WeeklyIssue(number = n, subject = o.str("subject"), name = o.str("name")))
    }
    return out
}

/**
 * PGC 作品详情 `/pgc/view/web/season`。
 *
 * ## 两个必须知道的事
 *
 * 1. **游客态 `code=0` 但 `data=null`** —— "没登录"不能靠 code 判断，
 *    只能靠返回的 data 是不是空。所以这里 data 为 null 时返回 null，
 *    由调用方解释成"需要登录"，而不是"接口挂了"。这两种情况给用户的提示完全不同。
 * 2. 真实结构在 `data.result` 里（不是 `data` 本身），但接口有两套形态并存，
 *    所以两个位置都找一遍。
 *
 * ## `title` 和 `long_title`
 *
 * `title` 是集数序号（`"1"`），`long_title` 才是集名（`"柱训练"`）。
 * 见 [PgcEpisode] 的注释。
 */
fun parsePgcDetail(json: String): PgcDetail? {
    val d = dataOf(json) ?: return null
    // 接口有两套形态：老的在 data.result，新的直接铺在 data 上
    val r = d.objOrNull("result") ?: d

    val eps = r.arrOrNull("episodes")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // ep_id 在部分响应里叫 id
            val ep = o.num("ep_id").takeIf { it > 0 } ?: o.num("id")
            if (ep <= 0) return@mapNotNull null
            PgcEpisode(
                epId = ep,
                cid = o.num("cid"),
                number = o.str("title"),
                longTitle = o.str("long_title"),
                cover = o.str("cover"),
                durationSec = o.optInt("duration") / 1000,   // 这个接口给的是毫秒
                badge = badgeOf(o),
                aid = o.num("aid"),
                bvid = o.str("bvid"),
            )
        }
    }.orEmpty()

    val seasonId = r.num("season_id").takeIf { it > 0 } ?: return null
    return PgcDetail(
        seasonId = seasonId,
        title = r.str("title"),
        cover = r.str("cover").ifBlank { r.str("bkg_cover") },
        evaluate = r.str("evaluate"),
        subtitle = r.str("subtitle"),
        // 评分有几个可能的位置：新形态在 `rating.info.score`，老形态直接是 `score`。
        // 都是**字符串**（`"8.8"`），转数字会变成 0，所以一路按字符串拿。
        score = r.objOrNull("rating")?.objOrNull("info")?.str("score").orEmpty()
            .ifBlank { r.str("score") },
        episodes = eps,
    )
}

/**
 * PGC 分类索引 `/pgc/season/index/result`。
 *
 * 参数坑：**必须带 `type=1`**，否则一律 `code=-400 请求错误`。
 * 这个参数在任何地方都没提，是 2026-09-29 试出来的。
 *
 * 字段坑：`score` 和 `order` 都是**字符串**（`"8.8"` / `"1342.5万追番"`），
 * `optDouble` 会得到 0，必须按字符串读。
 */
fun parsePgcIndex(json: String): List<PgcSeason> {
    val arr = dataOf(json)?.arrOrNull("list") ?: return emptyList()
    val out = ArrayList<PgcSeason>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        // 兼容 link：老接口只给 `.../bangumi/play/ss48001`，season_id 只能从链接里抠
        val seasonId = o.num("season_id").takeIf { it > 0 }
            ?: Regex("""(?:ss|md)(\d+)""").find(o.str("link"))?.groupValues?.get(1)?.toLongOrNull()
            ?: continue
        val cover = o.str("cover")
        out.add(
            PgcSeason(
                seasonId = seasonId,
                epId = o.objOrNull("first_ep")?.num("ep_id") ?: 0L,
                title = o.str("title"),
                subtitle = o.str("subTitle").ifBlank { o.str("sub_title") },
                cover = cover,
                backdrop = o.str("ss_horizontal_cover").ifBlank { o.objOrNull("first_ep")?.str("cover").orEmpty().ifBlank { o.objOrNull("new_ep")?.str("cover").orEmpty().ifBlank { cover } } },
                indexShow = o.str("index_show").ifBlank { o.objOrNull("new_ep")?.str("index_show").orEmpty() },
                order = o.str("order"),
                score = o.str("score").ifBlank { o.str("rating").removeSuffix("分") },
                badge = badgeOf(o),
                seasonType = o.optInt("season_type"),
                seasonStatus = o.optInt("season_status"),
            )
        )
    }
    return out
}

fun parsePgcFilters(json: String): List<top.bilitv.data.model.PgcFilterField> {
    val data = dataOf(json) ?: return emptyList()
    val filters = data.optJSONArray("filter") ?: JSONArray()
    val out = ArrayList<top.bilitv.data.model.PgcFilterField>()
    for (i in 0 until filters.length()) {
        val field = filters.optJSONObject(i) ?: continue
        val id = field.optString("field")
        if (!id.matches(Regex("[a-z_]{1,32}"))) continue
        val values = field.optJSONArray("values") ?: continue
        out.add(top.bilitv.data.model.PgcFilterField(id, field.optString("name"), (0 until values.length()).mapNotNull { n ->
            values.optJSONObject(n)?.let { top.bilitv.data.model.PgcFilterValue(it.optString("keyword"), it.optString("name")) }
        }))
    }
    val orders = data.optJSONArray("order") ?: JSONArray()
    if (orders.length() > 0) out.add(0, top.bilitv.data.model.PgcFilterField("order", "排序", (0 until orders.length()).mapNotNull { n ->
        orders.optJSONObject(n)?.let { top.bilitv.data.model.PgcFilterValue(it.optString("field"), it.optString("name")) }
    }))
    return out
}

/** 仅取官方频道页面初始数据中的作品 Banner；活动外链不冒充能播放的剧集。 */
fun parsePgcBanner(html: String, type: Int): List<PgcSeason> {
    val marker = Regex("__INITIAL_STATE__\\s*=\\s*").find(html) ?: return emptyList()
    val tail = html.substring(marker.range.last + 1)
    var depth = 0; var quoted = false; var escaped = false; var end = -1
    for (i in tail.indices) {
        val c = tail[i]
        if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
        else if (c == '"') quoted = true else if (c == '{') depth++ else if (c == '}' && --depth == 0) { end = i + 1; break }
    }
    if (end < 0) return emptyList()
    val items = JSONObject(tail.substring(0, end)).optJSONObject("modules")?.optJSONObject("banner")?.optJSONArray("items") ?: return emptyList()
    return (0 until minOf(items.length(), 20)).mapNotNull { i ->
        val item = items.optJSONObject(i) ?: return@mapNotNull null
        val id = Regex("/ss(\\d+)").find(item.optString("link"))?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
        val cover = item.optString("cover")
        PgcSeason(id, 0L, item.optString("title"), item.optString("sub_title"), cover, cover, "", "", "", "", type)
    }
}

/** 播放地址 `/x/player/wbi/playurl`（fnval=4048 → DASH） */
fun parsePlayInfo(json: String): PlayInfo? {
    val payload = dataOf(json) ?: return null
    val d = payload.objOrNull("video_info") ?: payload
    val dash = d.objOrNull("dash") ?: return null

    fun streams(key: String): List<DashStream> =
        dash.arrOrNull(key)?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> streamOf(arr.optJSONObject(i)) }
        }.orEmpty()

    val videos = streams("video")
    val audios = streams("audio")
    if (videos.isEmpty()) return null

    return PlayInfo(
        durationMs = dash.num("duration") * 1000L,
        videos = videos,
        audios = audios,
        qualityLabels = buildMap {
            val formats = d.arrOrNull("support_formats")
            for (i in 0 until (formats?.length() ?: 0)) {
                val format = formats?.optJSONObject(i) ?: continue
                val quality = format.optInt("quality")
                val label = format.str("new_description").ifBlank { format.str("display_desc") }.ifBlank { format.str("description") }
                if (quality > 0 && label.isNotBlank()) put(quality, label)
            }
        },
    )
}

private fun streamOf(o: JSONObject?): DashStream? {
    if (o == null) return null
    val url = o.str("baseUrl").ifBlank { o.str("base_url") }
    if (url.isBlank()) return null
    val backups = (o.arrOrNull("backupUrl") ?: o.arrOrNull("backup_url"))?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.orEmpty()
    return DashStream(
        qualityId = o.optInt("id"),
        codecs = o.str("codecs"),
        bandwidth = o.num("bandwidth"),
        width = o.optInt("width"),
        height = o.optInt("height"),
        baseUrl = url,
        backupUrls = backups,
    )
}

// ---------------------------------------------------------------- 动态流

/**
 * 取一个**可以为空**的数字。
 *
 * ## 为什么必须单独一个（全项目最要紧的一条纪律）
 *
 * [num] 的默认值是 `0L`。对"播放量"这类字段，**"接口没给"和"接口说它是 0"
 * 是两句完全不同的话**：前者应该一个字都不显示，后者才该显示 `0`。
 * 拿 `num("play")` 去读，两种情况都得到 0 —— 界面就会给用户看
 * 「0 播放」这种假话。
 *
 * 这个坑 2026-09-29 在直播页真的踩过一次（卡片角标恒显示「未开播」，
 * 30 张卡 30 句谎话，见 `docs/19` §2），根因就是"拿默认值当结论"。
 * 所以凡是**可空才有意义**的数值，一律走这里。
 *
 * `has(key) && !isNull(key)` 两个条件都要 —— 接口确实会返回 `"xxx": null`。
 */
private fun JSONObject.numOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

/**
 * 动态流 `/x/polymer/web-dynamic/v1/feed/all`。
 *
 * ## ★★ 这个解析器的核心规则：**解析不出 `bvid` 的条目，直接丢掉**
 *
 * 动态流里混着 11 种条目（视频投稿 / 图文 / 纯文字 / 转发 / 专栏 / 音频 /
 * 直播预告 / 合集更新 / 收藏夹 / 优惠券 / 已删除）。对遥控器用户来说，
 * 一张"点进去什么都不发生"的卡片是最糟的东西 —— `docs/18` §1.3 里
 * 已经因为同一件事否掉过"关注页做成时间流"的方案。
 *
 * 所以这里不返回"带类型的条目让界面去判"，而是**在解析层就筛掉**。
 * 好处是界面上不可能出现灰卡片/空卡片，坏处是**列表会比真实的动态条数少**
 * —— 那个代价由 `DynamicViewModel` 用"首屏太少就自动多翻一页"补回来。
 *
 * ## 转发的处理
 *
 * 转发动态自己的 `module_dynamic.major` 是 `null`，视频在被转发的那条里
 * （`items[].orig.modules...`）。处理方式：**取 `orig` 里的视频**，
 * 但**作者取转发者**（"这条是谁发的"），另标 `forwarded=true` 让界面打角标。
 *
 * ## ⚠️ 没有在真实响应上验过（全项目唯一）
 *
 * 动态流是**登录门禁**接口：实测游客态一律 `-101`（带不带 WBI 一样，
 * 见 `tools/probe_dynamic.py`），所以字段名只能照官方文档写。
 * 因此这里对"缺字段"一律**更保守**：宁可少显示一条，也不编一个值。
 * 登录后第一件事就是跑 `tools/probe_dynamic.py --cookie` 拿真实响应回来核这一段。
 */
fun parseDynamicFeed(json: String): DynamicFeed {
    val root = runCatching { JSONObject(json) }.getOrNull()
        ?: return DynamicFeed(CODE_UNPARSEABLE, emptyList(), "", false)
    val code = root.optInt("code", CODE_UNPARSEABLE)
    val data = root.objOrNull("data") ?: return DynamicFeed(code, emptyList(), "", false)
    val arr = data.arrOrNull("items") ?: return DynamicFeed(code, emptyList(), "", false)

    val items = (0 until arr.length()).mapNotNull { dynamicItemOf(arr.optJSONObject(it)) }

    return DynamicFeed(
        code = code,
        items = items,
        nextOffset = data.str("offset"),
        /*
         * `has_more` 取不到就当"没有下一页"。
         *
         * 这是**安全方向**：停在那儿只是少翻一页，而反过来（缺字段当 true）
         * 会在字段改名时把翻页请求打满 —— 那才是要出事的那个方向。
         */
        hasMore = data.optBoolean("has_more", false),
    )
}

/** 解析不出可播目标时返回 `null`，调用方据此把这条丢掉。理由见 [parseDynamicFeed]。 */
private fun dynamicItemOf(raw: JSONObject?): DynamicItem? {
    val o = raw ?: return null
    val modules = o.objOrNull("modules") ?: return null
    val author = modules.objOrNull("module_author")
    val dyn = modules.objOrNull("module_dynamic")

    var forwarded = false
    var major = dyn?.objOrNull("major")
    var caption = dyn?.objOrNull("desc")?.str("text").orEmpty()

    if (major == null) {
        /*
         * `major` 为 null 只有两种可能，两种都走这条路：
         * - **转发**：主体在被转发的那条里（`orig`，结构和条目本身一样，有 modules）；
         * - **纯文字**：压根没有主体。它没有 `orig`，下面就 `return null` 把它丢掉 ——
         *   这正是我们要的（遥控器上点纯文字动态没反应）。
         */
        val origModules = o.objOrNull("orig")?.objOrNull("modules") ?: return null
        val origDyn = origModules.objOrNull("module_dynamic") ?: return null
        major = origDyn.objOrNull("major") ?: return null
        forwarded = true
        /*
         * 正文**优先用转发语**（`module_dynamic.desc`，在 `major == null` 之前就取好了）。
         * 转发的人没写话时才退回落用被转发那条的原文 —— 那时屏幕上至少还有东西看。
         */
        if (caption.isBlank()) caption = origDyn.objOrNull("desc")?.str("text").orEmpty()
    }

    /*
     * 只认 `MAJOR_TYPE_ARCHIVE`。
     *
     * 刻意**不支持** `MAJOR_TYPE_PGC`（追番剧集）—— 官方文档里 `major.pgc`
     * 只有 `epid / season_id / cover / stat / sub_type`，**没有 title**，
     * 照它做出来的会是一张"没有标题的卡片"。而且这一整段都验不了，
     * 与其加一个可能长歪的分支，不如等登录后拿到真实响应再说。
     */
    if (major.str("type") != "MAJOR_TYPE_ARCHIVE") return null

    val archive = major.objOrNull("archive") ?: return null
    val bvid = archive.str("bvid")
    if (bvid.isBlank()) return null

    val stat = archive.objOrNull("stat")
    val mstat = modules.objOrNull("module_stat")

    return DynamicItem(
        id = o.str("id_str"),
        bvid = bvid,
        title = archive.str("title"),
        cover = archive.str("cover"),
        durationText = archive.str("duration_text"),
        badge = badgeOf(archive),
        // ★ 作者字段名是 `name`，**不是** `uname`（这个接口独一份的命名）
        ownerMid = author?.num("mid") ?: 0L,
        ownerName = author?.str("name").orEmpty(),
        ownerFace = author?.str("face").orEmpty(),
        action = author?.str("pub_action").orEmpty(),
        pubTs = author?.numOrNull("pub_ts"),
        caption = caption,
        forwarded = forwarded,
        play = stat?.numOrNull("play"),
        danmaku = stat?.numOrNull("danmaku"),
        like = mstat?.objOrNull("like")?.numOrNull("count"),
        comment = mstat?.objOrNull("comment")?.numOrNull("count"),
        forward = mstat?.objOrNull("forward")?.numOrNull("count"),
    )
}


// ---------------------------------------------------------------- 收藏夹

/**
 * 收藏夹列表 `/x/v3/fav/folder/created/list-all`。
 *
 * ## ⚠️ 这个接口**必须带 `up_mid`**，而且游客态**测不出来**
 *
 * `docs/21` 记过一次实测：游客态打它是 `code=-400 请求错误`，
 * 当时的结论是"**参数**错，和登录无关" —— 因为游客态根本拿不到自己的 mid，
 * 而 `up_mid` 是必填的。
 *
 * 所以这条路径**只有登录后才跑得到**：
 * - 代码写完时**没有实测过响应形状**，字段名照 B 站公开文档与同类项目写的；
 * - 配套探针 `tools/probe_fav.py`，少爷登录后跑一次核对（和 `probe_dynamic.py` 一个套路）。
 *
 * 结构：`data.list[] = { id, fid, title, media_count, … }`
 */
fun parseFavFolders(json: String): List<FavFolder> = try {
    val arr = org.json.JSONObject(json).optJSONObject("data")?.optJSONArray("list")
    buildList {
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr?.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L).takeIf { it > 0L } ?: o.optLong("fid", 0L)
            if (id <= 0L) continue
            add(
                FavFolder(
                    id = id,
                    title = o.optString("title"),
                    count = o.optInt("media_count", 0),
                    favored = if (o.has("fav_state") && !o.isNull("fav_state")) when (o.optInt("fav_state", -1)) {
                        0 -> false; 1 -> true; else -> null
                    } else null,
                )
            )
        }
    }
} catch (t: Throwable) {
    emptyList()
}

/**
 * 某个收藏夹里的资源 `/x/v3/fav/resource/list`。
 *
 * ⛔⛔ **不能复用 [itemsFrom] —— 字段名整套都不一样**（2026-09-30 真机登录态实测推翻）。
 *
 * | 要的东西 | 热门 / 新投稿 / 推荐流 | 收藏夹 |
 * |---|---|---|
 * | 缩略图 | `pic` | **`cover`** |
 * | UP 主 | `owner.name` | **`upper.name`** |
 * | 播放 / 弹幕 | `stat.view` / `stat.danmaku` | **`cnt_info.play` / `cnt_info.danmaku`** |
 * | 发布时间 | `pubdate` | **`pubtime`** |
 *
 * **复用的后果不是报错，是"能加载但一片空"**：
 * `title` 和 `duration` 碰巧同名，所以标题和时长正常显示 ——
 * 而**封面、UP 主、播放量、日期全读不到**。
 * 屏幕上看起来像"网不好、图没加载出来"，实际是**四个字段一个都没读对**。
 * 实测截图（模拟器，已登录，默认收藏夹 318 条）：4 列卡片只有文字，封面整片黑。
 *
 * 这正是 `docs/99` §C 那条"编译过 + 单测全绿证明不了功能活着"的又一例 ——
 * 而且它比一般情况更阴：**页面有内容、布局正常、不报错**，只是信息少了一半。
 *
 * ## 为什么保留 `pic` / `owner` 作为兜底
 *
 * B 站同一个接口在不同灰度/版本下字段名有过漂移，两条都读一遍的代价是零，
 * 而只认一条的话下次漂移又要重踩一遍。
 */
fun parseFavResources(json: String): List<top.bilitv.data.model.FeedItem> {
    val arr = dataOf(json)?.arrOrNull("medias") ?: return emptyList()
    val out = ArrayList<top.bilitv.data.model.FeedItem>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val bvid = o.str("bvid").ifBlank { o.str("id") }
        // 收藏夹里可能混着已失效/已删除的稿件，它们没有合法 bvid，直接跳过
        if (bvid.isBlank() || !bvid.startsWith("BV")) continue
        val cnt = o.objOrNull("cnt_info")
        out.add(
            top.bilitv.data.model.FeedItem(
                bvid = bvid,
                badge = badgeOf(o),
                title = o.str("title"),
                cover = o.str("cover").ifBlank { o.str("pic") },
                ownerName = o.objOrNull("upper")?.str("name")
                    ?: o.objOrNull("owner")?.str("name").orEmpty(),
                durationSec = o.optInt("duration"),
                // 统计和发布时间也各留一条旧名字的兜底，理由同上（字段名漂移过一次了）
                viewCount = cnt?.num("play") ?: o.objOrNull("stat")?.num("view") ?: 0L,
                danmakuCount = cnt?.num("danmaku") ?: o.objOrNull("stat")?.num("danmaku") ?: 0L,
                pubDateSec = o.num("pubtime").takeIf { it > 0L } ?: o.num("pubdate"),
                tname = o.str("tname"),
            )
        )
    }
    return out
}
