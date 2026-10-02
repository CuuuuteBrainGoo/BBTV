package top.bilitv.data.model

/**
 * 一个 UP 主。
 *
 * ## 为什么单独建一个模型，而不是塞进 [FeedItem]
 *
 * [FeedItem] 是"一个**视频**卡片"的模型（封面 16:9、时长、播放量……）。
 * UP 主没有这些字段 —— 他有的是一张**头像**、一个名字、粉丝数。
 * 硬塞进去的结果是 FeedItem 里一半字段永远为空，然后每个用到的地方都得
 * 判"这条到底是视频还是人"。关注页一屏就是十几个 UP，那种判断会到处都是。
 *
 * ## 字段的用途
 *
 * | 字段 | 界面上的位置 |
 * |---|---|
 * | [name] | 头像下面的名字 |
 * | [face] | 头像（圆形裁切） |
 * | [fans] | 名字下面一行小字 |
 * | [sign] | 长按/详情时才显示；列表里不放，会挤 |
 *
 * @param mid 用户号。点进他的主页靠它。
 * @param name 昵称。
 * @param face 头像 URL。**可能是 `http://` 或 `//` 开头**，
 *   显示前统一走 `fixedScheme()`（见 `docs/15` 同类问题）。
 * @param fans 粉丝数。0 表示接口没给 —— 界面**不显示 0**，那是假信息。
 * @param sign 个性签名。可为空串。
 * @param officialDesc 认证说明（如「bilibili 官方账号」）。空串表示没认证。
 * @param live 直播间号。> 0 表示**正在直播**（接口里 `live_status==1` 才带上）。
 */
data class UpUser(
    val mid: Long,
    val name: String,
    val face: String,
    val fans: Long = 0L,
    val sign: String = "",
    val officialDesc: String = "",
    /** 正在直播时的直播间号；0 表示没在播。界面用它画一个"直播中"角标 */
    val liveRoomId: Long = 0L,
)

/**
 * 一页「UP 主投稿」的结果。
 *
 * ## 为什么要把 [total] 和 [items] 绑在一起返回
 *
 * 它俩来自**同一个响应**（`data.list.vlist[]` 和 `data.page.count`）。
 * 拆成两个方法就要打两次请求，而这两条信息在界面上是同时出现的
 * （「共 328 个投稿」+ 下面的网格）。分开发请求除了多一个往返，
 * 还会让两句话**可能来自不同时刻** —— 页码显示 328、网格里 0 条。
 *
 * @param items 这一页的投稿。
 * @param total 该 UP 一共多少投稿（不是"本页几条"）。0 表示接口没给。
 */
data class UpVideoPage(
    val items: List<FeedItem>,
    val total: Long = 0L,
)

data class UpProfile(val mid: Long, val name: String, val face: String, val sign: String,
    val level: Int?, val fans: Long?, val relation: Int?) {
    val followed: Boolean get() = relation == 2 || relation == 6
    val blocked: Boolean get() = relation == 128
}

/**
 * **我自己的**账号资料（`/x/web-interface/nav` 的 `data`）。
 *
 * 和 [UpUser] 分开的理由：两者字段几乎没有交集 —— UP 主有粉丝数、签名、
 * 认证说明；我自己的资料有等级、会员状态、UID。硬合成一个类型的话，
 * 一半字段在每种用法里都是空的。
 *
 * @param mid 我的 UID。
 * @param name 昵称。空串表示接口没给 —— 界面**不要**用"用户12345"之类的编号顶上，
 *   那看起来像真名。
 * @param face 头像 URL。可能 `http://` 或 `//` 开头，显示前走 `fixedScheme()`。
 * @param level 当前等级（0~6）。0 表示接口没给。
 * @param vip 是否大会员。
 */
data class MyProfile(
    val mid: Long = 0L,
    val name: String = "",
    val face: String = "",
    val level: Int = 0,
    val vip: Boolean = false,

    /**
     * 硬币余额（`nav` 响应里的 `money`）。
     *
     * ★ 2026-09-30 少爷（截图批注）：「**不要抓动态这个字段了，抓用户硬币数量**」
     * ——「我的」页第三个数字从「动态」改成「硬币」。
     *
     * ⚠️ 用 `Double` 而不是 `Long`：这个接口给的是小数（如 `42.5`）。
     * 显示时再取整，不要在解析层丢精度。
     */
    val coins: Double = 0.0,
)

/**
 * 我的关注 / 粉丝 / 动态计数（`/x/web-interface/nav/stat`）。
 *
 * ## 三个字段都是**可空**，这不是洁癖
 *
 * 取不到时是 `null`，不是 `0`。界面据此显示 `--`。
 * 写成 `Long = 0L` 的后果是：接口没给这一项时屏幕上是"关注 0" ——
 * 一句我们自己编的假话（直播页的角标就是这么骗了 30 张卡，见 `docs/19` §2）。
 */
data class MyStat(
    val following: Long? = null,
    val follower: Long? = null,
    val dynamic: Long? = null,
)

/**
 * `/x/web-interface/nav` 的三种结果。
 *
 * ## 为什么要单独一个类型，而不是"解析失败返回 null"
 *
 * 未登录和接口坏掉是**两件用户能做的事完全不同**的事：
 *
 * | 结果 | 界面该说的话 |
 * |---|---|
 * | [Ok] | 正常显示 |
 * | [NotLoggedIn] | 未登录（或登录**过期了**）→ 给「扫码登录」 |
 * | [Unsupported] | 接口变了 / 拿不到 → 给「重新加载」 |
 *
 * 合并成 null 的话，界面上只剩一句"加载失败"，用户唯一能做的
 * 就是反复重试（这正是这个项目一直在治的病，见 `docs/15` §2.1）。
 */
sealed interface MyProfileResult {
    /** 服务端明确给了资料 */
    data class Ok(val profile: MyProfile) : MyProfileResult

    /**
     * 服务端明确说"没登录"（`data.isLogin == false`）。
     *
     * ⚠️ 注意这不等于"本机没有凭证" —— 凭证过期时本机还留着 SESSDATA，
     * 服务端却已经不认了。所以这个分支的文案是「登录已过期」，不是「还没登录」。
     */
    data object NotLoggedIn : MyProfileResult

    /** 拿不到 / 结构不对（接口变了、被风控、数据被截断……） */
    data object Unsupported : MyProfileResult
}
