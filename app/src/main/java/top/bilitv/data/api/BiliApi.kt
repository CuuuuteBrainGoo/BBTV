package top.bilitv.data.api

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import top.bilitv.data.auth.AuthBus
import top.bilitv.data.auth.CredentialStore
import top.bilitv.data.auth.TvLogin
import top.bilitv.data.auth.TvLoginPoll
import top.bilitv.data.auth.TvQrSession
import top.bilitv.data.auth.WebLogin
import top.bilitv.data.auth.WebQrSession
import top.bilitv.data.auth.Wbi
import top.bilitv.data.auth.parseSsoCookies
import top.bilitv.data.danmaku.DanmakuItem
import top.bilitv.data.danmaku.parseDanmakuSeg
import top.bilitv.data.model.CODE_REQUEST_FAILED
import top.bilitv.data.model.DynamicFeed
import top.bilitv.data.model.FavFolder
import top.bilitv.data.model.FeedItem
import top.bilitv.data.model.LiveArea
import top.bilitv.data.model.LivePlayInfo
import top.bilitv.data.model.LiveRoom
import top.bilitv.data.model.MyProfileResult
import top.bilitv.data.model.MyStat
import top.bilitv.data.model.PgcDetail
import top.bilitv.data.model.PgcSeason
import top.bilitv.data.model.PgcType
import top.bilitv.data.model.PlayInfo
import top.bilitv.data.model.UpVideoPage
import top.bilitv.data.model.VideoDetail
import top.bilitv.data.model.VideoRelation
import top.bilitv.data.model.parseVideoRelation
import top.bilitv.data.model.requireActionResponse
import top.bilitv.data.model.WeeklyIssue
import top.bilitv.util.AppLog
import org.json.JSONObject

/** Opt-in list requests must distinguish a valid empty page from an API failure. */
internal fun requireFeedSuccess(raw: String): String {
    val root = JSONObject(raw)
    val code = root.optInt("code", Int.MIN_VALUE)
    if (code != 0) throw java.io.IOException(if (code == Int.MIN_VALUE) "接口响应格式异常" else "接口返回错误（$code）")
    if (listOf(root.opt("data"), root.opt("result")).none { it is JSONObject || it is org.json.JSONArray })
        throw java.io.IOException("接口缺少列表数据")
    return raw
}

/**
 * 造一条 B 站 Cookie。
 *
 * ## ⛔ `.bilibili.com` 这种写法会让 App **一启动就崩**（2026-09-29 模拟器实测）
 *
 * ```
 * java.lang.IllegalArgumentException: unexpected domain: .bilibili.com
 *     at okhttp3.Cookie$Builder.domain(Cookie.kt:297)
 *     at top.bilitv.data.api.BiliApi.buildCookie(BiliApi.kt:56)
 *     at top.bilitv.data.api.BiliApi.<init>(BiliApi.kt:84)
 * ```
 *
 * OkHttp 的 `Cookie.Builder.domain()` **不接受前导点** —— 它要的是一个主机名，
 * 不是 RFC 那种 `Domain=.example.com` 的写法。而 `BiliApi` 的 `init` 会拿
 * 存下来的登录态去造 Cookie，所以只要本机**存过任何一条** Cookie
 * （比如 `ensureBuvid` 落盘的 `buvid3`），构造 `BiliApi` 就抛异常。
 * 它挂在 `BiliTvApp.api` 的 `by lazy` 上，`HomeViewModel` 第一次取就触发 ——
 * 表现是**首页白屏然后闪退**，而且和"网络/接口"毫无关系的报错位置，极难联想的到。
 *
 * ## 为什么写成 `.domain("bilibili.com")` 也够用
 *
 * 我们自己的 `CookieJar.loadForRequest` 是**无条件返回全部 Cookie** 的
 * （不按 URL 过滤），所以 domain 只影响 OkHttp 的构造校验。
 * 而 `bilibili.com` 这种"无前导点"的域在 OkHttp 里同时匹配
 * `bilibili.com` 和 `*.bilibili.com` —— 点播站和直播站都覆盖到了。
 *
 * ## 为什么用 runCatching 兜一层
 *
 * 这是个**恢复登录态**的路径，跑在 App 启动链路上。随便一个字段形态变化
 * 都不该让整个应用起不来 —— 大不了这次不带 Cookie（退化成游客态）。
 * 但要**记一条 error 日志**，不能静默吞掉，否则下次出问题又找不到线索。
 */
internal fun buildBiliCookie(name: String, value: String?): Cookie? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        Cookie.Builder()
            .domain("bilibili.com")
            .name(name)
            .value(value)
            .build()
    }.onFailure {
        AppLog.e("Api", "造 Cookie 失败 name=$name（值不回显）：${it.javaClass.simpleName}")
    }.getOrNull()
}

/**
 * 登录凭据 cookie 的名字。
 *
 * ⚠️ 登出时**只摘这几个**，不要顺手把整个 cookie jar 清空 ——
 * `buvid3` / `bili_ticket` 那类是**设备指纹**，清掉等于宣告"我换设备了"，
 * 是要招风控的。
 */
private val CREDENTIAL_COOKIE_NAMES = listOf("SESSDATA", "bili_jct", "DedeUserID")

/**
 * B 站接口访问入口。
 *
 * 设计约束：
 *  - 所有请求统一走这里，接口变更只改一处
 *  - WBI 密钥缓存 12 小时（官方每日轮换）
 *  - Cookie 只从 CredentialStore 读写，不落日志
 */
class BiliApi(context: Context) {

    private val store = CredentialStore(context)
    private val settings = top.bilitv.data.settings.SettingsStore(context)

    /**
     * Cookie 缓存。**必须是字段**：见 [ensureBuvid] —— 它会在运行期往里补一条。
     * 之前这里是 `object : CookieJar` 的内部 `val`，只有构造时那一次写入，
     * 补不进去。
     */
    // 多个并行请求会同时收发 Cookie，遍历时不能使用普通可变 Map。
    private val cookieCache = java.util.concurrent.ConcurrentHashMap<String, Cookie>()

    private fun buildCookie(name: String, value: String?): Cookie? = buildBiliCookie(name, value)

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { c ->
                cookieCache[c.name] = c
                // ★ 同步进 AuthBus —— 风控层维护出来的 cookie（buvid3/ticket）
                //   和登录拿到的 cookie 都从这里走，两边共享同一份。
                AuthBus.cookies.put(c.name, c.value, c.expiresAt)
                when (c.name) {
                    "SESSDATA" -> store.sessdata = c.value
                    "bili_jct" -> store.biliJct = c.value
                    "DedeUserID" -> store.dedeUserId = c.value
                    "buvid3" -> store.buvid = c.value
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieCache.values.toList()
    }

    /**
     * 把 [AuthBus] 里新出现的 cookie 拉回本地缓存。
     *
     * ## 为什么需要这个"反向同步"
     *
     * `AuthBus` 是风控层（[top.bilitv.data.auth.WebCookieMaintainer]）用的总线，
     * 它在后台补了 `buvid4` / `b_nut` / `bili_ticket` / 同步来的 SSO cookie ——
     * 这些**不是**通过 `BiliApi` 的 cookie jar 收的，所以本地 [cookieCache] 不知道。
     *
     * 不拉回来的话，风控层白干：补齐了 cookie，但业务请求不带，等于没有。
     *
     * **在每次 `get`/`getRaw` 之前调一次**，代价是遍历一个 ~10 元素的 map，
     * 可以忽略不计。
     */
    private fun syncCookiesFromBus() {
        AuthBus.cookies.all().forEach { (name, value) ->
            if (cookieCache[name]?.value != value) {
                buildCookie(name, value)?.let { cookieCache[name] = it }
            }
        }
    }

    init {
        applyStoredCredentials()
    }

    /**
     * 把 [CredentialStore] 里的凭据推进**活的** cookie 缓存。
     *
     * ## 为什么必须有这个函数（2026-09-29 电视真机实测踩坑）
     *
     * 这里原来只有 `init { ... }` 那一段 —— **只在构造时跑一次**。
     * 于是扫码登录出现了这个现象（日志实拍）：
     *
     * ```
     * I/Api:  扫码登录成功（凭证已落盘，不回显内容）
     * W/Mine: 本机有凭证，但服务端说没登录（凭证过期？）    ← 0.5 秒后
     * ```
     *
     * 扫码明明成功了，界面上却一直停在「登录已过期」，
     * **把 App 杀掉重开就好了** —— 因为重开时 `init` 才终于把新凭据读进去。
     *
     * **根因**：[tvPoll] 把扫来的 cookie 写进了 `store`（磁盘），
     * 但 `store` **不是**请求真正用的那份 cookie —— 请求走的是 [cookieCache]
     * 和 [AuthBus] 这两个**内存**缓存。它们没被通知，于是请求继续带着
     * 旧的（空的）cookie 出去，服务端当然说没登录。
     *
     * ## 为什么抽成函数而不是在登录处再抄一遍
     *
     * `buvid` 已经踩过**同一个坑**（见 [cookieCache] 的注释："只有构造时那一次写入，
     * 补不进去"）。当时是给 [ensureBuvid] 单独加了一条补写路径 ——
     * 结果登录这条路又漏了。根因不是"少写一行"，而是**"读凭据"这件事散在多处**。
     * 抽成函数之后，"启动时读"和"登录成功后立刻读"走同一条路，
     * 以后再有新的凭据来源，改这一个函数就够了。
     */
    private fun applyStoredCredentials() {
        listOf(
            "SESSDATA" to store.sessdata,
            "bili_jct" to store.biliJct,
            "DedeUserID" to store.dedeUserId,
            "buvid3" to store.buvid,
        ).forEach { (name, value) ->
            if (value.isNullOrBlank()) return@forEach
            buildCookie(name, value)?.let { c ->
                cookieCache[name] = c
                // 两个缓存都要写：cookieCache 是本 client 用的，
                // AuthBus 是风控层（WebCookieMaintainer）共享的。
                // 只写一边，另一边的请求路径就看不到新凭据。
                AuthBus.cookies.put(name, value, c.expiresAt)
            }
        }
    }

    /**
     * 登出时把**凭据类** cookie 从两个缓存里摘掉。
     *
     * 只摘 [CREDENTIAL_COOKIE_NAMES] 这三个 —— `buvid3` 之类是**设备指纹**，
     * 登出时把它清掉反而像"换了一台设备"，白白招风控。
     *
     * 不做这一步的话是**反向的同款 bug**：`store` 清空了（界面显示"还没登录"），
     * 但 [cookieCache] 里那份 SESSDATA 还在，请求继续以**已登录身份**发出去 ——
     * 界面说退出了、实际没退出。
     */
    private fun dropCredentialCookies() {
        CREDENTIAL_COOKIE_NAMES.forEach { name ->
            cookieCache.remove(name)
            AuthBus.cookies.remove(name)
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val original = chain.request()
            /*
             * ★ 直播站的 Referer / Origin **必须换掉**。
             *
             * 实测（`tools/probe_live.py` 的对照实验）：把直播接口的 Referer
             * 写成点播站的 `https://www.bilibili.com/`，风控挑不出来 —— 但反过来
             * 也不成立：用 `https://live.bilibili.com/` 打点播接口一样正常。
             * 所以按**目标域名**分流，是最省心的做法：
             * 只有 `*.live.bilibili.com` 用直播站的来路，其余保持原样。
             *
             * 为什么值得为这个加一段判断：Referer 是 B 站风控的输入之一，
             * 跨站打接口属于"看起来像脚本"的行为。这不是猜测 —— CDN 那边
             * 对 Referer 是**硬校验**（缺了直接 403，见 `BiliPlayer`）。
             */
            val isLive = original.url.host.endsWith("live.bilibili.com") || original.url.host.endsWith(".chat.bilibili.com")
            val req = original.newBuilder()
                .header("User-Agent", original.header("User-Agent") ?: UA)
                .header("Referer", if (isLive) "https://live.bilibili.com/" else "https://www.bilibili.com/")
                .header("Origin", if (isLive) "https://live.bilibili.com" else "https://www.bilibili.com")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            chain.proceed(req)
        }
        .build()

    private val guestClient by lazy { client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build() }
    private val appVideo by lazy { AppGrpcVideo(client) }
    private var imgKey: String? = null
    private var subKey: String? = null
    private var keyTs: Long = 0L

    /** WBI 密钥，缓存 12 小时 */
    suspend fun wbiKeys(): Pair<String, String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val i = imgKey
        val s = subKey
        if (i != null && s != null && now - keyTs < KEY_TTL) return@withContext i to s

        val nav = getRaw(NAV)
        val data = JSONObject(nav).optJSONObject("data") ?: error("nav 返回异常")
        val wbi = data.optJSONObject("wbi_img") ?: error("nav 缺少 wbi_img（可能已被风控）")
        val ni = Wbi.keyFromUrl(wbi.optString("img_url"))
        val ns = Wbi.keyFromUrl(wbi.optString("sub_url"))
        imgKey = ni
        subKey = ns
        keyTs = now
        ni to ns
    }

    /** 带 WBI 签名的 GET，返回原始 JSON 文本 */
    suspend fun get(path: String, params: Map<String, String> = emptyMap()): String {
        syncCookiesFromBus()
        ensureBuvid()
        val (i, s) = wbiKeys()
        val signed = Wbi.sign(params, i, s)
        return getRaw(path, signed)
    }

    /**
     * 确保本机有一个 `buvid3`。
     *
     * ## 为什么必须有这一步（`docs/15` §4.2）
     *
     * `/x/frontend/finger/spi` 把 `buvid3` 放在**响应体的 `data.b_3`** 里，
     * **不是 `Set-Cookie`** —— 所以 cookie jar 永远收不到它，得自己去拿。
     * 而少了它，一批接口直接 `-352 风控校验失败`（UP 主投稿列表就是典型）。
     *
     * 只做一次：拿到了就落盘，之后每次启动直接从 [CredentialStore] 读回。
     * 失败**不报错也不重试** —— 游客态常规接口（首页/热门/影视）本来就不需要它，
     * 为它在冷启动路径上加重试只会拖慢首屏。
     */
    private suspend fun ensureBuvid() {
        if (!store.buvid.isNullOrBlank()) return
        runCatching {
            val b3 = JSONObject(getRaw(PATH_FINGER_SPI))
                .optJSONObject("data")?.optString("b_3").orEmpty()
            if (b3.isNotBlank()) {
                store.buvid = b3
                buildCookie("buvid3", b3)?.let { cookieCache["buvid3"] = it }
                AppLog.i(TAG, "已取得 buvid3（不回显内容）")
            } else {
                AppLog.w(TAG, "finger/spi 没给 b_3，跳过")
            }
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.w(TAG, "取 buvid3 失败：${it.javaClass.simpleName}")
        }
    }

    suspend fun getRaw(path: String, params: Map<String, String> = emptyMap()): String =
        String(getRawBytes(path, params), Charsets.UTF_8)

    /**
     * 二进制 GET（弹幕 protobuf 走这里，返回体不是文本）。
     *
     * ## 为什么这里必须打日志
     *
     * 上面每个业务方法都是 `runCatching { ... }.getOrNull()` —— 为了界面不崩，异常被吞掉。
     * 好处是稳，坏处是**出错时外面什么线索都没有**：
     * 首页只会显示一句「接口可能变了或网络不通」，而这两种情况的修法完全相反。
     *
     * 所以把日志打进**传输层这一个点**，所有接口的失败都有据可查，
     * 而不用去改十几个业务方法的签名。
     *
     * @param base 目标站点。默认点播站；直播传 [LIVE_BASE]（那是**另一个域名**）。
     */
    suspend fun getRawBytes(
        path: String,
        params: Map<String, String> = emptyMap(),
        base: String = BASE,
        maxBytes: Int = Int.MAX_VALUE,
        anonymous: Boolean = false,
        userAgent: String? = null,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            if (!anonymous) syncCookiesFromBus()
            val url = "$base$path".toHttpUrl().newBuilder().apply {
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()

            val call = (if (anonymous) guestClient else client).newCall(Request.Builder().url(url).get()
                .apply { userAgent?.let { header("User-Agent", it) } }.build())
            try { call.readCancellable { resp ->
                val body = if (maxBytes == Int.MAX_VALUE) resp.body?.bytes() ?: ByteArray(0) else {
                    require(maxBytes > 0)
                    val source = resp.body?.source()
                    val buffer = okio.Buffer()
                    while (source != null && buffer.size <= maxBytes &&
                        source.read(buffer, minOf(8_192L, maxBytes + 1L - buffer.size)) != -1L) { }
                    require(buffer.size <= maxBytes) { "响应超过大小限制 @ $path" }
                    buffer.readByteArray()
                }
                if (!resp.isSuccessful) {
                    // 连上了但状态码不对，把服务端的原话记下来
                    AppLog.e(TAG, "HTTP ${resp.code} @ $path | ${body.decodeToString().take(200)}")
                    error("HTTP ${resp.code} @ $path")
                }
                body
            } } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AppLog.e(TAG, "请求异常 @ $path", e)
                throw e
            }
        }

    // ---------------------------------------------------------------- 业务接口

    /**
     * 表单 POST，**指定完整域名**。
     *
     * 为 passport 那一系列单开一个方法，而不是把 GET 改成能带 body：
     * 两者的差别不只是"有没有 body"——passport 的签名是 appkey/appsec，
     * 和 WBI 那套毫无关系，路径也不在 `api.bilibili.com`。
     * 硬塞进一个方法里，早晚会有人把 WBI 签名套到登录接口上。
     */
    private suspend fun qrPostForm(base: String, path: String, params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val form = okhttp3.FormBody.Builder().apply {
                params.forEach { (k, v) -> add(k, v) }
            }.build()

            client.newCall(Request.Builder().url(base + path).post(form).build()).readCancellable { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code} @ POST $path")
                }
                body
            }
        }

    /** Supported UGC App details share playback's setting; PGC details remain Web. */
    suspend fun videoDetail(bvid: String): VideoDetail? = try {
        detailWithFallback(settings.videoApiSource, bvid) { source ->
            val result = if (source == top.bilitv.data.settings.VideoApiSource.APP) {
                requireAppCredentialOrGuest()
                appVideo.detail(bvid, store.accessKey, store.buvid)
            } else {
                val raw = getRaw(PATH_VIEW, mapOf("bvid" to bvid))
                withContext(Dispatchers.Default) { parseVideoDetail(raw) }
            }
            if (result != null) AppLog.i(TAG, "实际详情来源=${source.name}")
            result
        }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { AppLog.w(TAG, "视频详情暂不可用：${e.javaClass.simpleName}"); null }

    fun canWriteVideoActions(): Boolean = !store.sessdata.isNullOrBlank() && !store.biliJct.isNullOrBlank()

    suspend fun videoRelation(aid: Long): VideoRelation {
        require(aid > 0)
        check(canWriteVideoActions()) { "请先扫码登录后再操作" }
        return parseVideoRelation(getRaw("/x/web-interface/archive/relation", mapOf("aid" to aid.toString())))
    }

    // 消费账号状态的 POST 禁止网络层自动重试，避免一次按键产生重复扣币。
    private val actionClient by lazy { client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).build() }
    private suspend fun videoAction(path: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        check(canWriteVideoActions()) { "请先扫码登录后再操作" }
        syncCookiesFromBus()
        val csrf = AuthBus.cookies.get("bili_jct")?.takeIf { it.isNotBlank() } ?: store.biliJct!!
        val form = okhttp3.FormBody.Builder().apply {
            params.forEach { (key, value) -> add(key, value) }
            add("csrf", csrf)
        }.build()
        actionClient.newCall(Request.Builder().url("https://api.bilibili.com$path").post(form).build()).execute().use {
            check(it.isSuccessful) { "网络响应 ${it.code}；请刷新状态后再试" }
            requireActionResponse(it.body?.string().orEmpty())
        }
    }

    suspend fun setVideoLike(aid: Long, liked: Boolean) {
        require(aid > 0)
        videoAction("/x/web-interface/archive/like", mapOf("aid" to "$aid", "like" to if (liked) "1" else "2"))
    }

    suspend fun upProfile(mid: Long): top.bilitv.data.model.UpProfile {
        require(mid > 0)
        val data = requireActionResponse(getRaw("/x/web-interface/card", mapOf("mid" to "$mid"))).getJSONObject("data")
        val card = data.getJSONObject("card")
        val relation = if (isLoggedIn()) runCatching {
            requireActionResponse(getRaw("/x/relation", mapOf("fid" to "$mid"))).getJSONObject("data").getInt("attribute")
        }.getOrNull() else null
        return top.bilitv.data.model.UpProfile(mid, card.optString("name"), card.optString("face"), card.optString("sign"),
            card.optJSONObject("level_info")?.takeIf { it.has("current_level") }?.getInt("current_level"),
            data.takeIf { it.has("follower") }?.getLong("follower"), relation)
    }

    suspend fun changeUpRelation(mid: Long, action: Int) {
        require(mid > 0 && action in listOf(1, 2, 5, 6))
        videoAction("/x/relation/modify", mapOf("fid" to "$mid", "act" to "$action", "re_src" to "11"))
    }

    suspend fun addVideoCoins(aid: Long, count: Int) {
        require(aid > 0 && count in 1..2)
        videoAction("/x/web-interface/coin/add", mapOf("aid" to "$aid", "multiply" to "$count", "select_like" to "0"))
    }

    suspend fun tripleVideo(aid: Long): JSONObject {
        require(aid > 0)
        return videoAction("/x/web-interface/archive/like/triple", mapOf("aid" to "$aid"))
            .getJSONObject("data")
    }

    suspend fun videoFavoriteFolders(aid: Long): List<FavFolder> {
        require(aid > 0)
        check(canWriteVideoActions()) { "请先扫码登录后再操作" }
        val mid = store.dedeUserId?.toLongOrNull()?.takeIf { it > 0 } ?: error("登录资料缺失，请重新扫码登录")
        val body = getRaw(PATH_FAV_FOLDERS, mapOf("up_mid" to "$mid", "rid" to "$aid", "type" to "2"))
        val rows = requireActionResponse(body).getJSONObject("data").getJSONArray("list")
        return parseFavFolders(body).also { folders ->
            check(folders.size == rows.length() && folders.all { it.favored != null }) { "收藏状态缺失，请刷新后再试" }
        }
    }

    suspend fun setVideoFavorites(aid: Long, add: Set<Long>, remove: Set<Long>) {
        require(aid > 0 && (add + remove).all { it > 0 } && add.intersect(remove).isEmpty())
        if (add.isEmpty() && remove.isEmpty()) return
        videoAction("/x/v3/fav/resource/deal", mapOf("rid" to "$aid", "type" to "2",
            "add_media_ids" to add.joinToString(","), "del_media_ids" to remove.joinToString(","), "platform" to "web"))
    }

    /**
     * 播放地址（UGC：普通投稿视频）。
     *
     * ## 参数里的两个"风控规避"字段（2026-09-29 逆向 MyTVB v2.0.6 得出）
     *
     * MyTVB 的取流串是：
     * ```
     * /x/player/wbi/playurl?voice_balance=1&gaia_source=pre-load&isGaiaAvoided=true
     * ```
     *
     * - **`gaia_source=pre-load`**：声明"我是**预加载**场景发起的请求"。
     *   `gaia` 是 B 站的雅典娜风控系统，预加载属于正常播放器行为，
     *   比"用户点了才请求"更容易被判定为合法。
     * - **`isGaiaAvoided=true`**：声明"客户端已自行规避风控"。
     *   这是客户端与风控网关之间的约定 —— 说"我知道规矩，我照规矩做"。
     *
     * 这两个参数**成本为零**（就是两个 URL 参数），但 MyTVB / chinasoul.bt 都带。
     * 不带的时候：游客态通常也能拿到流，但**登录态下更容易被降级**。
     *
     * `fnval=4048` 要 DASH 音画分离；`fourk=1` 放开 4K。
     * 实测游客态即可返回，清晰度上限 480P；高画质依赖登录。
     */
    suspend fun playInfo(bvid: String, cid: Long, aid: Long = 0L,
        source: top.bilitv.data.settings.VideoApiSource = settings.videoApiSource,
        accepts: (PlayInfo) -> Boolean = { true }): PlayInfo? = playbackRequest(source, accepts) { source ->
        if (source == top.bilitv.data.settings.VideoApiSource.APP) {
            requireAppCredentialOrGuest()
            appVideo.play(aid, cid, bvid, 0, settings.preferHevc && !settings.forceAvc,
                store.accessKey, store.buvid)
        } else parsePlayInfo(get(PATH_PLAYURL, playUrlParams(bvid = bvid, cid = cid)))
    }

    private fun requireAppCredentialOrGuest() {
        // A Web QR session is not an App access_key. Preserve its rights through Web instead of guest downgrading.
        if (!store.sessdata.isNullOrBlank() && store.accessKey.isNullOrBlank())
            throw java.io.IOException("当前登录未提供 App 凭据")
    }

    private suspend fun playbackRequest(source: top.bilitv.data.settings.VideoApiSource, accepts: (PlayInfo) -> Boolean,
        request: suspend (top.bilitv.data.settings.VideoApiSource) -> PlayInfo?): PlayInfo? = try {
        playbackWithFallback(source, accepts) { source ->
            try { request(source)?.let(top.bilitv.data.model.PlaybackPreview::requireBound) } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.w(TAG, "${source.name} 取流失败：${e.javaClass.simpleName}")
                throw e
            }
        }.also { info ->
            if (info != null) AppLog.i(TAG, "实际取流来源=${info.source.name} 试看=${info.isPreview}")
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLog.e(TAG, "播放接口失败", e); null
    }

    /**
     * 取流公共参数。**UGC 和 PGC 共用**，避免两处写重复的"风控字段"而漏改一边。
     */
    private fun playUrlParams(
        bvid: String? = null,
        epId: Long? = null,
        cid: Long,
    ): Map<String, String> = buildMap {
        bvid?.let { put("bvid", it) }
        epId?.let { put("ep_id", it.toString()) }
        put("cid", cid.toString())
        put("fnval", "4048")
        put("fnver", "0")
        put("fourk", "1")
        // ★ 风控规避（见 playInfo 的注释）
        put("gaia_source", "pre-load")
        put("isGaiaAvoided", "true")
    }

    /**
     * 弹幕分段。
     *
     * **必须走 protobuf 的 `seg.so`**：旧 XML 接口（`comment.bilibili.com/{cid}.xml`）
     * 实测返回 HTTP 200 但一条都解析不出来（见 `docs/06` §2.3）。
     * 该接口不需要 WBI 签名，游客态即可用。
     *
     * @param index 分段号，从 1 开始（6 分钟一段）
     */
    suspend fun danmakuSegment(cid: Long, index: Int): List<DanmakuItem> =
        withContext(Dispatchers.Default) { runCatching {
            parseDanmakuSeg(
                getRawBytes(
                    PATH_DM_SEG,
                    mapOf("type" to "1", "oid" to cid.toString(), "segment_index" to index.toString()),
                )
            )
        }.getOrDefault(emptyList()) }

    suspend fun subtitleTracks(bvid: String, cid: Long, aid: Long = 0L): List<top.bilitv.data.model.SubtitleTrack> {
        require(cid > 0 && (bvid.isNotBlank() || aid > 0)) { "字幕缺少视频编号" }
        val params = mapOf("cid" to "$cid") + if (bvid.isNotBlank()) mapOf("bvid" to bvid) else mapOf("aid" to "$aid")
        val raw = try { get("/x/player/wbi/v2", params).also { top.bilitv.data.model.parseSubtitleTracks(it) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AppLog.w("Subtitle", "WBI 字幕信息失败，回退 v2：${e.javaClass.simpleName}")
                getRaw("/x/player/v2", params)
            }
        val tracks = top.bilitv.data.model.parseSubtitleTracks(raw)
        check(tracks.isNotEmpty() || JSONObject(raw).optJSONObject("data")?.optBoolean("need_login_subtitle") != true) {
            "字幕需要网页登录，请在登录页面取得账号 Cookie"
        }
        return tracks
    }

    /** Only paid/charging content needs this extra read. A generic purchase toast is not an entitlement flag. */
    suspend fun previewState(bvid: String, cid: Long): Boolean? = try {
        val params = mapOf("bvid" to bvid, "cid" to "$cid")
        val raw = try { get("/x/player/wbi/v2", params).also { top.bilitv.data.model.explicitPreviewState(it) } }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                getRaw("/x/player/v2", params)
            }
        top.bilitv.data.model.explicitPreviewState(raw)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLog.w(TAG, "试看元数据暂不可用：${e.javaClass.simpleName}"); null
    }

    private val subtitleClient by lazy { client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build() }

    suspend fun subtitleBody(track: top.bilitv.data.model.SubtitleTrack): top.bilitv.data.model.SubtitleTimeline =
        withContext(Dispatchers.IO) {
            val url = top.bilitv.data.model.subtitleUrl(track.url) ?: error("字幕地址无效")
            val bytes = subtitleClient.newCall(Request.Builder().url(url).build()).readCancellable { response ->
                if (!response.isSuccessful) error("字幕下载失败（HTTP ${response.code}）")
                val source = response.body?.source() ?: error("字幕正文为空")
                val buffer = okio.Buffer()
                val limit = 4L * 1024 * 1024
                while (buffer.size <= limit && source.read(buffer, minOf(8_192, limit + 1 - buffer.size)) != -1L) { }
                require(buffer.size <= limit) { "字幕正文超过 4 MiB" }
                buffer.readByteArray()
            }
            withContext(Dispatchers.Default) { top.bilitv.data.model.SubtitleTimeline.parse(bytes.toString(Charsets.UTF_8)) }
        }

    suspend fun danmakuMetadata(cid: Long, aid: Long): top.bilitv.data.danmaku.DanmakuCloudProfile {
        val params = mapOf("type" to "1", "oid" to "$cid") + if (aid > 0L) mapOf("pid" to "$aid") else emptyMap()
        val metadata = getRawBytes("/x/v2/dm/web/view", params, maxBytes = 1_048_576)
        return withContext(Dispatchers.Default) { top.bilitv.data.danmaku.parseDanmakuCloudProfile(metadata) }
    }

    suspend fun danmakuCloud(profile: top.bilitv.data.danmaku.DanmakuCloudProfile): top.bilitv.data.danmaku.DanmakuRules {
        check(!store.sessdata.isNullOrBlank()) { "未取得账号 Cookie，请重新扫码登录" }
        val body = getRaw("/x/dm/filter/user")
        return withContext(Dispatchers.Default) { top.bilitv.data.danmaku.parseDanmakuCloudRules(body, profile.reportWords) }
    }

    /**
     * 首页推荐（注意路径带 `wbi` 前缀，实测须签名）。
     *
     * 这里**故意不用 `runCatching` 一句话吞掉**：这一条是首页的全部数据来源，
     * 失败了界面只会显示「接口可能变了或网络不通」——两个方向完全相反却看不出区别。
     * 所以失败记录异常、返回空记录响应原文，让日志里能直接看到服务端说了什么。
     */
    suspend fun feedRecommend(freshIdx: Int = 1, strict: Boolean = false): List<FeedItem> = try {
        recommendPage(freshIdx, top.bilitv.data.settings.RecommendSource.WEB, true).items
    } catch (e: Exception) {
        if (e is CancellationException || strict) throw e
        AppLog.e(TAG, "推荐流失败", e); emptyList()
    }

    suspend fun recommendPage(freshIdx: Int, source: top.bilitv.data.settings.RecommendSource,
        personalized: Boolean): RecommendPage = recommendWithFallback(source) { requested ->
        val index = freshIdx.coerceAtLeast(1).toString()
        val raw = if (requested == top.bilitv.data.settings.RecommendSource.APP) {
            val params = mutableMapOf("idx" to index, "mobi_app" to "android_hd", "build" to "2020100",
                "ts" to (System.currentTimeMillis() / 1000).toString())
            if (personalized) store.accessKey?.takeIf { it.isNotBlank() }?.let { params["access_key"] = it }
            val signed = top.bilitv.data.auth.AppSign.sign(params, "dfca71928277209b", "b5475a8825547a4fc26c7d518eaaa02e")
            String(getRawBytes("/x/v2/feed/index", signed, "https://app.bilibili.com", anonymous = true,
                userAgent = "Mozilla/5.0 BiliDroid/2.2.0 os/android mobi_app/android_hd build/2020100"), Charsets.UTF_8)
        } else {
            val params = mapOf("ps" to "12", "fresh_type" to "3", "fresh_idx" to index)
            if (personalized) try {
                val body = get(PATH_FEED, params)
                withContext(Dispatchers.Default) { requireFeedSuccess(body) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.w(TAG, "网页推荐切换备用线路：${e.javaClass.simpleName}")
                getRaw("/x/web-interface/index/top/feed/rcmd", params + mapOf("feed_version" to "V1", "plat" to "1"))
            } else String(getRawBytes("/x/web-interface/index/top/feed/rcmd",
                params + mapOf("feed_version" to "V1", "plat" to "1"), anonymous = true), Charsets.UTF_8)
        }
        val list = withContext(Dispatchers.Default) {
            requireFeedSuccess(raw)
            val data = JSONObject(raw).optJSONObject("data") ?: throw java.io.IOException("推荐数据格式异常")
            if (requested == top.bilitv.data.settings.RecommendSource.APP) {
                if (data.optJSONArray("items") == null) throw java.io.IOException("App 推荐缺少卡片列表")
                parseAppFeedRecommend(raw)
            } else {
                if (data.optJSONArray("item") == null && data.optJSONArray("items") == null)
                    throw java.io.IOException("网页推荐缺少卡片列表")
                parseFeedRecommend(raw)
            }
        }
        AppLog.i(TAG, "推荐来源=${requested.name} 个性化=$personalized 条数=${list.size}")
        list
    }

    /**
     * 热门 `/x/web-interface/popular`。
     *
     * 游客态可用、**不用签名**（2026-09-29 探针实测：20 条，223ms）。
     */
    suspend fun popular(pn: Int = 1, ps: Int = 20, strict: Boolean = false): List<FeedItem> =
        feedOrLog("热门", PATH_POPULAR, mapOf("pn" to pn.toString(), "ps" to ps.toString()), strict) {
            parsePopular(it)
        }

    /**
     * 分区最新投稿 `/x/web-interface/newlist`。
     *
     * 替代已下线的 `dynamic/region`（那个现在所有分区都 -404）。
     * 分区编号见 `docs/15`：影视相关是 11(电视剧) 13(番剧) 23(电影) 177(纪录片)。
     */
    suspend fun regionNewList(rid: Int, pn: Int = 1, ps: Int = 20, strict: Boolean = false): List<FeedItem> =
        feedOrLog("分区$rid", PATH_NEWLIST, mapOf(
            "rid" to rid.toString(),
            "pn" to pn.toString(),
            "ps" to ps.toString(),
        ), strict) { parseRegionNewList(it) }

    /**
     * 「每周必看」当期视频 `/x/web-interface/popular/series/one`。
     *
     * @param number 期号。传 0 表示不指定 —— 接口会当作当期。
     *   不要自己去算"当前是第几期"，那个算法会随年份/周数错位。
     */
    suspend fun weeklyOne(number: Int = 0, strict: Boolean = false): List<FeedItem> {
        val params = if (number > 0) mapOf("number" to number.toString()) else emptyMap()
        return feedOrLog("每周必看#$number", PATH_WEEKLY_ONE, params, strict) { parseWeeklyOne(it) }
    }

    /** 「每周必看」期号列表 `/x/web-interface/popular/series/list`（不用签名） */
    suspend fun weeklyIssues(): List<WeeklyIssue> = try {
        withContext(Dispatchers.Default) { parseWeeklySeries(getRaw(PATH_WEEKLY_LIST)) }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        AppLog.e(TAG, "每周必看期号列表失败", t)
        emptyList()
    }

    /**
     * PGC 分类索引 `/pgc/season/index/result`。
     *
     * ⚠️ **必须带 `type=1`**，少这一个参数一律 `code=-400 请求错误`
     * —— 这个参数不在任何文档里，是 2026-09-29 试出来的。
     * 其余那一长串 `-1` 是"不限"的意思（地区/年份/风格/是否完结……）。
     *
     * `order=3` = 按追番人数排序（热度），`st=1` = 全部状态。
     */
    suspend fun pgcIndexPage(type: PgcType, page: Int = 1, ps: Int = 24, filters: Map<String,String> = emptyMap()): top.bilitv.data.model.PgcIndexPage {
        val raw = getRaw(PATH_PGC_INDEX, mapOf(
            "season_type" to type.id.toString(),
            "type" to "1",
            "page" to page.toString(),
            "pagesize" to ps.toString(),
            "order" to "2",
            "st" to "1",
            "sort" to "0",
            "area" to "-1",
            "year" to "-1",
            "style_id" to "-1",
            "season_version" to "-1",
            "spoken_language_type" to "-1",
            "is_finish" to "-1",
            "copyright" to "-1",
            "season_status" to "-1",
            "quarter" to "-1",
            "is_special" to "0",
        ) + filters)
        return withContext(Dispatchers.Default) { parsePgcIndexPage(raw, page, ps) }
    }

    suspend fun pgcFilters(type: PgcType): List<top.bilitv.data.model.PgcFilterField> =
        withContext(Dispatchers.Default) {
            parsePgcFilters(getRaw("/pgc/season/index/condition", mapOf("season_type" to "${type.id}", "type" to "1")))
        }

    suspend fun pgcRank(type: PgcType): List<PgcSeason> =
        feedOrLog("${type.name}热播榜", "/pgc/season/rank/web/list", mapOf("season_type" to "${type.id}", "day" to "3")) { parsePgcIndex(it) }

    suspend fun pgcBanner(type: PgcType): List<PgcSeason> {
        val path = when (type) {
            PgcType.MOVIE -> "/movie/"; PgcType.TV -> "/tv/"; PgcType.DOCUMENTARY -> "/documentary/"
            PgcType.VARIETY -> "/variety/"; PgcType.BANGUMI -> "/anime/"; PgcType.GUOCHUANG -> "/guochuang/"
            else -> return emptyList()
        }
        return withContext(Dispatchers.Default) { parsePgcBanner(String(getRawBytes(path, base = "https://www.bilibili.com"), Charsets.UTF_8), type.id) }
    }

    /**
     * 搜索视频 `/x/web-interface/wbi/search/type`。
     *
     * 游客态实测可用（Phase 0 清单第 5 项，20 条，未触发风控）。
     * 但仍按"可能被风控"来写：失败返回空，日志里留服务端原话。
     */
    suspend fun searchVideoPage(keyword: String, page: Int = 1): top.bilitv.data.model.SearchVideoPage {
        require(keyword.isNotBlank() && page > 0)
        val raw = get(PATH_SEARCH, mapOf(
            "keyword" to keyword,
            "search_type" to "video",
            "page" to page.toString(),
        ))
        return withContext(Dispatchers.Default) { parseSearchVideoPage(raw, page) }
    }

    /**
     * 热搜词 `/x/web-interface/search/square`。
     *
     * 失败**不算错误**：热搜只是"帮用户省掉打字"的辅助，没有它搜索照样能用，
     * 所以这里静默返回空列表，不记异常 —— 否则每次断网进搜索页都会刷一条 error 日志。
     */
    /** Read-only video comments, opaque WBI cursor; no automatic retry or account writes. */
    suspend fun comments(aid: Long, newest: Boolean = false, offset: String = ""): top.bilitv.data.model.CommentPage {
        require(aid > 0)
        return withContext(Dispatchers.Default) { top.bilitv.data.model.parseCommentPage(get("/x/v2/reply/wbi/main", mapOf(
            "type" to "1", "oid" to aid.toString(), "mode" to if (newest) "2" else "3",
            "pagination_str" to top.bilitv.data.model.commentPagination(offset),
            "plat" to "1", "web_location" to "1315875"))) }
    }
    suspend fun hotSearch(limit: Int = 12, strict: Boolean = false): List<String> = try {
        val raw = getRaw(PATH_HOT_SEARCH, mapOf("limit" to limit.toString()))
        withContext(Dispatchers.Default) {
            check(JSONObject(raw).optInt("code", -1) == 0) { "热搜暂时加载失败" }
            parseHotSearch(raw, limit)
        }
    } catch (e: Exception) {
        if (strict || e is CancellationException) throw e
        emptyList()
    }

    /**
     * PGC 作品详情 `/pgc/view/web/season`（含全部剧集）。
     *
     * ⚠️ **返回 null 不等于出错**。实测（2026-09-29）：未登录时该接口返回
     * `code=0` 但 `data=null` —— 也就是说"没登录"和"接口挂了"在这里**都是 null**，
     * 只能靠"当前有没有登录"来分辨该给用户哪句提示。见 `isLoggedIn()`。
     */
    suspend fun pgcDetail(seasonId: Long, epId: Long = 0L): PgcDetail? {
        if (seasonId <= 0L && epId <= 0L) return null
        return try {
            val params = if (seasonId > 0L) mapOf("season_id" to seasonId.toString())
                else mapOf("ep_id" to epId.toString())
            val raw = getRaw(PATH_PGC_SEASON, params)
            val d = withContext(Dispatchers.Default) { parsePgcDetail(raw) }
            if (d == null) AppLog.w(TAG, "PGC 详情为空 season_id=$seasonId（未登录时属于正常现象）")
            d
        } catch (e: CancellationException) { throw e }
        catch (t: Throwable) {
            AppLog.e(TAG, "PGC 详情失败 season_id=$seasonId", t)
            null
        }
    }

    /**
     * PGC 播放地址。**三条通道依次尝试**（照 chinasoul.bt 的做法）。
     *
     * ## 为什么不是"选一条最好的"，而是"依次试"
     *
     * 番剧/影视的取流对**账号等级、大会员状态、地区**都敏感，
     * 三条通道在不同账号下的可用性不一样：
     *
     * | 顺序 | 通道 | 特点 |
     * |---|---|---|
     * | 1 | `/pgc/player/web/playurl` | 网页端 v1。**我们原本唯一在用的** |
     * | 2 | `/pgc/player/web/v2/playurl` | 网页端 v2。**MyTVB 唯一在用的** |
     * | 3 | `/pgc/player/api/playurl` | API 端。chinasoul.bt 在用 |
     *
     * 实测（`tools/probe_pgc.py`）：游客态下它们**返回结构一致**（都是 DASH），
     * 但**可用性不一致** —— 有的账号在 v1 上被拒、v2 上能过，反之也有。
     * 所以"依次试"比"押一条"稳。
     *
     * ⚠️ 都返回 `code=0` + 空 data 时（未登录），三条都会是 null，
     * 这时**不要**把"三条都失败"报成"接口挂了" —— 见调用方的文案分支。
     */
    suspend fun pgcPlayInfo(epId: Long, cid: Long,
        source: top.bilitv.data.settings.VideoApiSource = settings.videoApiSource,
        accepts: (PlayInfo) -> Boolean = { true }): PlayInfo? = playbackRequest(source, accepts) { source ->
        if (source == top.bilitv.data.settings.VideoApiSource.APP) {
            requireAppCredentialOrGuest()
            appVideo.play(0, cid, "", epId, settings.preferHevc && !settings.forceAvc,
                store.accessKey, store.buvid)
        } else pgcWebPlayInfo(epId, cid)
    }

    private suspend fun pgcWebPlayInfo(epId: Long, cid: Long): PlayInfo? {
        for (path in PGC_PLAYURL_PATHS) {
            val r = try { parsePlayInfo(getRaw(path, playUrlParams(epId = epId, cid = cid))) }
                catch (e: Exception) { if (e is CancellationException) throw e; null }
            if (r != null && r.videos.isNotEmpty()) {
                if (path != PGC_PLAYURL_PATHS.first()) {
                    AppLog.i(TAG, "PGC 取流：v1 不通，改用 $path 成功")
                }
                return r
            }
        }
        AppLog.w(TAG, "PGC 取流三条通道都没拿到流 ep_id=$epId（未登录时属于正常现象）")
        return null
    }

    // ---------------------------------------------------------------- 关注（需登录）

    /**
     * 我关注的人 `/x/relation/followings`。
     *
     * ## 未登录的表现是 `code=-101 账号未登录`
     *
     * 这是一个**可判别**的信号（2026-09-29 实测，见 `tools/probe_follow.py`），
     * 和 PGC 那种 `code=0 + data=null` 不一样 —— 这里至少能确定"是没登录"。
     * 非零 code 及坏响应保留失败，不能假装为零关注。页面离开会取消读取。
     *
     * @param vmid 自己的 mid。传 0 时接口会按"当前登录用户"处理。
     */
    suspend fun followingPage(vmid: Long = 0L, pn: Int = 1, ps: Int = 30): top.bilitv.data.model.FollowingPage {
        val json = getRaw(PATH_FOLLOWINGS, mapOf(
            "vmid" to vmid.toString(),
            "pn" to pn.toString(),
            "ps" to ps.toString(),
            "order" to "desc",
            "order_type" to "attention",
        ))
        return withContext(Dispatchers.Default) { parseFollowingPage(json, pn, ps) }
    }

    /**
     * 某个 UP 主的投稿列表 `/x/space/wbi/arc/search`。
     *
     * ## ⚠️ 这个接口在游客态是**走不通**的
     *
     * 实测（2026-09-29）：不带 buvid3 → `-352 风控校验失败`；
     * 带上 buvid3 + WBI 签名 + `platform=web` → 仍然 `-352`，再试变
     * `-412 request was banned`。所以**不要再去猜参数了**，
     * 这一页只能登录后用。见 `docs/18`。
     *
     * @param order `pubdate` 按投稿时间 / `click` 按播放量
     */
    suspend fun upVideos(mid: Long, pn: Int = 1, ps: Int = 20, order: String = "pubdate"): UpVideoPage? =
        try {
            val raw = get(PATH_SPACE_ARC, mapOf(
                "mid" to mid.toString(),
                "pn" to pn.toString(),
                "ps" to ps.toString(),
                "order" to order,
                "platform" to "web",
                "web_location" to "1550101",
            ))
            val page = withContext(Dispatchers.Default) { parseUpVideoPage(raw, pn, ps) }
            if (page.items.isEmpty()) AppLog.w(TAG, "UP$mid 投稿解析出0条，页=$pn，还有页=${page.hasMore}")
            page
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            /*
             * ★ 2026-09-30 改：**失败返回 `null`，不再返回 `UpVideoPage(emptyList(), 0)`**。
             *
             * 原来调用方只能看到"空列表"，于是把"拿不到"判成了 EMPTY ——
             * 而 EMPTY 的文案是「**他没有公开的投稿**」：
             * 等于**我们网络不通，却替那个 UP 主下了个结论说他没发过视频**。
             *
             * 连带后果：`UpState.ERROR` 那一支（含它的「重新加载」按钮和
             * 「拿不到他的投稿列表」文案）**从来没有被走到过** —— 是死代码。
             * 判据见 `docs/99` §C：**"取不到"和"是空的"必须走两条不同的路。**
             */
            AppLog.e(TAG, "UP$mid 投稿失败", t)
            null
        }

    suspend fun relatedVideos(bvid: String): List<FeedItem>? =
        withContext(Dispatchers.Default) { parseRelatedVideos(getRaw("/x/web-interface/archive/related", mapOf("bvid" to bvid))) }

    suspend fun relatedSeasons(seasonId: Long): List<FeedItem>? =
        withContext(Dispatchers.Default) { parseRelatedSeasons(getRaw("/pgc/season/web/related/recommend", mapOf("season_id" to seasonId.toString()))) }

    /**
     * 动态流 `/x/polymer/web-dynamic/v1/feed/all` —— 侧栏「动态」页的唯一数据源。
     *
     * ## ⚠️ 这是全项目唯一一个**完全登录门禁**的接口
     *
     * 实测（`tools/probe_dynamic.py`，游客态）：这一族**所有变体**都回
     * `code=-101 账号未登录` ——
     *
     * | 变体 | 游客态 |
     * |---|---|
     * | `/feed/all`（全量） | `-101` |
     * | `/feed/all?type=video` | `-101` |
     * | `/feed/all/update`（增量） | `-101` |
     * | `/polymer/web-dynamic/desktop/v1/feed/all`（桌面路径） | `-101` |
     * | `/feed/all` **带 WBI 签名** | `-101` |
     *
     * 最后一行是**对照组**：带签名和不带的结果一模一样，所以「动态要不要 WBI」
     * 这个问题有答案了 —— 签不签都一样，我沿用项目里已有的 [get]（会签名），
     * 顺便白拿一次 `ensureBuvid()`。真有可能哪天变了，用不带签名的
     * `getRaw` 换一下就是一行的事。
     *
     * ## 为什么返回原始 `code` 而不只是列表
     *
     * 别的列表接口（关注/直播）靠「列表空 + `isLoggedIn()`」就能分开
     * "没登录"和"接口挂了"。这里不行：`isLoggedIn()` 为真**但**服务端回 `-101`
     * 是一种真实存在的情况（**登录过期**），界面要说的第 4 句话。
     * 判断这件事只能靠响应码，所以它必须被带上来。
     *
     * @param offset 分页游标，等于上一页的 `data.offset`。首屏传空串。
     */
    suspend fun dynamicFeed(offset: String = ""): DynamicFeed = try {
        val raw = get(
            PATH_DYNAMIC_FEED,
            mapOf(
                "timezone_offset" to "-480",
                "type" to "all",
                "page" to "1",
                // 少给 features 会影响返回形状（`itemOpusStyle` 决定图文用新版结构），
                // 这里按"网页版实际会发的"给，免得测出来的形状和真实使用不一致。
                "features" to "itemOpusStyle",
                "platform" to "web",
                "offset" to offset,
            ),
        )
        val feed = withContext(Dispatchers.Default) { parseDynamicFeed(raw) }
        if (feed.items.isEmpty()) {
            // 空列表的原因千差万别（没登录 / 全是非视频动态 / 接口改结构了），
            // 把服务端的原话和码一起记下来，别让人去猜。
            AppLog.w(TAG, "动态解析出0条（code=${feed.code}，还有页=${feed.hasMore}）")
        }
        feed
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        AppLog.e(TAG, "动态流失败", t)
        DynamicFeed(CODE_REQUEST_FAILED, emptyList(), "", false)
    }

    /** 当前登录账号的 mid。未登录返回 0。 */
    fun myMid(): Long = store.dedeUserId?.trim()?.toLongOrNull() ?: 0L

    /**
     * 「我关注了多少人」`/x/relation/stat`。**游客态可用**（同期实测 `code=0`）。
     *
     * 它唯一的用途是给关注页当**第二条独立证据**：关注列表空的时候，
     * 用它区分"真的一个都没关注"（用户能自己解决）和"列表接口出问题了"
     * （用户解决不了）。列表本身分不出这两件事，而界面要说的话完全不同。
     *
     * @return **null = 取不到**，和 0 完全不是一回事。取不到就**不要下结论**。
     */
    suspend fun followingCount(vmid: Long): Long? = try {
        parseFollowingCount(getRaw(PATH_RELATION_STAT, mapOf("vmid" to vmid.toString())))
    } catch (t: Throwable) {
        AppLog.w(TAG, "关注计数失败：${t.javaClass.simpleName}")
        null
    }

    /** 当前有没有登录（有 SESSDATA 就算）。用来区分"没登录"和"接口挂了"。 */
    fun isLoggedIn(): Boolean = store.sessdata?.isNotBlank() == true

    /**
     * 我自己的账号资料 `/x/web-interface/nav`。
     *
     * ## 为什么登录后还要再问一次，而不是登录时存下来
     *
     * 登录流程（TV 扫码）拿到的只有 `access_token` + `mid` + 三个 cookie，
     * **不含昵称和头像**。想在「我的」页显示它们，就得单独问这一次。
     * （也可以登录时顺手问一次并存起来，但那会引入"存的可能是旧的"这类问题：
     * 用户在手机改了昵称，电视上就一直显示老名字，除非再加一套刷新逻辑。）
     *
     * ## 未登录时的表现很特殊
     *
     * 它返回 `code=-101` **但同时给一份 `data{isLogin:false}`**。
     * 所以这里不能用"code != 0 就是错"来判断 —— 详见 [parseMyProfile] 的说明。
     *
     * @return 请求失败返回 [MyProfileResult.Unsupported]，协程取消正常向上传递。
     */
    suspend fun myProfile(): MyProfileResult = try {
        parseMyProfile(getRaw(NAV))
    } catch (t: Exception) {
        if (t is CancellationException) throw t
        AppLog.w(TAG, "账号资料失败：${t.javaClass.simpleName}")
        MyProfileResult.Unsupported
    }

    /**
     * 我的关注 / 粉丝 / 动态计数 `/x/web-interface/nav/stat`。
     *
     * 未登录是 `code=-101` + `data=null`，所以解析出 null。
     * 界面据此显示 `--`，**不是 0** —— 见 [MyStat]。
     */
    suspend fun myStat(): MyStat? = try {
        parseMyStat(getRaw(PATH_NAV_STAT))
    } catch (t: Exception) {
        if (t is CancellationException) throw t
        AppLog.w(TAG, "账号计数失败：${t.javaClass.simpleName}")
        null
    }

    // ---------------------------------------------------------------- 直播（另一个域名）

    /**
     * 直播站的 GET。
     *
     * ## 为什么单开一个方法，不复用 [get]
     *
     * 两件事完全不一样：
     * 1. **域名不同** —— 直播在 `api.live.bilibili.com`，[get] 拼的是点播站；
     * 2. **没有 WBI 签名** —— 直播这一族是老接口，不认 `w_rid`。
     *    而且它**不需要 [ensureBuvid]**（实测：带不带 `buvid3` 结果一样），
     *    所以也别为它多花一个请求。
     *
     * 把这两点写成一个方法，是为了以后**不会再有人**顺手把 `get()` 套到直播路径上 ——
     * 那样会得到一个"签名对了、域名错了"的 404，查起来很费劲。
     */
    private suspend fun getLive(path: String, params: Map<String, String> = emptyMap()): String =
        String(getRawBytes(path, params, LIVE_BASE), Charsets.UTF_8)

    /**
     * 推荐直播（直播页的主内容源）。
     *
     * ★ 用的是**老接口** `/room/v1/room/get_user_recommend`。
     * 新的 `/xlive/web-interface/v1/second/getList` 在游客态**稳定 -352**，
     * 别改回去。对照表见 `Parsers.kt` 里"直播"那一节的注释。
     */
    /**
     * 「我关注的、正在直播的房间」
     * （`/xlive/web-ucenter/v1/xfetter/GetWebList`）。
     *
     * ★ 2026-09-30 少爷（截图批注）：「直播第一个是关注，第二个是推荐」。
     *
     * ⛔ **要登录**。未登录时接口给 `code=-101`，列表为空 —— 界面把这种情况
     * 当"没有在播的关注"处理（和"没登录"共用一套空态文案，见 LiveScreen）。
     *
     * ⚠️ 响应形状见 [parseLiveFollowing]：`data` 是对象、列表在 `data.list`、
     * 每条外面包一层 `room_info` —— 和另外两个列表接口**都不一样**。
     */
    suspend fun liveFollowing(page: Int = 1, size: Int = 30, strict: Boolean = false): List<LiveRoom> =
        liveOrLog("关注直播", PATH_LIVE_FOLLOWING, mapOf(
            "page" to page.toString(),
            "page_size" to size.toString(),
        ), strict) { raw ->
            val list = parseLiveFollowing(raw)
            // ★ 0 条时把原文打出来 —— 这个接口没文档，形状只能实测（见 parseLiveFollowing 的说明）
            if (list.isEmpty()) AppLog.w(TAG, "关注直播 0 条 | 响应前 300 字: ${raw.take(300)}")
            list
        }

    /**
     * 推荐直播（直播页的主内容源）。
     *
     * ★ 用的是**老接口** `/room/v1/room/get_user_recommend`。
     * 新的 `/xlive/web-interface/v1/second/getList` 在游客态**稳定 -352**，
     * 别改回去。对照表见 `Parsers.kt` 里"直播"那一节的注释。
     */
    suspend fun liveRecommend(page: Int = 1, size: Int = 30, strict: Boolean = false): List<LiveRoom> =
        liveOrLog("推荐直播", PATH_LIVE_RECOMMEND, mapOf(
            "page" to page.toString(),
            "page_size" to size.toString(),
        ), strict) { parseLiveRecommend(it) }

    /**
     * 按分区取直播列表 `/room/v1/Area/getRoomList`（同样是老接口，同样因为新接口 -352）。
     *
     * @param parentAreaId 大区 id（1=娱乐 2=网游…），传 0 表示"全部".
     * @param areaId 子区 id。传 0 表示这个大区下不限子区。
     */
    suspend fun liveAreaRooms(
        parentAreaId: Int = 0,
        areaId: Int = 0,
        page: Int = 1,
        size: Int = 30,
        strict: Boolean = false,
    ): List<LiveRoom> = liveOrLog("分区直播", PATH_LIVE_AREA_ROOMS, mapOf(
        "parent_area_id" to parentAreaId.toString(),
        "area_id" to areaId.toString(),
        "sort_type" to "online",
        "page" to page.toString(),
        "page_size" to size.toString(),
    ), strict) { parseLiveAreaRooms(it) }

    /** 直播分区表 `/room/v1/Area/getList`。界面上的大区/子区标签靠它 */
    suspend fun liveAreas(): List<LiveArea> =
        liveOrLog("直播分区", PATH_LIVE_AREA_LIST, mapOf("parent_area_id" to "1")) {
            parseLiveAreas(it)
        }

    /**
     * 房间信息 `/xlive/web-room/v1/index/getRoomBaseInfo`。
     *
     * ★ 不用更常见的 `getInfoByRoom` —— 那个游客态 -352。
     *
     * ⚠️ **一次只吃一个房间号**，而且 `req_biz` 不能省。实测（2026-09-29）：
     *
     * | 传参 | 结果 |
     * |---|---|
     * | `room_ids=545068&req_biz=web_room_page` | ✅ `code=0`，`live_status=1` |
     * | `room_ids=545068`（少 `req_biz`） | ❌ `-400 请求错误` |
     * | `room_ids=545068,1775719573`（逗号拼多个） | ❌ `-400 请求错误` |
     *
     * 所以这里**不提供"批量查房间"的入口** —— 参数长得很像支持批量，
     * 但拼两个就 -400。以后真要一次问多个房间，得先探针验过再说。
     *
     * @return null 表示**取不到**（不是"房间不存在"）。界面据此说
     *   "拿不到房间信息"，而不是编一个"这个直播间不存在"。
     */
    suspend fun liveRoomInfo(roomId: Long): LiveRoom? = try {
        val d = parseLiveRoomInfo(getLive(PATH_LIVE_ROOM_BASE, mapOf(
            "room_ids" to roomId.toString(),
            "req_biz" to "web_room_page",
        )))
        if (d == null) AppLog.w(TAG, "房间 $roomId 信息为空")
        d
    } catch (t: Throwable) {
        AppLog.e(TAG, "房间 $roomId 信息失败", t)
        null
    }

    /**
     * 直播取流 `/xlive/web-room/v2/index/getRoomPlayInfo`。
     *
     * ## 参数为什么是这么一串
     *
     * - `protocol=0,1` → 要 `http_stream` 和 `http_hls` 两种
     * - `format=0,1,2` → 要 `flv` / `ts` / `fmp4` 三种封装
     * - `codec=0,1` → 要 `avc` 和 `hevc`
     * - `qn=10000` → 申请原画；**游客态会被降级到 250**（实测 `current_qn=250`），
     *   这不是我们写错了，登录后才能拿到 10000/400。想要哪一档可以看 `accept_qn`。
     * - `ptype=8` → 网页端。换别的值会拿到 H5/APP 那套结构（字段名都不一样）。
     *
     * ## 返回 null ≠ 出错
     *
     * 房间没开播时 `playurl_info` 是 null，但 `code:0` 是正常的 ——
     * 那种情况会返回一个 [LivePlayInfo]（`lines` 为空、`liveStatus` 是 2）。
     * **null 只在真的解析不出来（接口变了 / 风控）时返回。**
     */
    suspend fun livePlayInfo(roomId: Long, quality: Int = 10000): LivePlayInfo? = try {
        val info = parseLivePlayInfo(getLive(PATH_LIVE_PLAY_INFO, mapOf(
            "room_id" to roomId.toString(),
            "protocol" to "0,1",
            "format" to "0,1,2",
            "codec" to "0,1",
            "qn" to "$quality",
            "platform" to "web",
            "ptype" to "8",
            "dolby" to "5",
            "panorama" to "1",
        )))
        if (info == null) {
            AppLog.w(TAG, "房间 $roomId 取流解析失败")
        } else {
            AppLog.i(
                TAG,
                "房间 $roomId 取流：状态=${info.liveStatus} 线路 ${info.lines.size} 条 " +
                    info.lines.joinToString(" ") { "${it.protocol}/${it.format}/${it.codec}@${it.qn}" },
            )
        }
        info
    } catch (t: Throwable) {
        AppLog.e(TAG, "房间 $roomId 取流失败", t)
        null
    }

    suspend fun liveDanmaku(roomId: Long, onBatch: (List<DanmakuItem>) -> Unit) {
        require(roomId > 0)
        ensureBuvid()
        val (i, s) = wbiKeys()
        val data = requireActionResponse(getLive("/xlive/web-room/v1/index/getDanmuInfo",
            Wbi.sign(mapOf("id" to "$roomId", "type" to "0", "web_location" to "444.8"), i, s))).getJSONObject("data")
        val host = data.getJSONArray("host_list").getJSONObject(0)
        val hostname = host.getString("host")
        check(hostname.endsWith(".chat.bilibili.com") && hostname.matches(Regex("[a-zA-Z0-9.-]+"))) { "直播弹幕服务器地址异常" }
        val port = host.optInt("wss_port", 443)
        check(port in 1..65535)
        val auth = JSONObject().put("uid", store.dedeUserId?.toLongOrNull() ?: 0L).put("roomid", roomId)
            .put("protover", 2).put("platform", "web").put("type", 2).put("key", data.getString("token"))
            .put("buvid", store.buvid.orEmpty())
        top.bilitv.data.danmaku.receiveLiveDanmaku(client, "wss://$hostname:$port/sub", auth, onBatch)
    }

    /**
     * 直播这一族的取数外壳。
     *
     * 和 [feedOrLog] 是同一个用意（失败记异常 + 留服务端原话），
     * 但**单独一份**：直播走的是另一个域名、另一套错误码，
     * 日志前缀要能一眼分开，否则排查时会去点播站找问题。
     */
    private suspend fun <T> liveOrLog(
        what: String,
        path: String,
        params: Map<String, String>,
        strict: Boolean = false,
        parse: (String) -> List<T>,
    ): List<T> = try {
        val raw = getLive(path, params)
        val list = withContext(Dispatchers.Default) { parse(if (strict) requireFeedSuccess(raw) else raw) }
        if (list.isEmpty()) AppLog.w(TAG, "[直播] $what 解析出 0 条 | 响应前 300 字: ${raw.take(300)}")
        list
    } catch (t: Throwable) {
        if (t is CancellationException || strict) throw t
        AppLog.e(TAG, "[直播] $what 失败", t)
        emptyList()
    }

    // ---------------------------------------------------------------- 扫码登录

    /**
     * 申请一张登录二维码。
     *
     * ⚠️ **必须 POST**。用 GET 会得到 `405 Method Not Allowed`，返回体只有 19 字节 ——
     * 那看起来像"接口下线了"，其实只是方法错了（2026-09-29 实测）。
     */
    suspend fun tvQrSession(): TvQrSession? = try {
        val ts = System.currentTimeMillis() / 1000
        val body = qrPostForm(
            PASSPORT_BASE,
            PATH_TV_QR,
            TvLogin.sign(mapOf("appkey" to TvLogin.APPKEY, "local_id" to "0"), ts),
        )
        val d = JSONObject(body).optJSONObject("data")
        val url = d?.optString("url").orEmpty()
        val code = d?.optString("auth_code").orEmpty()
        if (url.isBlank() || code.isBlank()) {
            AppLog.w(TAG, "申请二维码返回异常 code=${JSONObject(body).optInt("code", -1)}")
            null
        } else {
            TvQrSession(url = url, authCode = code)
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        AppLog.w(TAG, "申请二维码失败：${t.javaClass.simpleName}")
        null
    }

    /**
     * 轮询扫码结果。**成功时顺手把 Cookie 存下来** —— 存的动作放在这里而不是界面层，
     * 是因为"拿到凭证就落盘"必须是一次不可分割的事，界面层忘了调就白登录了。
     */
    suspend fun tvPoll(authCode: String): TvLoginPoll = try {
        val ts = System.currentTimeMillis() / 1000
        val body = qrPostForm(
            PASSPORT_BASE,
            PATH_TV_POLL,
            TvLogin.sign(
                mapOf(
                    "appkey" to TvLogin.APPKEY,
                    "auth_code" to authCode,
                    "local_id" to "0",
                ),
                ts,
            ),
        )
        val json = JSONObject(body)
        val rawCode = json.optInt("code", -1)
        when (rawCode) {
            0 -> {
                val d = json.optJSONObject("data") ?: return TvLoginPoll.Failed(-1, "成功但没带数据")
                val cookies = HashMap<String, String>()
                d.optJSONObject("cookie_info")?.optJSONArray("cookies")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        cookies[c.optString("name")] = c.optString("value")
                    }
                }
                val token = d.optJSONObject("token_info") ?: JSONObject()
                store.sessdata = cookies["SESSDATA"]
                store.biliJct = cookies["bili_jct"]
                store.dedeUserId = cookies["DedeUserID"]
                store.accessKey = token.optString("access_token").takeIf { it.isNotBlank() }
                // ★ 必须立刻推进内存缓存 —— 否则这次登录要等**重启 App** 才生效
                //   （2026-09-29 电视真机实测，见 applyStoredCredentials 的说明）
                applyStoredCredentials()
                AppLog.i(TAG, "扫码登录成功（凭证已落盘，不回显内容）")
                TvLoginPoll.Success(
                    cookies = cookies,
                    accessToken = token.optString("access_token"),
                    refreshToken = token.optString("refresh_token"),
                )
            }
            86090 -> TvLoginPoll.Scanned
            86039 -> TvLoginPoll.Waiting
            86038 -> TvLoginPoll.Expired
            else -> TvLoginPoll.Failed(rawCode, json.optString("message"))
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        AppLog.w(TAG, "轮询扫码结果失败：${t.javaClass.simpleName}")
        TvLoginPoll.Failed(-1, t.message ?: "网络异常")
    }

    // ---------------------------------------------------------------- 收藏夹

    /**
     * 我的收藏夹列表。**要登录**，而且要显式传自己的 mid。
     *
     * ⚠️ **`up_mid` 不能省** —— 省了就是 `code=-400 请求错误`，
     * 而那个错**看起来像接口坏了**（`docs/21` 记过这次误判）。
     * 游客态拿不到 mid，所以这条路径只有登录后才跑得到。
     *
     * ⚠️ **未实测**：响应形状是按公开文档与同类项目写的，
     * 配套探针 `tools/probe_fav.py`，登录后跑一次核对。
     */
    suspend fun favFolders(mid: Long): List<FavFolder>? = try {
        withContext(Dispatchers.Default) { parseFavFolders(getRaw(PATH_FAV_FOLDERS, mapOf("up_mid" to mid.toString()))) }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        /*
         * ★ 2026-09-30 改：**失败返回 `null`，不再返回 `emptyList()`**。
         *
         * 理由同 [favResources]：原来失败和"真的一个收藏夹都没有"都返回空列表，
         * 界面就显示「还没有收藏夹」—— 用户有 4 个夹的时候看到这句，
         * 会以为收藏功能坏了。**"取不到"和"是空的"必须走两条不同的路。**
         */
        AppLog.e(TAG, "收藏夹列表失败", t)
        null
    }

    /**
     * 某个收藏夹里的资源。
     *
     * `platform=web` 是**必须的** —— 不带它接口按移动端口径返回，
     * 字段会少一批（这是同类客户端的共同做法）。
     */
    suspend fun favResourcePage(mediaId: Long, pn: Int = 1, ps: Int = 20): top.bilitv.data.model.FavResourcePage? = try {
        withContext(Dispatchers.Default) { parseFavResourcePage(
            getRaw(
                PATH_FAV_RESOURCES,
                mapOf(
                    "media_id" to mediaId.toString(),
                    "pn" to pn.toString(),
                    "ps" to ps.toString(),
                    "platform" to "web",
                    // 只要视频。不带的话会把音频/专栏/合集一起塞进来，
                    // 而我们的卡片只认得视频（没有 bvid 的会被解析层丢掉，白拉一趟）
                    "type" to "0",
                ),
            )
        ) }
    } catch (t: Throwable) {
        /*
         * ★ 2026-09-30 改：**失败返回 `null`，不再返回 `emptyList()`**。
         *
         * 原来失败和"这个夹真的没有内容"都返回空列表，界面就**分不清**了 ——
         * 结果是网络一断、收藏夹标题上写着「这个收藏夹是空的」，
         * 而那个夹里其实有 318 条。**用户会以为自己的收藏全丢了。**
         *
         * 这正是 `docs/99` §C 那一类"拿不到别当成没有"：**"取不到"和"是空的"
         * 必须走两条不同的路**（前者要说"拿不到、可以重试"，后者才说"这个是空的"）。
         *
         * 返回类型可空是**故意的**：让每个调用方都**必须**显式回答
         * "拿不到的时候怎么办"，而不是顺手把空列表当成数据。
         */
        if (t is CancellationException) throw t
        AppLog.e(TAG, "收藏夹内容失败", t)
        null
    }

    /**
     * 退出登录：清空凭证。
     *
     * ⚠️ **三处都要清**，少一处就是"界面说退出了、请求还在用旧身份"：
     * `store`（磁盘）+ [cookieCache] + [AuthBus]。见 [dropCredentialCookies]。
     */
    fun logout() {
        store.clear()
        dropCredentialCookies()
        AppLog.i(TAG, "已退出登录")
    }

    // ---------------------------------------------------------------- 网页端扫码登录

    /**
     * 申请网页端登录二维码。
     *
     * ⚠️ 是 **GET**，和 TV 端那套（必须 POST）**相反**。用错方法会得到 405。
     * 不需要 appkey 签名，也不需要 cookie —— 纯公开接口。
     */
    suspend fun webQrSession(): WebQrSession? = try {
        val body = getRaw(PATH_WEB_QR_GENERATE)
        val d = JSONObject(body).optJSONObject("data")
        val url = d?.optString("url").orEmpty()
        val key = d?.optString("qrcode_key").orEmpty()
        if (url.isBlank() || key.isBlank()) {
            AppLog.w(TAG, "网页端申请二维码返回异常 code=${JSONObject(body).optInt("code")}")
            null
        } else {
            WebQrSession(url = url, qrcodeKey = key)
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        AppLog.w(TAG, "网页端申请二维码失败：${t.javaClass.simpleName}")
        null
    }

    /**
     * 轮询网页端扫码结果。成功时**顺手同步多域名 Cookie + 落盘凭据**。
     *
     * ## 为什么"成功就立刻做三件事"而不是交给界面层
     *
     * 1. 存 `SESSDATA` 等（不存 = 白登录）
     * 2. 存 `refresh_token`（网页端唯一能续期的凭据）
     * 3. **调 `/sso/list` 同步其它域名**（不做 = "首页能看直播不能看"）
     *
     * 这三件是**一次不可分割的登录收尾**。放在界面层的话，
     * 任何一个界面忘了调，就得到一个"看似登录、实则残缺"的状态 ——
     * 而且这种残缺**不会报错**，只会表现为某些页面莫名其妙用不了。
     */
    suspend fun webPoll(qrcodeKey: String): WebLogin.Poll {
        return try {
            val body = getRaw(PATH_WEB_QR_POLL, mapOf("qrcode_key" to qrcodeKey))
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: JSONObject()
            // ★ 注意：网页端的返回码在 **data.code** 里，不在顶层 code！
            //   顶层 code=0 只表示"请求成功"，真正的业务码在 data.code。
            //   这一点和 TV 端不一样（TV 端的码在顶层）。
            val bizCode = data.optInt("code", -1)
            when (bizCode) {
                0 -> {
                    val cookies = collectLoginCookies()
                    if (cookies["SESSDATA"].isNullOrBlank()) {
                        AppLog.w(TAG, "网页端登录成功但没拿到 SESSDATA")
                        return WebLogin.Poll.Failed(-1, "登录成功但凭据不完整")
                    }
                    store.sessdata = cookies["SESSDATA"]
                    store.biliJct = cookies["bili_jct"]
                    store.dedeUserId = cookies["DedeUserID"]
                    // 同 TV 端：立刻推进内存缓存（这条路的 cookie 是 Set-Cookie 收进来的，
                    // 通常已经在缓存里了，但显式再推一次保证两条登录路径行为一致）
                    applyStoredCredentials()

                    val refresh = data.optString("refresh_token").takeIf { it.isNotBlank() }
                    AuthBus.prefs.refreshToken = refresh

                    // 多域名同步 —— 失败不影响登录本身
                    syncSsoCookies()

                    AppLog.i(TAG, "网页端扫码登录成功（凭证已落盘，不回显内容）")
                    WebLogin.Poll.Success(cookies = cookies, refreshToken = refresh.orEmpty())
                }
                86101 -> WebLogin.Poll.Waiting
                86090 -> WebLogin.Poll.Scanned
                86038 -> WebLogin.Poll.Expired
                else -> WebLogin.Poll.Failed(bizCode, data.optString("message"))
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLog.w(TAG, "网页端轮询失败：${t.javaClass.simpleName}")
            WebLogin.Poll.Failed(-1, t.message ?: "网络异常")
        }
    }

    /**
     * 把 cookie jar 里收到的登录 cookie 收拢成 map。
     *
     * 网页端登录的 cookie 是**通过 `Set-Cookie` 下发的**（不像 TV 端在响应体 JSON 里），
     * 所以要从 cookie jar 读回来，而不是从响应体解析。
     */
    private fun collectLoginCookies(): Map<String, String> {
        val jar = AuthBus.cookies
        return buildMap {
            listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5").forEach { name ->
                jar.get(name)?.takeIf { it.isNotBlank() }?.let { put(name, it) }
            }
        }
    }

    /**
     * 调 `/sso/list` 把主站 Cookie 同步到其它 B 站域名。
     *
     * 三家参考客户端都有这一步（MyTVB 的字符串池里能扫到 `SsoListModel`）。
     * 不做的话，直播站 / 国际版域名会认为你没登录。
     *
     * 失败**只记 warning** —— 它的作用是"覆盖面更广"，缺了不会让已登录的主功能失效。
     */
    private suspend fun syncSsoCookies() {
        runCatching {
            val body = getRaw(WebLogin.PATH_SSO_LIST)
            val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("sso")
                ?: return@runCatching
            val urls = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            val merged = parseSsoCookies(urls)
            if (merged.isNotEmpty()) {
                AuthBus.cookies.seed(merged)
                AppLog.i(TAG, "SSO 同步完成，合并 ${merged.size} 条 cookie（不回显内容）")
            } else {
                AppLog.i(TAG, "SSO 同步：没有需要合并的条目")
            }
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.w(TAG, "SSO 同步失败：${it.javaClass.simpleName}")
        }
    }

    /**
     * 所有"列表页数据源"共用的取数外壳。
     *
     * 这些方法**故意不用 `runCatching { }.getOrNull()` 一句话吞掉**：
     * 列表页失败时界面只会显示「暂时没有内容」，而"接口变了"和"网络不通"
     * 这两种情况的修法完全相反。所以失败一律记异常、把服务端原话写进日志。
     * 返回空列表只是为了界面不崩，不代表"这里没问题"。
     */
    private suspend fun <T> feedOrLog(
        what: String,
        path: String,
        params: Map<String, String>,
        strict: Boolean = false,
        parse: (String) -> List<T>,
    ): List<T> = try {
        val raw = get(path, params)
        val list = withContext(Dispatchers.Default) { parse(if (strict) requireFeedSuccess(raw) else raw) }
        if (list.isEmpty()) AppLog.w(TAG, "$what 解析出 0 条 | 响应前 300 字: ${raw.take(300)}")
        list
    } catch (t: Throwable) {
        if (t is CancellationException || strict) throw t
        AppLog.e(TAG, "$what 失败", t)
        emptyList()
    }

    companion object {
        const val BASE = "https://api.bilibili.com"

        /**
         * ★ 直播站。**和点播不是一个域名** —— 直播接口全在这里，且**不认 WBI 签名**。
         * 详见 [getLive]。
         */
        const val LIVE_BASE = "https://api.live.bilibili.com"

        /** 登录走另一个域名。别把 WBI 签名套到它上面 —— 两套签名毫无关系 */
        const val PASSPORT_BASE = "https://passport.bilibili.com"
        const val PATH_TV_QR = "/x/passport-tv-login/qrcode/auth_code"
        const val PATH_TV_POLL = "/x/passport-tv-login/qrcode/poll"
        /** 网页端扫码申请。GET，无需签名。见 [webQrSession] */
        const val PATH_WEB_QR_GENERATE = WebLogin.PATH_QR_GENERATE
        /** 网页端扫码轮询。GET，带 qrcode_key。见 [webPoll] */
        const val PATH_WEB_QR_POLL = WebLogin.PATH_QR_POLL
        const val NAV = "/x/web-interface/nav"
        /**
         * 我的关注/粉丝/动态计数。
         *
         * ⚠️ 和 [PATH_RELATION_STAT]（`/x/relation/stat?vmid=x`）是**两个接口**：
         * 那个要传 vmid 查别人，这个查"我自己"，且**只有登录后可用**
         * （游客态 `-101` + `data=null`，实测见 `tools/probe_mine.py`）。
         */
        const val PATH_NAV_STAT = "/x/web-interface/nav/stat"
        const val PATH_VIEW = "/x/web-interface/view"
        const val PATH_PLAYURL = "/x/player/wbi/playurl"
        const val PATH_FEED = "/x/web-interface/wbi/index/top/feed/rcmd"
        const val PATH_POPULAR = "/x/web-interface/popular"
        const val PATH_NEWLIST = "/x/web-interface/newlist"
        const val PATH_WEEKLY_LIST = "/x/web-interface/popular/series/list"
        const val PATH_WEEKLY_ONE = "/x/web-interface/popular/series/one"
        const val PATH_PGC_INDEX = "/pgc/season/index/result"
        const val PATH_PGC_SEASON = "/pgc/view/web/season"
        /**
         * PGC 取流三条通道，**按顺序尝试**（见 [pgcPlayInfo]）。
         *
         * ⚠️ 第一个必须保持是 `web/playurl` —— 它是实测游客态可用、
         * 且我们历史数据最多的那条，放第一个能让绝大多数请求一次命中。
         */
        val PGC_PLAYURL_PATHS = listOf(
            "/pgc/player/web/playurl",
            "/pgc/player/web/v2/playurl",
            "/pgc/player/api/playurl",
        )
        /** 兼容旧引用（[pgcPlayInfo] 已改成走 [PGC_PLAYURL_PATHS]）。 */
        const val PATH_PGC_PLAYURL = "/pgc/player/web/playurl"
        const val PATH_SEARCH = "/x/web-interface/wbi/search/type"
        const val PATH_HOT_SEARCH = "/x/web-interface/search/square"
        /** 关注列表。**未登录返回 -101**，见 [followingPage] */
        const val PATH_FOLLOWINGS = "/x/relation/followings"

        /**
         * 动态流。**登录门禁**接口 —— 游客态一律 `-101`（详见 [dynamicFeed] 的表）。
         * 注意它**不在** WBI 签名接口名单里：带不带签名实测结果一模一样。
         */
        const val PATH_DYNAMIC_FEED = "/x/polymer/web-dynamic/v1/feed/all"
        /** 关注/粉丝计数。**游客态可用**，见 [followingCount] */
        const val PATH_RELATION_STAT = "/x/relation/stat"
        /** UP 主投稿列表。**游客态一律 -352**，只能登录后用，见 [upVideos] */
        const val PATH_SPACE_ARC = "/x/space/wbi/arc/search"
        /** 设备指纹。`buvid3` 在**响应体** `data.b_3`，不在 Set-Cookie，见 [ensureBuvid] */
        const val PATH_FINGER_SPI = "/x/frontend/finger/spi"

        /*
         * ---- 直播（都在 LIVE_BASE 上，且都是**老接口**）----
         *
         * ⚠️ 这一族别按"名字里没有 v2 就是旧的"去升级：新版的几个
         * （`/xlive/web-interface/v1/second/getList`、
         *  `/xlive/web-interface/v1/second/getListByArea`、
         *  `/xlive/web-room/v1/index/getInfoByRoom`）
         * 在游客态**一律 -352 风控**，而老的这一批全通。实测对照见 `Parsers.kt`。
         */
        const val PATH_LIVE_RECOMMEND = "/room/v1/room/get_user_recommend"
        // 「我关注的、正在直播的」——少爷要求直播页第一个分区是它
        const val PATH_LIVE_FOLLOWING = "/xlive/web-ucenter/v1/xfetter/GetWebList"
        const val PATH_LIVE_AREA_ROOMS = "/room/v1/Area/getRoomList"
        const val PATH_LIVE_AREA_LIST = "/room/v1/Area/getList"
        const val PATH_LIVE_ROOM_BASE = "/xlive/web-room/v1/index/getRoomBaseInfo"
        const val PATH_LIVE_PLAY_INFO = "/xlive/web-room/v2/index/getRoomPlayInfo"
        const val PATH_DM_SEG = "/x/v2/dm/web/seg.so"

        /** 收藏夹列表。**必须带 `up_mid`**，见 `parseFavFolders` 的说明。 */
        const val PATH_FAV_FOLDERS = "/x/v3/fav/folder/created/list-all"

        /** 收藏夹里的资源。 */
        const val PATH_FAV_RESOURCES = "/x/v3/fav/resource/list"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val KEY_TTL = 12 * 60 * 60 * 1000L
        private const val TAG = "Api"
    }
}
