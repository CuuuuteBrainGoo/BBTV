package top.bilitv.data.model

/**
 * 动态流（`/x/polymer/web-dynamic/v1/feed/all`）里的一条**能播的**内容。
 *
 * ## ★★ 为什么这个模型只有"视频"一种形态
 *
 * B 站的动态流是一个**混装容器**，`items[].type` 有 11 种
 * （视频投稿 / 图文 / 纯文字 / 转发 / 专栏 / 音频 / 直播预告 / 合集更新 / 收藏夹 / 优惠券 / 已删除）。
 *
 * 但少爷对这个 App 的要求是「**只留能播的**」。所以：
 * - 解析阶段就把**解析不出 `bvid` 的条目丢掉**（见 `Parsers.parseDynamicFeed`）；
 * - 模型层因此**只需要装视频** —— 没有"类型"字段要判，界面也不可能画出一张
 *   "点了什么都不发生"的卡片。
 *
 * 这是一条**结构性保证**，不是一个 `when` 分支：能进到这个类里的，一定是能播的。
 *
 * ## ⚠️ 这一整块**没有在真实响应上验过**
 *
 * 动态流是**登录门禁**接口。实测（`tools/probe_dynamic.py`，游客态）：
 * `/x/polymer/web-dynamic/v1/feed/all`、`type=video`、`.../all/update`、
 * 桌面版路径 —— **一律 `code=-101 账号未登录`**，带不带 WBI 签名结果一样。
 * 所以字段名只能照官方接口文档写，拿不到真实数据校准。
 *
 * 由此定了三条**更保守**的规矩（登录后验第一件事就是核这一条）：
 * 1. `bvid` 取不到 → **丢掉这条**，不显示灰卡（灰卡也占一个焦点位）。
 * 2. 计数取不到 → 记 `null`，界面**不画这个数字**；绝不写 0（「0 播放」是假话，
 *    这是直播页角标踩过的坑，见 `docs/19` §2）。
 * 3. 时间取 `pub_ts` 时间戳，**由本机算相对时间**（`components/Format.formatPubDate`）——
 *    不用接口给的 `pub_time` 文本。理由：那是**又一个我没法验的字段**，
 *    而 `formatPubDate` 是纯函数、已有单测覆盖，且 `pubTs` 缺失时它天然返回空串
 *    （不会编出一个"1970年"）。这也和「观看记录」页的口径一致。
 *
 * @param id 动态 id（`id_str`）。只用于列表。
 * @param bvid 视频号。**必填** —— 没有它这条根本不该存在。
 * @param title 视频标题。
 * @param cover 封面。B 站会返回 `http://` 或 `//` 开头，画之前要过 `fixedScheme()`。
 * @param durationText 时长文本（`"12:34"` 这样的字符串）。
 * @param ownerMid / ownerName / ownerFace 这条动态的**发出者**。
 *   转发动态取的是**转发者**（"这条是谁发的"），另用 [forwarded] 打角标。
 * @param action 更新动作，如「投稿了视频」「转发动态」。接口没给就是空串，界面不画。
 * @param pubTs 发布时间戳（**秒**）。`null` 表示接口没给 —— 界面就不显示时间。
 * @param caption 动态正文。转发时是转发语。
 * @param forwarded 是转发来的吗。
 * @param play / danmaku / like / comment / forward 计数。**可空** ——
 *   `null` 表示接口没给这个字段，界面**不画**（不是 0）。见上方第 2 条规矩。
 */
data class DynamicItem(
    val id: String,
    val bvid: String,
    val title: String,
    val cover: String,
    val durationText: String,
    val ownerMid: Long,
    val ownerName: String,
    val ownerFace: String,
    val action: String,
    val pubTs: Long?,
    val caption: String,
    val forwarded: Boolean,
    val play: Long?,
    val danmaku: Long?,
    val like: Long?,
    val comment: Long?,
    val forward: Long?,
    val badge: String = "",
)

/**
 * 动态流的一页。
 *
 * [code] **原样带回服务端响应码** —— 这是这个接口和项目里其他列表接口
 * 最大的不同：它必须把码带上来，因为 `-101`（没登录）要在界面上
 * 变成一句完全不同的话（「去登录」和「重新加载」是两件事）。
 * 其他列表接口靠"列表空 + `isLoggedIn()`"就能分，这个不能。
 *
 * @param code 0 成功；-101 没登录；负数其他是失败。另外有两个**我们自己编的**码，
 *   见 [CODE_REQUEST_FAILED] / [CODE_UNPARSEABLE]。
 * @param nextOffset 下一页游标（等于**本页最后一条**的 id）。
 * @param hasMore 还有下一页吗。
 */
data class DynamicFeed(
    val code: Int,
    val items: List<DynamicItem>,
    val nextOffset: String,
    val hasMore: Boolean,
)

/**
 * 服务端「账号未登录」。
 *
 * 实测（`tools/probe_dynamic.py`）：动态流所有变体在游客态都回这个码。
 * 注意它**不是**我们推断出来的 —— 是接口明说的。
 */
const val CODE_NOT_LOGGED_IN = -101

/**
 * 请求直接抛异常（断网 / 超时 / TLS 失败）。
 *
 * ⚠️ **这是我们自己编的码，不是服务端给的。** 之所以单独一个值而不是也用 -1…
 * 是因为 -1 在 HTTP 语义里不出现，用它当"传输层失败"的哨兵值，
 * 和真实响应码不会撞车。
 */
const val CODE_REQUEST_FAILED = -1

/**
 * 响应不是合法 JSON，或者最外层结构不认识。
 *
 * ⚠️ 同样是**我们自己编的**。它和 [CODE_REQUEST_FAILED] 分开，
 * 是因为修法完全不同：前者查网络，后者查接口是不是改结构了。
 */
const val CODE_UNPARSEABLE = -2

/** 时间倒序；服务端分页重叠不重复造卡，缺少动态 ID 时用有效视频号兜底。 */
fun mergeDynamicItems(existing: List<DynamicItem>, incoming: List<DynamicItem>): List<DynamicItem> =
    (existing + incoming).distinctBy { it.id.ifBlank { it.bvid } }
        .sortedByDescending { it.pubTs ?: Long.MIN_VALUE }
