package top.bilitv.data.auth

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import top.bilitv.util.AppLog
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * 网页端风控维护（Web 风控三件套 + Cookie 刷新）。
 *
 * ## 为什么必须有这个类（2026-09-29 三款参考 App 逆向 + 实测得出）
 *
 * 【现象】手机上用 TV 扫码登录后，普通视频反而打不开；
 *        退出登录（游客态）却能正常看。
 *
 * 【根因】B 站对"带登录凭据的请求"风控标准**高于**游客态。
 *        三款参考客户端（blbl / MyTVB / chinasoul.bt）**无一例外**都在登录后
 *        补齐了下面这四样东西，而我们项目一样都没有：
 *
 * | 项目 | 作用 | 缺失后果 |
 * |---|---|---|
 * | `buvid3 / buvid4 / b_nut` | 浏览器身份指纹 | 被判"非浏览器"，`-352 风控校验失败` |
 * | buvid 激活（`ExClimbWuzhi`） | 上报"这个 buvid 是活的" | buvid 不被服务端认可，等于没有 |
 * | `bili_ticket` | 风控门票（HMAC-SHA256 签名换取） | 高价值接口（取流）降级 |
 * | Cookie 刷新（每日） | 维持会话新鲜度 | 登录态静默失效 |
 *
 * 【证据】三家的字符串常量池里都能扫到 `ExClimbWuzhi` / `GenWebTicket` /
 *        `bili_ticket` / `buvid4` / `b_nut` / `cookie/refresh`（见 `docs/27`）。
 *
 * ## 调用时机
 *
 * - **[ensureHealthyForPlay]：进播放页之前**。三件套一次补齐（内部有缓存，不会重复打）。
 *   这是"起播前的一次体检"，成本是首次 3 个请求，之后每天最多 1 次。
 * - **[ensureDailyMaintenance]：App 启动时**。只做 ticket + Cookie 刷新，抢在播放之前。
 *
 * ## ⚠️ 失败不阻塞
 *
 * 所有方法**都不抛异常**（内部 `runCatching`）。
 * 理由：这是"锦上添花"的维护，失败了大不了回到"没维护"的状态（也就是现在这样），
 * 但**绝不能因为取 ticket 失败就让整个播放流程挂掉**。
 * 失败只记 warning，不记 error —— 它本来就是尽力而为。
 */
object WebCookieMaintainer {

    private const val TAG = "WebCookie"

    /** bili_ticket 的固定 key_id（随网页端分发的公开值，不是密钥）。 */
    private const val BILI_TICKET_KEY_ID = "ec02"

    /** bili_ticket 的 HMAC 密钥。同样是**网页端公开的固定值**。 */
    private const val BILI_TICKET_HMAC_KEY = "XgwSnGZ1p"

    /**
     * Cookie 刷新时上报的来源标识。
     * `main_web` = 主站网页端。换别的值服务端可能不认。
     */
    private const val REFRESH_SOURCE = "main_web"

    /**
     * 从 `/correspond/1/{path}` 的 HTML 里抠出 `refresh_csrf`。
     *
     * 这个值是网页端 Cookie 刷新流程的第一步产物 —— B 站故意把它藏在 HTML 里
     * （而不是 JSON 接口里），就是为了挡脚本。所以要解析 HTML。
     */
    private val refreshCsrfRegex =
        Regex("<div\\s+id=\"1-name\">\\s*([0-9a-fA-F]{16,})\\s*</div>")

    /**
     * Cookie 刷新用的 RSA 公钥。
     *
     * 用途：把时间戳加密成 `correspondPath`（拼进 URL 的那个）。
     * 这是 `bilibili-api-docs` 的 `cookie_refresh.md` 里公开记录的值，
     * 和登录 appkey 一样属于"随客户端分发"的公开信息。
     */
    private val correspondPublicKey: PublicKey by lazy {
        val derBase64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg" +
                "Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71" +
                "nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40" +
                "JNrRuoEUXpabUzGB8QIDAQAB"
        val keyBytes = Base64.decode(derBase64, Base64.DEFAULT)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
    }

    private val refineMutex = Mutex()

    // ------------------------------------------------------------ 对外入口

    /**
     * ★ 起播前体检 —— **播放页进入时调一次**。
     *
     * 顺序有讲究：先指纹（后续请求要带），再激活 buvid（依赖指纹里的 buvid3），
     * 最后取 ticket。
     */
    suspend fun ensureHealthyForPlay() {
        ensureWebFingerprintCookies()
        ensureBuvidActiveOncePerDay()
        ensureBiliTicket()
    }

    /**
     * 日常维护 —— **App 启动时调一次**。
     *
     * 比 [ensureHealthyForPlay] 少了两件：指纹和 buvid 激活。
     * 那两件在起播前的上下文里做更合适（那时才有明确的"要给用户放视频"的意图）。
     */
    suspend fun ensureDailyMaintenance() {
        ensureBiliTicket()
        refreshCookieIfNeededOncePerDay()
    }

    // ------------------------------------------------------------ ① Web 指纹

    /**
     * 补齐 Web 端指纹 cookie：`buvid3` / `buvid4` / `b_nut`。
     *
     * ## 三个字的分工
     *
     * - `buvid3`：设备指纹主体。**在响应体里**（`data.b_3`），不在 `Set-Cookie`，
     *   所以 cookie jar 永远收不到，必须手动塞。
     * - `buvid4`：指纹的第二段（浏览器版本相关的哈希）。
     * - `b_nut`：**访问首页时由 `Set-Cookie` 下发**，是个"我真访问过网页"的凭证。
     *   我们只拿 `buvid3` 的时候整整少了它 —— 这是"看起来不像浏览器"的一大原因。
     *
     * ## 为什么"访问一次首页"不能省
     *
     * `b_nut` 只在真实的首页响应里下发，没有任何接口能单独拿到它。
     * 这一步就是"假装我打开过一次 B 站首页"。
     */
    suspend fun ensureWebFingerprintCookies() = withContext(Dispatchers.IO) {
        val hasBuvid3 = !AuthBus.cookies.get("buvid3").isNullOrBlank()
        val hasBNut = !AuthBus.cookies.get("b_nut").isNullOrBlank()
        val hasBuvid4 = !AuthBus.cookies.get("buvid4").isNullOrBlank()
        if (hasBuvid3 && hasBNut && hasBuvid4) return@withContext

        // 3.1 首页 —— 拿 b_nut
        if (!hasBNut) {
            runCatching { AuthBus.rawGet("https://www.bilibili.com/", captureCookies = true) }
                .onFailure { AppLog.w(TAG, "首页访问失败（b_nut 可能拿不到）：${it.javaClass.simpleName}") }
        }

        // 3.2 finger/spi —— 拿 buvid3 / buvid4
        if (!hasBuvid3 || !hasBuvid4) {
            runCatching {
                val json = JSONObject(AuthBus.rawGet("https://api.bilibili.com/x/frontend/finger/spi"))
                val data = json.optJSONObject("data") ?: JSONObject()
                val b3 = data.optString("b_3").trim()
                val b4 = data.optString("b_4").trim()
                val expires = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                if (b3.isNotBlank() && !hasBuvid3) AuthBus.cookies.put("buvid3", b3, expires)
                if (b4.isNotBlank() && !hasBuvid4) AuthBus.cookies.put("buvid4", b4, expires)
                AppLog.i(TAG, "指纹补齐：b3=${b3.isNotBlank()} b4=${b4.isNotBlank()}")
            }.onFailure { AppLog.w(TAG, "finger/spi 失败：${it.javaClass.simpleName}") }
        }
    }

    // ------------------------------------------------------------ ② buvid 激活

    /**
     * 激活 buvid（每天最多一次）。
     *
     * ## 这一步在干什么
     *
     * `buvid3` 只是个"编号"。服务端要确认这个编号**背后有真实浏览器在跑**，
     * 才认它。激活方式就是往 `ExClimbWuzhi`（风控网关）上报一段**浏览器环境数据**：
     * 操作系统、屏幕相关参数、以及一段 PNG 结尾的 base64 标记。
     *
     * 那段"PNG 标记"是 PiliPlus 之类的项目逆向出来的固定尾巴 —— 服务端校验它。
     * 我们不理解它为什么长这样，但**照抄是对的**：这是服务端期望的形状。
     *
     * ## 为什么要按天做
     *
     * 服务端按天记"这个 buvid 今天活着"。每天一次就够，多了反而可疑。
     */
    suspend fun ensureBuvidActiveOncePerDay() = withContext(Dispatchers.IO) {
        val midStr = AuthBus.cookies.get("DedeUserID")?.trim().orEmpty()
        val mid = midStr.toLongOrNull()?.takeIf { it > 0 } ?: return@withContext
        val epochDay = System.currentTimeMillis() / 86_400_000L
        if (AuthBus.prefs.lastBuvidActiveMid == mid && AuthBus.prefs.lastBuvidActiveDay == epochDay) {
            return@withContext
        }

        runCatching {
            val jsonData = JSONObject()
                .put("3064", 1)
                .put("39c8", "333.1387.fp.risk")
                .put(
                    "3c43",
                    JSONObject()
                        .put("adca", "Linux")
                        .put("bfe9", randomPngTailBase64()),
                )
                .toString()

            val headers = buildMap {
                put("Content-Type", "application/json")
                put("env", "prod")
                put("app-key", "android64")
                put("x-bili-aurora-zone", "sh001")
                put("x-bili-mid", mid.toString())
                genAuroraEid(mid)?.let { put("x-bili-aurora-eid", it) }
                put("Referer", "https://www.bilibili.com")
            }

            AuthBus.rawPost(
                "https://api.bilibili.com/x/internal/gaia-gateway/ExClimbWuzhi",
                body = JSONObject().put("payload", jsonData).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
                headers = headers,
                attachCookies = true,
            )

            AuthBus.prefs.lastBuvidActiveMid = mid
            AuthBus.prefs.lastBuvidActiveDay = epochDay
            AppLog.i(TAG, "buvid 激活成功（mid 不回显）")
        }.onFailure { AppLog.w(TAG, "buvid 激活失败：${it.javaClass.simpleName}") }
    }

    /**
     * 生成 x-bili-aurora-eid（极光 ID）。
     *
     * 算法：`mid` 的十进制字符串，逐字节与固定 key `ad1va46a7lza` 循环异或，
     * 再 base64（无填充）。这是 App 端公开的混淆方式，不是加密。
     */
    private fun genAuroraEid(mid: Long): String? {
        if (mid <= 0) return null
        val key = "ad1va46a7lza".toByteArray()
        val input = mid.toString().toByteArray()
        val out = ByteArray(input.size)
        for (i in input.indices) out[i] = (input[i].toInt() xor key[i % key.size].toInt()).toByte()
        return Base64.encodeToString(out, Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * 造那段 PNG 尾巴。
     *
     * `randPngEnd` 的前 40 字节是随机数，第 36-39 字节被**强行覆盖**成固定值
     * （0,0,0,0, 然后 73/69/78/68 = 'I','E','N','D' —— PNG 文件结尾块的名字），
     * 最后 4 字节再随机。
     *
     * 这个形状是从参考实现里抄的，服务端按它判"这是个真实浏览器上传的图"。
     * 不理解没关系，照形态复现即可 —— 改动它反而会失败。
     */
    private fun randomPngTailBase64(): String {
        val rand = ByteArray(32 + 8 + 4)
        java.security.SecureRandom().nextBytes(rand)
        rand[32] = 0; rand[33] = 0; rand[34] = 0; rand[35] = 0
        rand[36] = 73; rand[37] = 69; rand[38] = 78; rand[39] = 68
        val tail = ByteArray(4)
        java.security.SecureRandom().nextBytes(tail)
        for (i in 0 until 4) rand[40 + i] = tail[i]
        return Base64.encodeToString(rand, Base64.NO_WRAP)
    }

    // ------------------------------------------------------------ ③ bili_ticket

    /**
     * 取 `bili_ticket`（有效期内跳过）。
     *
     * ## 它是什么
     *
     * 网页端在**播放视频等高价值操作前**，会先领一张"门票"。
     * 服务端凭票放行；没票的请求会被降级（典型表现：清晰度被砍、或者直接拒绝）。
     *
     * ## 怎么领
     *
     * 对 `ts`（秒级时间戳）做 HMAC-SHA256，密钥是网页端公开的固定值，
     * 拼上 `key_id=ec02` 一起提交，服务端**能自己复算**这个签名 ——
     * 所以它不是"认证"（挡不住谁），而是**"你是不是按规矩调接口"的检查**。
     * 规矩走对了，就发票。
     */
    suspend fun ensureBiliTicket() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val epochDay = now / 86_400_000L

        // 已有且剩余寿命 > 6 小时 —— 直接用
        val existing = AuthBus.cookies.getWithExpiry("bili_ticket")
        if (existing != null && existing.second - now > 6 * 60 * 60 * 1000L) return@withContext
        // 今天试过了就别反复打（失败也会记住，避免每起播一次打一次）
        if (AuthBus.prefs.lastTicketCheckDay == epochDay) return@withContext
        AuthBus.prefs.lastTicketCheckDay = epochDay

        runCatching {
            val ts = (now / 1000).toString()
            val hexsign = hmacSha256Hex(BILI_TICKET_HMAC_KEY, "ts$ts")
            val params = buildMap {
                put("key_id", BILI_TICKET_KEY_ID)
                put("hexsign", hexsign)
                put("context[ts]", ts)
                AuthBus.cookies.get("bili_jct")?.takeIf { it.isNotBlank() }?.let { put("csrf", it) }
            }
            val url = "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket?" +
                params.entries.joinToString("&") { "${it.key}=${it.value}" }

            val json = JSONObject(
                AuthBus.rawPost(
                    url,
                    body = ByteArray(0).toRequestBody(null),
                    headers = mapOf("Referer" to "https://www.bilibili.com"),
                    attachCookies = true,
                )
            )
            val data = json.optJSONObject("data") ?: JSONObject()
            val ticket = data.optString("ticket").trim()
            val createdAt = data.optLong("created_at")
            val ttl = data.optLong("ttl")
            if (ticket.isBlank() || createdAt <= 0L || ttl <= 0L) {
                AppLog.w(TAG, "GenWebTicket 返回不完整：code=${json.optInt("code")}")
                return@runCatching
            }
            val expiresSec = createdAt + ttl
            AuthBus.cookies.put("bili_ticket", ticket, expiresSec * 1000L)
            AuthBus.cookies.put("bili_ticket_expires", expiresSec.toString(), expiresSec * 1000L)
            AppLog.i(TAG, "bili_ticket 已更新（有效期 ${ttl}s）")
        }.onFailure { AppLog.w(TAG, "bili_ticket 失败：${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------ ④ Cookie 刷新

    /**
     * Cookie 刷新（每天最多一次）。
     *
     * ## 为什么要做
     *
     * 网页端 Cookie 有"新鲜度"概念，服务端用 `refresh` 字段告诉你要不要续期。
     * 长期不续，`SESSDATA` 会**静默失效** —— 界面还显示着登录，
     * 但接口全返回 `-101`。这是最难查的一类问题。
     *
     * ## 三步流程（照官方网页端）
     *
     * 1. `/cookie/info` 问服务端"要不要刷新"
     * 2. 拿时间戳算 `correspondPath` → 抓 `/correspond/1/{path}` 的 HTML 取 `refresh_csrf`
     * 3. `/cookie/refresh` 换新 cookie → `/confirm/refresh` 确认
     *
     * ## 需要 refresh_token
     *
     * 这个 token 只有**网页登录**才会下发。TV 登录拿不到 —— 所以 TV 登录的用户
     * 这一步行不通（`refreshToken` 为空直接 return）。这也说明**网页登录更完整**。
     */
    suspend fun refreshCookieIfNeededOncePerDay() {
        if (!AuthBus.hasSessData()) return
        val refreshToken = AuthBus.prefs.refreshToken?.takeIf { it.isNotBlank() } ?: return

        refineMutex.withLock {
            val now = System.currentTimeMillis()
            val epochDay = now / 86_400_000L
            if (AuthBus.prefs.lastCookieRefreshDay == epochDay) return

            runCatching {
                val biliJct = AuthBus.cookies.get("bili_jct")?.takeIf { it.isNotBlank() } ?: return@runCatching

                // 1. 问服务端要不要刷
                val info = JSONObject(
                    AuthBus.rawGet(
                        "https://passport.bilibili.com/x/passport-login/web/cookie/info?csrf=$biliJct",
                        attachCookies = true,
                    )
                )
                val infoData = info.optJSONObject("data") ?: JSONObject()
                if (!infoData.optBoolean("refresh", false)) return@runCatching

                // 2. correspondPath → HTML → refresh_csrf
                val ts = infoData.optLong("timestamp", now).takeIf { it > 0 } ?: now
                val correspondPath = withContext(Dispatchers.Default) { correspondPathOf(ts) }
                val html = AuthBus.rawGet("https://www.bilibili.com/correspond/1/$correspondPath")
                val refreshCsrf = refreshCsrfRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()
                if (refreshCsrf.isBlank()) {
                    AppLog.w(TAG, "Cookie 刷新：HTML 里没找到 refresh_csrf")
                    return@runCatching
                }

                // 3. 换新 + 确认
                val refreshed = AuthBus.postFormJson(
                    "https://passport.bilibili.com/x/passport-login/web/cookie/refresh",
                    mapOf(
                        "csrf" to biliJct,
                        "refresh_csrf" to refreshCsrf,
                        "source" to REFRESH_SOURCE,
                        "refresh_token" to refreshToken,
                    ),
                )
                refreshed.optJSONObject("data")?.optString("refresh_token")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { AuthBus.prefs.refreshToken = it }

                val newJct = AuthBus.cookies.get("bili_jct")?.takeIf { it.isNotBlank() } ?: biliJct
                runCatching {
                    AuthBus.postFormJson(
                        "https://passport.bilibili.com/x/passport-login/web/confirm/refresh",
                        mapOf("csrf" to newJct, "refresh_token" to refreshToken),
                    )
                }

                AuthBus.prefs.lastCookieRefreshDay = epochDay
                AppLog.i(TAG, "Cookie 已刷新")
            }.onFailure { AppLog.w(TAG, "Cookie 刷新失败：${it.javaClass.simpleName}") }
        }
    }

    // ------------------------------------------------------------ 工具

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** `refresh_{时间戳}` 用 RSA-OAEP(SHA-256) 加密后转 hex。 */
    private fun correspondPathOf(timestampMs: Long): String {
        val cipher = runCatching { Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding") }
            .getOrElse { Cipher.getInstance("RSA/ECB/OAEPPadding") }
        cipher.init(
            Cipher.ENCRYPT_MODE,
            correspondPublicKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        return cipher.doFinal("refresh_$timestampMs".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
