package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.api.parseMyProfile
import top.bilitv.data.api.parseMyStat
import top.bilitv.data.model.MyProfileResult
import top.bilitv.ui.components.fixedScheme
import top.bilitv.ui.mine.statText

/**
 * 「我的」页的解析测试（2026-09-29）。
 *
 * ## 这一组钉的是**全项目唯一一个绕开 `dataOf()` 的解析器**
 *
 * `/x/web-interface/nav` 在未登录时**既报错、又给一份 data**：
 *
 * ```json
 * {"code":-101,"message":"账号未登录","data":{"isLogin":false,"wbi_img":{…},"ip_region":"…"}}
 * ```
 *
 * 这是 `tools/probe_mine.py` 的**实测原文**（不是想象的）。
 * `dataOf()` 要求 `code==0`，用它就会拿到 null，于是"没登录"和"接口坏了"
 * 在代码里变成同一件事 —— 而界面上这两句话该做的事完全不同。
 *
 * 所以第一条测试就是钉这个：**必须解析成 [MyProfileResult.NotLoggedIn]，
 * 不能是 [MyProfileResult.Unsupported]**。写错了界面上就会对已经登录过的人
 * 说"还没登录"，或者对没登录的人说"接口挂了"。
 *
 * ## 已登录的那一半是**未实测**的（诚实说明）
 *
 * 少爷还没在这台设备上登录过，所以「已登录」形状的 JSON 是照
 * 公开文档 + 惯例写的，**没有拿真实响应核过**。处置办法：
 *
 * 1. 解析器对每个字段都用 `optXxx`，缺字段不崩、也不编值；
 * 2. 界面在昵称为空时显示「已登录的账号」而不是编号；
 * 3. 等少爷登录后第一件事就是拿真实响应核一遍（见 docs/21）。
 */
class MineTest {

    // ------------------------------------------------------ nav / 我是谁

    @Test
    fun 未登录的nav_必须解析成未登录而不是接口坏了() {
        // ★ 实测原文（tools/probe_mine.py）：-101 的同时 data 里有 isLogin:false。
        //   这一条写错，界面就会把"该去登录"显示成"接口出问题了"。
        val real = """
            {"code":-101,"message":"账号未登录","ttl":1,"data":{
              "isLogin":false,
              "wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/abc.png",
                         "sub_url":"https://i0.hdslb.com/bfs/wbi/def.png"},
              "ip_region":"甘肃"
            }}
        """.trimIndent()

        assertEquals(MyProfileResult.NotLoggedIn, parseMyProfile(real))
    }

    @Test
    fun 已登录的nav_读出昵称头像UID等级() {
        // ⚠️ 这一份是**照文档写的、未经实测**（见类注释）。字段名是 B 站公开的稳定形状。
        val json = """
            {"code":0,"message":"0","ttl":1,"data":{
              "isLogin":true,
              "mid":293793435,
              "uname":"少爷",
              "face":"http://i2.hdslb.com/bfs/face/abc.jpg",
              "level_info":{"current_level":5,"current_min":0,"current_exp":0,"next_exp":0},
              "vipStatus":0,
              "vipType":0,
              "money":500,
              "moral":70
            }}
        """.trimIndent()

        val r = parseMyProfile(json)
        assertTrue(r is MyProfileResult.Ok)
        val p = (r as MyProfileResult.Ok).profile
        assertEquals(293793435L, p.mid)
        assertEquals("少爷", p.name)
        assertEquals("http://i2.hdslb.com/bfs/face/abc.jpg", p.face)
        assertEquals(5, p.level)
        assertEquals(false, p.vip)
    }

    @Test
    fun 大会员标记只看vipStatus等于1() {
        fun nav(vipStatus: Int) = """
            {"code":0,"data":{"isLogin":true,"mid":1,"uname":"x","face":"","vipStatus":$vipStatus}}
        """.trimIndent()
        assertEquals(true, (parseMyProfile(nav(1)) as MyProfileResult.Ok).profile.vip)
        // 0 = 不是；别的值（比如 2 = 过期）也**不算大会员** —— 只认 1，
        // 免得给一个已经过期的人挂上"大会员"角标
        assertEquals(false, (parseMyProfile(nav(0)) as MyProfileResult.Ok).profile.vip)
        assertEquals(false, (parseMyProfile(nav(2)) as MyProfileResult.Ok).profile.vip)
    }

    @Test
    fun nav缺isLogin字段时按拿不到处理_不猜() {
        // ★ 没有 isLogin 说明接口结构变了。这时候**不能猜**：
        //   猜成"已登录"会给一个空壳资料页；猜成"没登录"会让一个已经登录的人
        //   被要求重新登录。两种都比"说一句拿不到"差。
        assertTrue(parseMyProfile("""{"code":0,"data":{"mid":1,"uname":"x"}}""") is MyProfileResult.Unsupported)
        assertTrue(parseMyProfile("""{"code":0,"data":null}""") is MyProfileResult.Unsupported)
        assertTrue(parseMyProfile("""{"code":-352,"message":"风控校验失败"}""") is MyProfileResult.Unsupported)
        // 非 JSON（被网关拦了、返回 HTML）也不能崩
        assertTrue(parseMyProfile("<html>502 Bad Gateway</html>") is MyProfileResult.Unsupported)
    }

    @Test
    fun 昵称缺失时是空串而不是null也不编一个名字() {
        val json = """{"code":0,"data":{"isLogin":true,"mid":7,"face":"","uname":""}}"""
        val p = (parseMyProfile(json) as MyProfileResult.Ok).profile
        assertEquals("", p.name)
        // 界面据此显示「已登录的账号」。绝不能让解析层编一个"用户7" ——
        // 那看起来像真名，比空着更糟
        assertEquals(7L, p.mid)
    }

    // ------------------------------------------------------ nav/stat 计数

    @Test
    fun 计数_未登录时返回null而零返回三个零() {
        // ★ 实测：未登录是 -101 + **data=null**（和 nav 不一样，nav 那份 data 是有内容的）。
        //   null 和 0 在这里含义完全不同，混了就是给用户编结论。
        assertNull(parseMyStat("""{"code":-101,"message":"账号未登录","data":null}"""))
        assertNull(parseMyStat("""{"code":-352,"message":"风控校验失败"}"""))
        assertNull(parseMyStat("not json at all"))

        val zero = parseMyStat("""{"code":0,"data":{"mid":1,"following":0,"follower":0,"dynamic":0}}""")
        assertEquals(0L, zero?.following)
        assertEquals(0L, zero?.follower)
        assertEquals(0L, zero?.dynamic)
    }

    @Test
    fun 计数_正常的三个数读得出来() {
        val s = parseMyStat("""{"code":0,"data":{"mid":293793435,"following":128,"follower":56,"dynamic":12}}""")
        assertEquals(128L, s?.following)
        assertEquals(56L, s?.follower)
        assertEquals(12L, s?.dynamic)
    }

    @Test
    fun 计数_缺哪个字段哪个就是null_不拿零顶上() {
        // ★ 关键一条：只有 following 一个字段时，另外两个**必须**是 null。
        //   用 optLong(...,0) 的默认值顶上，屏幕上就会出现"粉丝 0"这种假结论。
        val s = parseMyStat("""{"code":0,"data":{"following":128}}""")
        assertEquals(128L, s?.following)
        assertNull(s?.follower)
        assertNull(s?.dynamic)
    }

    // ------------------------------------------------------ 界面上的那一行字

    @Test
    fun 计数格子取不到时显示两个横杠不是零() {
        // 这一行是 `?:` 写错就静默变假数据的地方，所以单独抽出来测
        assertEquals("--", statText(null))
        assertEquals("0", statText(0L))
        assertEquals("9999", statText(9999L))
        assertEquals("1.2万", statText(12_345L))
    }

    // ------------------------------------------------------ 顺手补的旧缺口

    @Test
    fun 头像地址统一升成https() {
        // `docs/15` 记过：B 站封面/头像会返回 `http://` 或 `//` 开头。
        // 明文地址在 Android 9+ 默认被拦，表现是"头像一片空白"。
        // 这个函数一直没单测，顺手补上（我的页整页就靠头像撑门面）。
        assertEquals("https://i2.hdslb.com/x.jpg", "http://i2.hdslb.com/x.jpg".fixedScheme())
        assertEquals("https://i2.hdslb.com/x.jpg", "//i2.hdslb.com/x.jpg".fixedScheme())
        assertEquals("https://i2.hdslb.com/x.jpg", "https://i2.hdslb.com/x.jpg".fixedScheme())
        assertEquals("", "".fixedScheme())
    }
}
