package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.api.parseFollowingCount
import top.bilitv.data.api.parseFollowingTotal
import top.bilitv.data.api.parseFollowings
import top.bilitv.data.api.parseUpVideoCount
import top.bilitv.data.api.parseUpVideos

/**
 * 关注页的解析测试（2026-09-29）。
 *
 * ## 这一组钉的是什么
 *
 * 关注页这三个接口的返回结构和前面那批**都不一样**，而且每一处差异
 * 失败时的表现都是同一句话：**「列表是空的」**。空列表在界面上和
 * "没登录""没关注任何人""接口挂了"长得一模一样，没法从现象倒推原因。
 *
 * 所以这里拿**真实响应形状**（照实测的字段写的 JSON）当基准，
 * 把三处最容易写错的地方钉死：
 *
 * 1. 投稿列表在 `data.list.vlist`（`list` 是对象，不是数组）；
 * 2. 投稿的时长字段 `length` 是 `"12:34"` **字符串**；
 * 3. 弹幕数字段叫 `video_review`，不叫 `danmaku`。
 *
 * 另外把 `/x/relation/stat` 的 `following` 解析也钉一条 ——
 * 它是关注页区分"真的没关注"和"接口出问题了"的唯一依据，
 * 返回 **null 和 0 的含义完全不同**，混了就会给用户说错话。
 */
class FollowTest {

    // ------------------------------------------------------------ 关注列表

    /** 一条关注记录，字段按 2026-09-29 实测的形状写 */
    private fun upJson(
        mid: Long,
        name: String,
        live: String = "{\"live_status\":0,\"roomid\":0}",
    ) = """
        {
          "mid": $mid,
          "attribute": 0,
          "uname": "$name",
          "face": "https://i2.hdslb.com/bfs/face/$mid.jpg",
          "sign": "签名$mid",
          "official_verify": {"type": 0, "desc": "bilibili 官方账号"},
          "vip": {"vipType": 0},
          "live": $live
        }
    """.trimIndent()

    @Test
    fun 关注列表_从list里读出UP主并带上认证说明() {
        val json = """
            {"code":0,"message":"0","data":{"list":[
              ${upJson(1, "甲")},
              ${upJson(2, "乙")}
            ],"total":2}}
        """.trimIndent()

        val list = parseFollowings(json)
        assertEquals(2, list.size)
        assertEquals(1L, list[0].mid)
        assertEquals("甲", list[0].name)
        assertEquals("签名1", list[0].sign)
        assertEquals("bilibili 官方账号", list[0].officialDesc)
        assertEquals(2L, parseFollowingTotal(json))
    }

    @Test
    fun 关注列表_正在直播时给出直播间号() {
        // live_status==1 才算在播。不给 roomid 就画不出"直播中"角标 ——
        // 而这个角标是关注页里唯一有时效性的信息。
        val json = """
            {"code":0,"data":{"list":[
              ${upJson(9, "在播", live = "{\"live_status\":1,\"roomid\":88888}")},
              ${upJson(10, "没播", live = "{\"live_status\":0,\"roomid\":88889}")}
            ],"total":2}}
        """.trimIndent()

        val list = parseFollowings(json)
        assertEquals(88888L, list[0].liveRoomId)
        // ★ 只有对象、live_status 为 0 → 必须当成"没在播"。
        //   见到 live 对象就画角标是个很容易犯的错，会把没播的人标成在播
        assertEquals(0L, list[1].liveRoomId)
    }

    @Test
    fun 关注列表_未登录返回101时解析成空列表而不是崩溃() {
        // ★ 这是未登录时的真实响应。解析器必须"给空列表"而不是抛异常 ——
        //   界面靠「列表空 + isLoggedIn()」两个信号一起判断该说什么话。
        val json = """{"code":-101,"message":"账号未登录","ttl":1,"data":null}"""
        assertTrue(parseFollowings(json).isEmpty())
        assertEquals(0L, parseFollowingTotal(json))
    }

    @Test
    fun 关注列表_mid非法的条目被丢掉() {
        // 幽灵条目（mid=0）会让 key 撞车 → 网格里出现重复 key 会直接崩
        val json = """{"code":0,"data":{"list":[${upJson(0, "幽灵")}],"total":1}}"""
        assertTrue(parseFollowings(json).isEmpty())
    }

    // ------------------------------------------------------------ 关注计数

    @Test
    fun 关注计数_取不到时返回null而零返回零() {
        // ★ 这两个值必须区分开：
        //   null = "这条证据也没拿到，别下结论"；0 = "确实一个都没关注"。
        //   把它们混成 0，就会在一个接口失败的时候骗用户说"你没有关注任何人"。
        assertNull(parseFollowingCount("""{"code":-352,"message":"风控校验失败"}"""))
        assertNull(parseFollowingCount("""{"code":0,"data":{}}"""))
        assertEquals(0L, parseFollowingCount("""{"code":0,"data":{"following":0}}"""))
        assertEquals(128L, parseFollowingCount("""{"code":0,"data":{"following":128}}"""))
    }

    // ------------------------------------------------------------ UP 主投稿

    /**
     * 一条投稿。**故意用真实的字段名和类型**：
     * `length` 是字符串、弹幕数叫 `video_review`、外层是 `list.vlist`。
     */
    private val videosJson = """
        {"code":0,"message":"0","data":{
          "list":{"tlist":{"1":{"tid":1,"name":"动画"}},
                  "vlist":[
                    {"comment":3,"typeid":1,"play":123456,"pic":"//i1.hdslb.com/x1.jpg",
                     "description":"","copyright":"1","title":"第一个视频",
                     "author":"某UP","mid":42,"created":1750000000,
                     "length":"12:34","video_review":789,"aid":111,
                     "bvid":"BV1xx411c7mD","is_pay":0},
                    {"comment":0,"typeid":1,"play":77,"pic":"http://i1.hdslb.com/x2.jpg",
                     "description":"","copyright":"1","title":"第二个视频",
                     "author":"某UP","mid":42,"created":1760000000,
                     "length":"1:02:03","video_review":0,"aid":222,
                     "bvid":"BV1yy411c7mE","is_pay":0}
                  ]},
          "page":{"count":328,"num":1,"size":20}
        }}
    """.trimIndent()

    @Test
    fun UP投稿_从list点vlist里读出视频() {
        // ★ 最容易写错的一处：`list` 是**对象**不是数组。
        //   写成 itemsFrom(json, "list") 会静默拿到空列表，界面上只说"他没有投稿"
        val list = parseUpVideos(videosJson)
        assertEquals(2, list.size)
        assertEquals("BV1xx411c7mD", list[0].bvid)
        assertEquals("第一个视频", list[0].title)
        assertEquals("某UP", list[0].ownerName)
        assertEquals(123456L, list[0].viewCount)
        assertEquals(1750000000L, list[0].pubDateSec)
        assertEquals(328L, parseUpVideoCount(videosJson))
    }

    @Test
    fun UP投稿_时长是时钟字符串要换算成秒() {
        // `length` 是 "12:34" 这种字符串，不是秒数。
        // 直接 optInt 会得到 0，卡片上就永远显示 "--:--"（很难注意到）
        val list = parseUpVideos(videosJson)
        assertEquals(12 * 60 + 34, list[0].durationSec)
        assertEquals(3600 + 2 * 60 + 3, list[1].durationSec)
    }

    @Test
    fun UP投稿_弹幕数在video_review字段() {
        // 和搜索接口一个毛病：弹幕数不叫 danmaku。读错就永远是 0
        val list = parseUpVideos(videosJson)
        assertEquals(789L, list[0].danmakuCount)
    }

    @Test
    fun UP投稿_被风控拒绝时解析成空列表() {
        // ★ 游客态的真实响应。之所以要单独钉一条，是因为它和"他真的没投稿"
        //   在界面上都是空列表 —— 实测见 tools/probe_follow.py：
        //   不带 buvid3 → -352；带了还是 -352，再试变 -412。
        assertTrue(parseUpVideos("""{"code":-352,"message":"风控校验失败","data":{"v_voucher":"x"}}""").isEmpty())
        assertEquals(0L, parseUpVideoCount("""{"code":-412,"message":"request was banned"}"""))
    }

    @Test
    fun UP投稿_bvid不合法的条目被丢掉() {
        val json = """
            {"code":0,"data":{"list":{"vlist":[
              {"bvid":"","title":"空"},
              {"bvid":"av123","title":"老格式"},
              {"bvid":"BV1zz411c7mF","title":"正常","length":"00:10"}
            ]},"page":{"count":1}}}
        """.trimIndent()
        val list = parseUpVideos(json)
        assertEquals(1, list.size)
        assertEquals("正常", list[0].title)
        assertEquals(10, list[0].durationSec)
    }
}
