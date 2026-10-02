package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import top.bilitv.data.api.buildBiliCookie

/**
 * Cookie 构造的回归测试（2026-09-29）。
 *
 * ## 为什么值得单独一个文件
 *
 * 这一条对着的是一个**让 App 一启动就闪退**的真事故 —— 而且它在很长一段时间里
 * 没被人发现，因为它有个前提：**本机得存过至少一条 Cookie**。
 *
 * ```
 * java.lang.IllegalArgumentException: unexpected domain: .bilibili.com
 *     at okhttp3.Cookie$Builder.domain(Cookie.kt:297)
 *     at top.bilitv.data.api.BiliApi.buildCookie(BiliApi.kt:56)
 *     at top.bilitv.data.api.BiliApi.<init>(BiliApi.kt:84)
 * ```
 *
 * 崩的位置是 `BiliApi` 的构造 → 挂在 `BiliTvApp.api` 的 `by lazy` 上 →
 * 首页第一次取接口就炸。而报错信息里只有 `Cookie$Builder`，看不出
 * "只要登录过一次就会崩"这件事。
 *
 * 所以这条测试钉两个点：
 * 1. **域名的写法**必须是 OkHttp 认的那种（前导点会让 `build()` 直接抛）；
 * 2. 空值要走 null 分支，不能造出一条空 Cookie。
 *
 * OkHttp 是个纯 JVM 库，所以这几条能在**不联网、不上模拟器**的前提下跑。
 */
class ApiCookieTest {

    @Test
    fun 造Cookie_域名不能带前导点() {
        // ★ 这条就是那个闪退事故。`domain(".bilibili.com")` 会抛
        //   IllegalArgumentException: unexpected domain
        val c = buildBiliCookie("SESSDATA", "abc123")
        assertNotNull(c)
        assertEquals("bilibili.com", c!!.domain)
        assertEquals("SESSDATA", c.name)
        assertEquals("abc123", c.value)
    }

    @Test
    fun 造Cookie_域名要覆盖点播和直播两个站() {
        // 直播在 api.live.bilibili.com，点播在 api.bilibili.com —— 都是 *.bilibili.com。
        // OkHttp 里无前导点的 `bilibili.com` 同时匹配这两者（host == domain 或
        // host 以 ".$domain" 结尾），所以一个域名够用
        val c = buildBiliCookie("buvid3", "X".repeat(46))
        assertNotNull(c)
        assertEquals("bilibili.com", c!!.domain)
    }

    @Test
    fun 造Cookie_空值和空白走null分支() {
        // 未登录时 CredentialStore 读回来的是 null / 空串。
        // 这条分支必须存在，否则第一次装完 App（没有任何 Cookie）也会去造空 Cookie
        assertNull(buildBiliCookie("SESSDATA", null))
        assertNull(buildBiliCookie("SESSDATA", ""))
        assertNull(buildBiliCookie("SESSDATA", "   "))
    }

    @Test
    fun 造Cookie_值里的特殊字符不影响构造() {
        // 登录后 SESSDATA 里带 % 和 * 之类的字符（是 URL 编码过的）。
        // 这里只保证"不会因为值而抛异常" —— OkHttp 对 value 是宽松的
        val messy = "%2Aabc*def%2Bghi=="
        val c = buildBiliCookie("SESSDATA", messy)
        assertNotNull(c)
        assertEquals(messy, c!!.value)
    }
}
