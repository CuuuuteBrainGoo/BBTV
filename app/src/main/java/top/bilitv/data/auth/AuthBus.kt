package top.bilitv.data.auth

import android.content.Context
import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import top.bilitv.util.AppLog
import java.util.concurrent.TimeUnit

/**
 * 认证层的"轻量总线"：给 [WebCookieMaintainer] 用。
 *
 * ## 为什么不让 WebCookieMaintainer 直接用 BiliApi
 *
 * 会造成**循环依赖**：`BiliApi` 要在起播前调 `Maintainer`，
 * 而 `Maintainer` 又要发请求（那本来是 `BiliApi` 的职责）。
 * 两者互相持有 = 谁都没法单独初始化。
 *
 * 所以把"发请求 + 存 cookie + 存少量偏好"这三件最基础的事抽出来，
 * 放在这里。它**不认识任何业务接口**（不解析视频、不解析用户），
 * 只负责"原样发出去、原样拿回来"。
 *
 * ## 和 BiliApi 的关系
 *
 * `BiliApi` 启动时把这个总线的 cookie 快照读进去；
 * 总线在维护过程中更新了 cookie，**下一条业务请求**自然就带上了。
 * 两边共享同一份 [CookieJarLite]，所以不存在"更新了没生效"的问题。
 *
 * ## ⚠️ 凭据纪律
 *
 * cookie 的**值绝不进日志**。只看"有没有"、看长度、看名字。
 */
object AuthBus {

    private lateinit var appContext: Context
    private lateinit var prefsStore: SharedPreferences

    /** Cookie 仓库。**值不回显**是硬规矩。 */
    val cookies = CookieJarLite()

    /** 少量与风控维护相关的偏好（按天去重的标记、refresh_token）。 */
    val prefs get() = Prefs()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .cookieJar(cookies)
            .build()
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private const val TAG = "AuthBus"

    /** App 启动时调一次。 */
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefsStore = appContext.getSharedPreferences("bilitv_auth_bus", Context.MODE_PRIVATE)
    }

    /**
     * GET，返回响应体文本。
     *
     * @param captureCookies 是否把响应里的 `Set-Cookie` 收进 [cookies]。
     *        默认 true（cookie jar 已经在做，这个参数是为"显式强调"留的）。
     * @param attachCookies 是否带上当前 cookie。给公开接口（如首页）用 false 更干净。
     */
    fun rawGet(
        url: String,
        attachCookies: Boolean = false,
        captureCookies: Boolean = true,
    ): String = raw(Request.Builder().url(url).get(), attachCookies, captureCookies) {}

    /** POST，返回响应体文本。 */
    fun rawPost(
        url: String,
        body: RequestBody,
        headers: Map<String, String> = emptyMap(),
        attachCookies: Boolean = true,
    ): String = raw(
        Request.Builder().url(url).post(body),
        attachCookies,
        captureCookies = true,
    ) { headers.forEach { (k, v) -> addHeader(k, v) } }

    /** 表单 POST，返回解析后的 JSON。 */
    fun postFormJson(url: String, form: Map<String, String>): JSONObject {
        val body = okhttp3.FormBody.Builder()
            .apply { form.forEach { (k, v) -> add(k, v) } }
            .build()
        return JSONObject(rawPost(url, body))
    }

    private inline fun raw(
        builder: Request.Builder,
        attachCookies: Boolean,
        captureCookies: Boolean,
        customize: Request.Builder.() -> Unit,
    ): String {
        builder.header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .header("Accept", "application/json, text/plain, */*")
            .customize()

        if (attachCookies) {
            val header = cookies.header()
            if (header.isNotBlank()) builder.header("Cookie", header)
        }

        val resp = client.newCall(builder.build()).execute()
        resp.use {
            if (captureCookies) {
                // cookie jar 会自动收；这里额外记一条"收了几个"便于排查（不记值）
                val n = resp.headers("Set-Cookie").size
                if (n > 0) AppLog.i(TAG, "收到 $n 条 Set-Cookie（不回显内容）")
            }
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                AppLog.w(TAG, "HTTP ${resp.code} @ ${resp.request.url.encodedPath}")
            }
            return text
        }
    }

    /** 有没有 SESSDATA 凭据。 */
    fun hasSessData(): Boolean = !cookies.get("SESSDATA").isNullOrBlank()

    // ---------------------------------------------------------- 内部：偏好

    class Prefs internal constructor() {
        private fun p() = prefsStore

        /** bili_ticket 上次检查的日子（epochDay）。 */
        var lastTicketCheckDay: Long
            get() = p().getLong("ticket_day", -1L)
            set(v) = p().edit().putLong("ticket_day", v).apply()

        /** Cookie 刷新上次执行的日子。 */
        var lastCookieRefreshDay: Long
            get() = p().getLong("refresh_day", -1L)
            set(v) = p().edit().putLong("refresh_day", v).apply()

        /** buvid 激活上次执行的日子 + mid（两个一起判，换账号要重做）。 */
        var lastBuvidActiveDay: Long
            get() = p().getLong("buvid_day", -1L)
            set(v) = p().edit().putLong("buvid_day", v).apply()

        var lastBuvidActiveMid: Long
            get() = p().getLong("buvid_mid", 0L)
            set(v) = p().edit().putLong("buvid_mid", v).apply()

        /**
         * 网页端登录才有的 refresh_token。
         *
         * ⚠️ 这是**凭据**，只存本地。绝不打日志、绝不进版本库。
         * 这里用普通 SharedPreferences 而不是 EncryptedSharedPreferences 是
         * **有意的取舍**：它单独放进加密存储会引入一次 Keystore 初始化（可能失败），
         * 而它只用于"续期"、丢了最坏结果是重新登录，不泄漏账号本身。
         * SESSDATA 那种真正的会话凭据仍然只留在 CredentialStore 的加密存储里。
         */
        var refreshToken: String?
            get() = p().getString("refresh_token", null)
            set(v) = p().edit().putString("refresh_token", v).apply()
    }
}

/**
 * 极简 Cookie 仓库。
 *
 * 只做三件事：按名读、按名写（带过期时间）、拼成 `Cookie` 请求头。
 * **不按 URL 过滤** —— 我们打的所有域名都吃同一份 cookie，过滤反而会漏。
 *
 * 用 `okhttp3.CookieJar` 是为了让 OkHttp 自动收 `Set-Cookie`；
 * 手动塞的那些（`buvid3` / `bili_ticket`）走 [put]。
 */
class CookieJarLite : okhttp3.CookieJar {

    private val map = LinkedHashMap<String, okhttp3.Cookie>()

    @Synchronized
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        cookies.forEach { c -> map[c.name] = c }
    }

    @Synchronized
    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = map.values.toList()

    @Synchronized
    fun get(name: String): String? = map[name]?.value

    /** 全量快照（名 → 值）。**调用方不得把它打日志。** */
    @Synchronized
    fun all(): Map<String, String> = map.mapValues { it.value.value }

    @Synchronized
    fun getWithExpiry(name: String): Pair<String, Long>? =
        map[name]?.let { it.value to it.expiresAt }

    /** 存入或覆盖一条。`expiresAt=0` 表示会话级（不设过期）。 */
    @Synchronized
    fun put(name: String, value: String, expiresAt: Long = 0L) {
        val builder = okhttp3.Cookie.Builder()
            .domain("bilibili.com")
            .name(name)
            .value(value)
            .path("/")
        if (expiresAt > 0) builder.expiresAt(expiresAt)
        runCatching { builder.build() }
            .onSuccess { map[name] = it }
            .onFailure { AppLog.w("AuthBus", "存 cookie 失败 name=$name（值不回显）") }
    }

    /** 拼成 `k=v; k=v` 请求头。**调用方负责不要把它打日志。** */
    @Synchronized
    fun header(): String = map.values.joinToString("; ") { "${it.name}=${it.value}" }

    /** 迁移用：把已有的会话 cookie（SESSDATA 等）灌进来。 */
    @Synchronized
    fun seed(entries: Map<String, String>) {
        entries.forEach { (k, v) -> if (v.isNotBlank()) put(k, v) }
    }

    /**
     * 按名摘掉一条。
     *
     * 给**登出**用 —— 登出只能摘 `SESSDATA` / `bili_jct` / `DedeUserID`，
     * **不能整个清空**：`buvid3` / `bili_ticket` 是设备指纹，
     * 登出时把它们清掉，下次请求就是"一台新设备"，白白招风控。
     *
     * 2026-09-29 加：不加这个函数，登出就只能清磁盘（`CredentialStore.clear()`），
     * 内存里那份 SESSDATA 还在继续发出去 —— 界面说退出了、请求还是登录态。
     */
    @Synchronized
    fun remove(name: String) {
        map.remove(name)
    }
}
