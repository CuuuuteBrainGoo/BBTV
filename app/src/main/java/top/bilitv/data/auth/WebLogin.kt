package top.bilitv.data.auth

import java.security.MessageDigest

/**
 * B 站**网页端**扫码登录（`passport-login/web/qrcode`）。
 *
 * ## 为什么和 TV 端那套并存，而不是替换掉 [TvLogin]
 *
 * 两套各有明确适用场景（2026-09-29 逆向 blbl / MyTVB / chinasoul.bt 三家得出，
 * 三家**都实现了两套**或至少网页端）：
 *
 * | | 网页端（本文件） | TV 端（[TvLogin]） |
 * |---|---|---|
 * | 拿到的凭据 | `SESSDATA` + **`refresh_token`** | `SESSDATA` + `access_token` |
 * | 设备管理显示 | 正常（网页登录） | "未知设备"或 TV 设备 |
 * | 凭据"户口" | **与后续所有网页接口一致** ★ | TV 户口，打网页接口有指纹矛盾 |
 * | 能否 Cookie 续期 | **能**（有 refresh_token） | ❌ 不能 |
 * | 接口 | GET `qrcode/generate` + `qrcode/poll` | POST + appkey 签名 |
 *
 * **默认走网页端**。理由：我们所有业务接口（首页/搜索/取流/动态）都是
 * `api.bilibili.com` 的网页端接口，用网页端登录取的凭据"户口一致"，
 * 从根上少一层风控矛盾。TV 端保留作为**兜底**（万一网页端流程改了还能用）。
 *
 * ## 和 TV 端最关键的两处不同
 *
 * 1. **是 GET，不是 POST**。TV 端反过来（它必须 POST，用 GET 会 405）。
 * 2. **两个接口**：`generate` 申请码、`poll` 轮询，参数是 `qrcode_key`
 *    （TV 端那套只有一个 `auth_code` 走到底）。
 *
 * ## 返回码含义（网页端与 TV 端**代码不同，别混用**）
 *
 * - `86101` 未扫码
 * - `86090` **已扫码待确认** ← 必须反馈给用户，否则他会以为没扫上
 * - `86038` 二维码已失效
 * - `0` 成功
 */
object WebLogin {

    /** 申请二维码。GET，无需签名、无需 cookie。 */
    const val PATH_QR_GENERATE = "/x/passport-login/web/qrcode/generate"

    /** 轮询扫码结果。GET，带 `qrcode_key`。 */
    const val PATH_QR_POLL = "/x/passport-login/web/qrcode/poll"

    /**
     * 多域名 Cookie 同步。
     *
     * ## 为什么这一步不能省
     *
     * B 站有多个域名（`bilibili.com` / `bilibili.tv` / 直播站等）。
     * 在一个域名登录后，**其它域名默认不知道你登录了**。
     * 不调它会出现"首页能看、直播要重新登录"这类莫名其妙的问题。
     *
     * 注意：这个接口返回的是**各个域名对应的 Cookie 串**，值都是凭据，
     * 解析时绝不打日志。
     */
    const val PATH_SSO_LIST = "/x/passport-login/web/sso/list"

    /**
     * 轮询结果。和 [TvLoginPoll] 分开定义（**不是重复**）——
     * 两者的返回码、字段名、成功后的凭据结构都不同，合成一个反而要在内部到处 if。
     */
    sealed interface Poll {
        /**
         * 成功。`cookies` 里含 `SESSDATA` / `bili_jct` / `DedeUserID`；
         * `refreshToken` 是网页端**独有**的续期凭据。
         */
        data class Success(
            val cookies: Map<String, String>,
            val refreshToken: String,
        ) : Poll

        /** 已扫码，等待手机确认 */
        data object Scanned : Poll

        /** 还没扫 */
        data object Waiting : Poll

        /** 二维码过期 */
        data object Expired : Poll

        data class Failed(val code: Int, val message: String) : Poll
    }
}

/** 一次网页端扫码会话。 */
data class WebQrSession(
    /** 二维码里画的 URL（用户拿手机扫的就是它）。 */
    val url: String,
    /** 轮询用的 key。**是凭据性质，不进日志。** */
    val qrcodeKey: String,
)

/**
 * 从 `/sso/list` 的响应里抠出"要写进我们 cookie 仓库的那几条"。
 *
 * ## 响应长什么样
 *
 * ```json
 * { "code": 0, "data": { "sso": ["https://passport.bilibili.com/...?...&sso=..."] } }
 * ```
 *
 * 每个元素是一条 URL，`?` 后面是 `Set-Cookie` 风格串。我们要的是其中的
 * `SESSDATA` / `bili_jct` / `DedeUserID`（其它字段对纯网页端请求没用）。
 *
 * ## 为什么写成纯函数 + 单独测
 *
 * 这是个**藏在 URL 里的 cookie 串解析**，字符串处理最容易写出"看着对但不全"的 bug
 * （少一条 `DedeUserID` 的表现是"点赞报错"，很难联想到是这里）。
 * 所以把它独立出来，用真实响应形状做单测。
 *
 * @return cookie 名 → 值。解析不出来的条目跳过，**不抛异常**。
 */
fun parseSsoCookies(ssoUrls: List<String>): Map<String, String> {
    val want = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
    val out = LinkedHashMap<String, String>()
    ssoUrls.forEach { raw ->
        // 取 ? 之后的查询串；没有 ? 就跳过
        val q = raw.substringAfter('?', "")
        if (q.isBlank()) return@forEach
        q.split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@forEach
            val name = pair.substring(0, idx)
            if (name !in want) return@forEach
            val value = pair.substring(idx + 1)
            if (value.isNotBlank()) out[name] = value
        }
    }
    return out
}
