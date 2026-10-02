package top.bilitv.data.auth

import java.security.MessageDigest

/**
 * B 站电视端扫码登录（`passport-tv-login`）。
 *
 * ## 为什么用 TV 端这套，而不是网页端那一套
 *
 * 电视上没法输入账号密码，网页端的登录接口又要过一堆风控（极验、短信）。
 * `passport-tv-login` 是**官方给电视 App 用的**那一条：拿二维码 → 手机 App 扫 → 轮询拿 Cookie。
 * 它只要一个 `appkey`（TV 端公开的固定值），不需要用户输任何东西。
 *
 * ## 和 B 站其它接口的两处不同
 *
 * 1. **是 POST，不是 GET**。用 GET 会得到 `405 Method Not Allowed`
 *    —— 我第一次就踩了这个，返回体只有 19 个字节的 `Method Not Allowed`，
 *    看起来像"接口没了"，其实是方法错了。
 * 2. **签名方式不一样**：这里的签名是 `md5(按 key 排序拼好的查询串 + appsec)`，
 *    和 WBI 那套（`w_rid` + `mixin_key`）完全是两回事。别混用。
 *
 * 这两条都写成了**纯函数 + 单测**（`TvLoginTest`），因为签名错的表现是
 * `code=-3 签名错误`，而"签名错了"和"appkey 换了"从返回里分不出来。
 */
object TvLogin {

    /** TV 端公开的 appkey。不是密钥——它随客户端分发，人人都有。 */
    const val APPKEY = "4409e2ce8ffd12b8"

    /**
     * 配套的 appsec。
     *
     * ⚠️ 它虽然叫 "sec"，但和 appkey 一样是**随 TV 客户端分发的固定值**，
     * 不是账号私密信息，写进代码不构成泄漏（对照我们自己的 SESSDATA —— 那个才绝不能进代码）。
     * 这一点已经按项目的脱敏规则确认过，见 `docs/03` §0。
     */
    private const val APPSEC = "59b43e04ad6965f34319062b478f83dd"

    /**
     * 给参数加 `ts` 和 `sign`。
     *
     * 规则：把**除 sign 之外**的全部参数按 key 升序拼成 `k=v&k=v`，
     * 末尾直接接 appsec，整体取 md5 就是 sign。**注意值不做 URL 编码** ——
     * 编码过再拼会和官方签名对不上。
     */
    fun sign(params: Map<String, String>, ts: Long): Map<String, String> {
        val withTs = params.toMutableMap().apply { put("ts", ts.toString()) }
        val query = withTs.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        val out = withTs.toMutableMap()
        out["sign"] = md5(query + APPSEC)
        return out
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** 一次扫码会话：给手机扫的那个 URL，以及轮询要用的 auth_code */
data class TvQrSession(val url: String, val authCode: String)

/**
 * 轮询结果。
 *
 * `code` 的含义（官方文档）：
 * - `0` 成功
 * - `86038` **二维码已过期**，要重新申请一张，不能继续轮询
 * - `86090` 已扫码，等待手机端确认 —— 这一步界面要变了，得让用户知道"扫到了"
 * - `86039` 未确认（还没扫）
 *
 * ★ 把 `86090` 单独拎出来是有意义的：用户扫完之后如果界面没有任何变化，
 * 他会以为没扫上、反复重扫。**"已被扫到"是一个必须反馈给用户的状态。**
 */
sealed interface TvLoginPoll {
    data class Success(
        val cookies: Map<String, String>,
        val accessToken: String,
        val refreshToken: String,
    ) : TvLoginPoll

    /** 已扫码，等待手机确认 */
    data object Scanned : TvLoginPoll

    /** 还没扫 */
    data object Waiting : TvLoginPoll

    /** 二维码过期，需要重新申请 */
    data object Expired : TvLoginPoll

    data class Failed(val code: Int, val message: String) : TvLoginPoll
}
