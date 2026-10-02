package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.api.parseHotSearch
import top.bilitv.data.api.parsePgcDetail
import top.bilitv.data.auth.TvLogin

/**
 * 登录签名 + PGC 详情 + 热搜 的解析测试。
 *
 * ## 为什么登录签名必须钉死一个基准值
 *
 * 签名错的时候，服务端返回的是 `code=-3 签名错误` —— 而这个错误和
 * "appkey 被换了""接口改协议了"从返回里**分不出来**。
 * 所以这里拿一个**真实被服务器接受过的签名**当基准：
 *
 * ```
 * ts=1790617979, appkey=4409e2ce8ffd12b8, local_id=0
 * → sign=628a92c461dc6f9a4162fcd08db19bcc
 * ```
 *
 * 这个值是 2026-09-29 用 `POST /x/passport-tv-login/qrcode/auth_code`
 * 实测拿到的（服务端返回 `code=0` 并给了二维码）。
 * 换成别的算法（比如给参数做 URL 编码、或者 ts 没参与排序），这个断言会立刻红。
 */
class LoginAndPgcTest {

    @Test fun 剧集短标题可能是版本名不能拼成集数() {
        // ep316548的实际响应：title="中文"、long_title为空；另一版本title="原版"。
        val detail = parsePgcDetail("""{"code":0,"result":{"season_id":31779,"title":"紫罗兰永恒花园外传",
          "episodes":[{"id":316548,"cid":160640640,"title":"中文","long_title":""},
          {"id":316301,"cid":160407915,"title":"原版","long_title":""}]}}""")!!
        assertEquals("中文", detail.episodes[0].displayName)
        assertEquals("原版", detail.episodes[1].displayName)
        assertEquals("紫罗兰永恒花园外传 · 中文", detail.playbackTitle(detail.episodes[0]))
    }

    @Test fun 频道榜单筛选Banner保留实际元数据() {
        val rank = top.bilitv.data.api.parsePgcIndex("""{"code":0,"data":{"list":[
          {"season_id":10,"title":"作品","rating":"9.8分","season_status":1,"ss_horizontal_cover":"wide","new_ep":{"index_show":"全1集"}},
          {"season_id":11,"badge":"大会员","season_status":1},{"season_id":12}]}}""")
        assertEquals("9.8", rank[0].score); assertEquals("wide", rank[0].backdrop)
        assertEquals("免费", rank[0].accessBadge); assertEquals("大会员", rank[1].accessBadge)
        assertEquals("", rank[2].accessBadge)
        val fields = top.bilitv.data.api.parsePgcFilters("""{"code":0,"data":{
          "order":[{"field":"2","name":"播放数量"}],
          "filter":[{"field":"release_date","name":"年份","values":[{"keyword":"2025-2026","name":"2025年"}]},
          {"field":"../../path","values":[]}]}}""")
        assertEquals(listOf("order", "release_date"), fields.map { it.id })
        assertEquals("2025-2026", fields.last().values.single().id)
        val banners = top.bilitv.data.api.parsePgcBanner("""<script>window.__INITIAL_STATE__ = {
          "modules":{"banner":{"items":[{"link":"https://www.bilibili.com/bangumi/play/ss10","title":"带}括号的标题","cover":"wide"},
          {"link":"https://www.bilibili.com/activity/1","title":"活动"}]}}};</script>""", 2)
        assertEquals(10L, banners.single().seasonId)
        assertEquals("带}括号的标题", banners.single().title)
    }

    @Test
    fun 登录签名与服务器接受的基准值一致() {
        val signed = TvLogin.sign(
            mapOf("appkey" to "4409e2ce8ffd12b8", "local_id" to "0"),
            ts = 1790617979L,
        )
        assertEquals("1790617979", signed["ts"])
        assertEquals("628a92c461dc6f9a4162fcd08db19bcc", signed["sign"])
    }

    @Test
    fun 登录签名的参数顺序不影响结果() {
        // 签名是按 key 排序后拼的，所以传 LinkedHashMap 还是打乱顺序都该得到同一个值。
        // 这条防的是"依赖调用方传参顺序"这种隐蔽 bug。
        val a = TvLogin.sign(mapOf("local_id" to "0", "appkey" to "4409e2ce8ffd12b8"), 1790617979L)
        val b = TvLogin.sign(mapOf("appkey" to "4409e2ce8ffd12b8", "local_id" to "0"), 1790617979L)
        assertEquals(a["sign"], b["sign"])
    }

    // ------------------------------------------------------------ PGC 详情

    @Test
    fun PGC详情_游客态返回空数据时解析成null() {
        // ★ 这是本组测试里最重要的一条：未登录时接口返回 code=0 但 data=null。
        //   如果解析器把它当成"解析失败"抛异常，界面就只能说"加载失败"，
        //   用户永远不知道自己该去登录。
        assertTrue(parsePgcDetail("""{"code":0,"message":"success","data":null}""") == null)
    }

    @Test
    fun PGC详情_剧集在result里并且集名用long_title() {
        val json = """
            {"code":0,"data":{"result":{
              "season_id":47836,"title":"鬼灭之刃 柱训练篇","cover":"http://i0.hdslb.com/a.jpg",
              "evaluate":"简介正文","subtitle":"副标题","rating":{"info":{"score":"9.6"}},
              "episodes":[
                {"ep_id":900001,"cid":111,"title":"1","long_title":"柱训练","cover":"c1","duration":1440000},
                {"id":900002,"cid":222,"title":"2","long_title":"","cover":"c2","duration":1440000}
              ]
            }}}
        """.trimIndent()
        val d = parsePgcDetail(json)!!
        assertEquals(47836L, d.seasonId)
        assertEquals("鬼灭之刃 柱训练篇", d.title)
        assertEquals("9.6", d.score)
        assertEquals(2, d.episodes.size)

        // title 是集数序号（"1"），long_title 才是集名 —— 用错就会出现"每一集都叫 1"
        assertEquals("柱训练", d.episodes[0].displayName)
        assertEquals(1440, d.episodes[0].durationSec)   // 接口给毫秒，这里存秒

        // 老形态用 id 而不是 ep_id；没有 long_title 时退回"第 N 集"
        assertEquals(900002L, d.episodes[1].epId)
        assertEquals("第 2 集", d.episodes[1].displayName)
    }

    @Test
    fun PGC详情_没有season_id就当解析失败() {
        assertTrue(parsePgcDetail("""{"code":0,"data":{"result":{"title":"没有季id"}}}""") == null)
    }

    // ------------------------------------------------------------ 热搜

    @Test
    fun 热搜_从三层结构里把关键词取出来() {
        val json = """
            {"code":0,"data":{"trending":{"title":"bilibili热搜","list":[
              {"keyword":"反T1联盟单曲","show_name":"反T1联盟单曲","heat_score":3060362},
              {"show_name":"只有show_name的项"},
              {"keyword":""},
              {"keyword":"第三个词"}
            ]}}}
        """.trimIndent()
        assertEquals(listOf("反T1联盟单曲", "只有show_name的项", "第三个词"), parseHotSearch(json))
    }

    @Test
    fun 热搜_限制条数并且坏数据返回空表() {
        val json = """{"code":0,"data":{"trending":{"list":[
            {"keyword":"a"},{"keyword":"b"},{"keyword":"c"}]}}}"""
        assertEquals(2, parseHotSearch(json, limit = 2).size)
        assertTrue(parseHotSearch("""{"code":-352}""").isEmpty())
        assertTrue(parseHotSearch("不是 JSON").isEmpty())
    }
}
