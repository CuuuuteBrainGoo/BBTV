package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.api.parseLiveAreaRooms
import top.bilitv.data.api.parseLiveAreas
import top.bilitv.data.api.parseLivePlayInfo
import top.bilitv.data.api.parseLiveRecommend
import top.bilitv.data.api.parseLiveRoomInfo
import top.bilitv.data.model.LiveRoom
import top.bilitv.data.model.LiveStatus
import top.bilitv.data.model.LiveStreamLine

/**
 * 直播接口的解析测试（2026-09-29）。
 *
 * ## 这一组钉的是什么
 *
 * 直播和前面所有页面有一个**根本区别：它换了域名**（`api.live.bilibili.com`），
 * 而且这一族的返回结构和点播那边**没有一处是共通的**。实测（`tools/probe_live.py`）
 * 之后，有四件事是"写错了就会静默出错、而且现象全都长一样"的：
 *
 * 1. **列表接口的 `data` 直接是数组**，没有 `list` / `items` / `archives` 这类外层名。
 *    照点播的习惯写 `itemsFrom(json, "list")` 会得到 0 条 ——
 *    界面只说"这个分区没人在播"，看不出是解析错了。
 * 2. **列表接口不返回 `live_status`**，所以角标是**推断**出来的
 *    （规则见 `Parsers.liveStatusOf`）。这一条是本组最贵的教训 —— 见下面
 *    「角标」那一节的测试。
 * 3. **房间信息是个以房间号为键的字典**（`data.by_room_ids`），不是数组。
 * 4. **取流地址的 `base_url` 在 `codec` 层，不在 `url_info` 层** ——
 *    读错层会得到空串。而且 `base_url` **自己以 `?` 结尾**，
 *    再补一个 `?` 会变成 `??expires=…`，CDN 直接 403。这两条都真踩过。
 *
 * ## ★ 这一组曾经"全绿但是错的"（2026-09-29）
 *
 * 第一版这里把 `roomid` / `online` 当成**字符串**抄（`"online":"515454"`），
 * 而实测它们是**数字**。更要命的是假数据里也**没放 `live_status`**，
 * 于是"角标恒为未开播"这个真 bug 一条都没拦住 —— 编译过、单测全绿、
 * 模拟器一看每张卡都挂着「未开播」。
 *
 * 教训：**假数据的字段类型和"有没有这个字段"都要照实测抄**。
 * 手写的 JSON 一旦跟真实响应长得不像，测试就只是在测自己。
 * 现在两边都补了：类型改数字（另留一条字符串容错），
 * 并单开「角标」一节把推断规则钉死。
 *
 * 另外把"选中哪条线路"的规则也钉住 —— 实测**只有 HLS + fMP4 那条稳**，
 * 选错的表现是"接口通了但画面出不来"。
 */
class LiveTest {

    // ------------------------------------------------------------ 推荐直播

    /**
     * 一条推荐直播。**字段照实测抄**（2026-09-29，`tools/probe_live.py`）：
     *
     * - `roomid` / `online` / `uid` / `area` / `short_id` 是 **JSON 数字**；
     * - `area` / `areaName` 是废字段（实测恒为 `0` / `""`）；
     * - ★ **没有 `live_status`** —— 这个接口不给。想要它出现在假数据里，
     *   得显式传 [liveStatus]（用来测"接口真给了就照收"那条路）。
     */
    private fun roomJson(
        roomId: String,
        title: String,
        uname: String,
        online: String,
        userCover: String = "https://i0.hdslb.com/bfs/live/user_$roomId.jpg",
        systemCover: String = "https://i0.hdslb.com/bfs/live/sys_$roomId.jpg",
        liveStatus: Int? = null,
    ): String = buildString {
        append("{\"area\":0,\"areaName\":\"\",")
        append("\"face\":\"https://i0.hdslb.com/bfs/face/$roomId.jpg\",")
        append("\"link\":\"/$roomId\",")
        append("\"online\":$online,")
        append("\"roomid\":$roomId,")
        append("\"short_id\":0,")
        if (liveStatus != null) append("\"live_status\":$liveStatus,")
        append("\"system_cover\":\"$systemCover\",")
        append("\"title\":\"$title\",")
        append("\"uid\":8739477,")
        append("\"uname\":\"$uname\",")
        append("\"user_cover\":\"$userCover\",")
        append("\"watched_show\":{\"switch\":true,\"num\":$online,")
        append("\"text_large\":\"${online}人看过\"}}")
    }

    @Test
    fun 推荐直播_data直接是数组不是对象() {
        // ★ 本组最重要的一条。
        //   点播那边的列表都在 data.xxx 里（list / item / archives / vlist…），
        //   直播这个**直接就是数组**。照老习惯写就会静默拿到空列表。
        val json = """
            {"code":0,"message":"ok","data":[
              ${roomJson("545068", "德云色  上分如喝汤", "老实憨厚的笑笑", "515454")},
              ${roomJson("1916509261", "豆包让我今天直播", "苏苏吃了没", "94205")}
            ]}
        """.trimIndent()

        val list = parseLiveRecommend(json)
        assertEquals(2, list.size)
        assertEquals(545068L, list[0].roomId)
        assertEquals("德云色  上分如喝汤", list[0].title)
        assertEquals("老实憨厚的笑笑", list[0].uname)
    }

    @Test
    fun 推荐直播_数字按实测是数字() {
        // 实测 `"online": 515454` 是 JSON 数字，不是字符串。
        // 早期这里写的是"全字符串"，是错的 —— 假数据跟着错，测试也就没用了
        val json = """{"code":0,"data":[${roomJson("1", "t", "u", "515454")}]}"""
        val list = parseLiveRecommend(json)
        assertEquals(515454L, list[0].online)
        assertEquals(8739477L, list[0].uid)
    }

    @Test
    fun 推荐直播_万一给了字符串也要读成数字() {
        // 容错那条路：老接口历史上有过字符串形态。读错的表现是
        // **人气永远是 0**，不报错、不崩 —— 只能靠这种测试拦
        val json = """
            {"code":0,"data":[{"roomid":"7","uid":"88","online":"515454",
              "title":"t","uname":"u","user_cover":"c"}]}
        """.trimIndent()
        val list = parseLiveRecommend(json)
        assertEquals(7L, list[0].roomId)
        assertEquals(515454L, list[0].online)
        assertEquals(88L, list[0].uid)
    }

    @Test
    fun 推荐直播_封面优先用主播传的再退回系统截帧() {
        // 实测三个候选：user_cover / cover / system_cover。
        // 取错顺序的后果是"封面看起来很怪"（系统截帧常常是黑屏或静止帧）
        val withUser = """{"code":0,"data":[${roomJson("7", "t", "u", "1")}]}"""
        assertEquals(
            "https://i0.hdslb.com/bfs/live/user_7.jpg",
            parseLiveRecommend(withUser)[0].cover,
        )

        val onlySystem = """
            {"code":0,"data":[{"roomid":"8","title":"t","uname":"u",
              "uid":"1","online":"1","face":"f",
              "system_cover":"https://i0.hdslb.com/bfs/live/sys_8.jpg",
              "user_cover":"","cover":""}]}
        """.trimIndent()
        assertEquals(
            "https://i0.hdslb.com/bfs/live/sys_8.jpg",
            parseLiveRecommend(onlySystem)[0].cover,
        )
    }

    @Test
    fun 推荐直播_被风控时解析成空列表() {
        // ★ 游客态实测：新接口 `/xlive/web-interface/v1/second/getList` 稳定 -352。
        //   我们因此改用老接口。这条测试保证"万一老接口也开始风控"时，
        //   解析层给的是空列表而不是异常。
        assertTrue(parseLiveRecommend("""{"code":-352,"message":"-352"}""").isEmpty())
        assertTrue(parseLiveRecommend("""{"code":0,"data":null}""").isEmpty())
    }

    @Test
    fun 推荐直播_房间号非法的条目被丢掉() {
        // roomid=0 会让网格里出现重复 key，LazyGrid 会直接崩
        val json = """{"code":0,"data":[{"roomid":"0","title":"幽灵","uname":"u"},{"roomid":"","title":"空"}]}"""
        assertTrue(parseLiveRecommend(json).isEmpty())
    }

    // ------------------------------------------------------------ 封面角标的开播状态
    //
    // ★ 这一节是 2026-09-29 模拟器实测抓出来的真 bug 换来的。
    //
    //   现象：直播页 30 张卡**每一张**都挂着「未开播」，可它们都有人气、
    //   点进去还能播。原因：两个列表接口都**不返回 `live_status`**，
    //   而解析层照老写法 `optInt("live_status", 0)` 兜底成 0 = 未开播。
    //   编译过、单测全绿 —— 上一版的假数据里也没这个字段，一条都没拦住。
    //
    //   修法：`live_status` 给了就用，没给就按 `online` 推断，`online` 也
    //   没数据就记「不知道」（界面不画角标，而不是画「未开播」）。

    @Test
    fun 角标_列表没给live_status时按人气判为在播() {
        // 推荐流实测 30/30 条 online > 0，且全都没有 live_status。
        // 如果没有这条推断，这一页的角标就是 100% 假的
        val json = """{"code":0,"data":[${roomJson("545068", "德云色", "笑笑", "506831")}]}"""
        val room = parseLiveRecommend(json)[0]
        assertEquals(LiveStatus.LIVING, room.liveStatus)
        assertTrue(room.isLiving)
    }

    @Test
    fun 角标_既没状态也没人气时报不知道而不是未开播() {
        // ★ 这条是"不许撒谎"的守门员。
        //   拿不到数据时 **不能** 说「未开播」—— 那是替接口下它没下的结论，
        //   而且会把还能看的房间挡在门外。UNKNOWN 的界面约定是「不画角标」。
        val json = """{"code":0,"data":[{"roomid":9,"title":"t","uname":"u","online":0}]}"""
        val room = parseLiveRecommend(json)[0]
        assertEquals(LiveStatus.UNKNOWN, room.liveStatus)
        assertFalse(room.isLiving)
    }

    @Test
    fun 角标_接口给了live_status就照收不推断() {
        // 轮播（2）会有人气，但**不是**直播。给了字段就必须听字段的，
        // 不能因为 online > 0 就一律说成"直播中"
        val rerun = """{"code":0,"data":[${roomJson("5", "t", "u", "1234", liveStatus = 2)}]}"""
        assertEquals(LiveStatus.RERUN, parseLiveRecommend(rerun)[0].liveStatus)

        val offline = """{"code":0,"data":[${roomJson("6", "t", "u", "0", liveStatus = 0)}]}"""
        assertEquals(LiveStatus.OFFLINE, parseLiveRecommend(offline)[0].liveStatus)

        // 字段在、值是 1 → 直播中（不依赖 online）
        val living = """{"code":0,"data":[${roomJson("7", "t", "u", "0", liveStatus = 1)}]}"""
        assertTrue(parseLiveRecommend(living)[0].isLiving)
    }

    @Test
    fun 角标_默认值是不知道不是未开播() {
        // `LiveRoom` 直接构造时（不是从 JSON 来）也不能默认成"未开播"。
        // 默认值选错，任何一处漏赋值的调用点都会跟着撒谎
        assertEquals(LiveStatus.UNKNOWN, LiveRoom(1L, "t", "u").liveStatus)
    }

    // ------------------------------------------------------------ 分区直播

    @Test
    fun 分区直播_带出大区和子区名() {
        // 分区列表比推荐流多的就是这两个字段：`parent_name`（大区）和
        // `area_name`（子区）。它们显示在卡片第三行「主播 · 网游·吃鸡行动」
        // ★ 注意它**同样没有 `live_status`** —— 开播状态走人气推断
        val json = """
            {"code":0,"message":"success","data":[
              {"roomid":1916509261,"uid":3546718645783015,
               "title":"豆包让我今天直播  说会天降大哥","uname":"苏苏吃了没",
               "online":94205,"user_cover":"//i0.hdslb.com/a.jpg",
               "cover":"//i0.hdslb.com/a.jpg","face":"//i1.hdslb.com/f.jpg",
               "parent_id":1,"parent_name":"娱乐","area_id":145,"area_name":"颜值"}
            ]}
        """.trimIndent()

        val list = parseLiveAreaRooms(json)
        assertEquals(1, list.size)
        assertEquals("苏苏吃了没", list[0].uname)
        assertEquals("娱乐", list[0].parentAreaName)
        assertEquals("颜值", list[0].areaName)
        assertEquals(94205L, list[0].online)
        assertEquals(LiveStatus.LIVING, list[0].liveStatus)
    }

    // ------------------------------------------------------------ 分区表

    @Test
    fun 分区表_两层结构都能读出来() {
        // 大区里套子区。列表接口按**子区 id** 过滤，所以子区 id 必须留下来 ——
        // 只存名字的话，将来想按子区筛就没得用了
        val json = """
            {"code":0,"message":"success","data":[
              {"id":"2","name":"网游","list":[
                {"id":"86","parent_id":"2","name":"英雄联盟","pic":"//i0.hdslb.com/a.png"},
                {"id":"80","parent_id":"2","name":"吃鸡行动","pic":"//i0.hdslb.com/b.png"}
              ]},
              {"id":"1","name":"娱乐","list":[
                {"id":"145","parent_id":"1","name":"颜值","pic":"//i0.hdslb.com/c.png"}
              ]}
            ]}
        """.trimIndent()

        val areas = parseLiveAreas(json)
        assertEquals(2, areas.size)
        assertEquals("网游", areas[0].name)
        assertEquals(2, areas[0].subs.size)
        assertEquals(86, areas[0].subs[0].id)
        assertEquals("英雄联盟", areas[0].subs[0].name)
        assertEquals(1, areas[1].subs.size)
        assertEquals(145, areas[1].subs[0].id)
    }

    @Test
    fun 分区表_没有子区的大区仍然保留() {
        // 大区本身是有效的筛选条件（传 parent_area_id 不传 area_id）
        val json = """{"code":0,"data":[{"id":"5","name":"电台","list":[]}]}"""
        val areas = parseLiveAreas(json)
        assertEquals(1, areas.size)
        assertEquals("电台", areas[0].name)
        assertTrue(areas[0].subs.isEmpty())
    }

    // ------------------------------------------------------------ 房间信息

    /**
     * 房间信息。★ 结构是**以房间号为键的字典**，不是数组 ——
     * 照列表接口的习惯写 `arr.optJSONObject(0)` 会得到 null。
     */
    private val roomBaseJson = """
        {"code":0,"message":"OK","data":{
          "by_uids":{},
          "by_room_ids":{
            "545068":{
              "room_id":"545068","uid":"8739477","area_id":"80",
              "live_status":"1","live_url":"https://live.bilibili.com/545068",
              "parent_area_id":"2","title":"德云色  上分如喝汤",
              "parent_area_name":"网游","area_name":"吃鸡行动",
              "live_time":"2026-09-28 19:20:23","description":"","tags":"",
              "attention":"2873680","online":"515454","short_id":"7777",
              "uname":"老实憨厚的笑笑",
              "cover":"https://i0.hdslb.com/bfs/live/cover.jpg",
              "background":"https://i0.hdslb.com/bfs/live/bg.jpg",
              "join_slide":"1","live_id":"737437896084574508",
              "lock_status":"0","hidden_status":"0","is_encrypted":"False"
            }
          }
        }}
    """.trimIndent()

    @Test
    fun 房间信息_从以房间号为键的字典里取() {
        val info = parseLiveRoomInfo(roomBaseJson)
        assertNotNull(info)
        assertEquals(545068L, info!!.roomId)
        assertEquals("德云色  上分如喝汤", info.title)
        assertEquals("老实憨厚的笑笑", info.uname)
        assertEquals(515454L, info.online)
        assertEquals("网游", info.parentAreaName)
        assertEquals("吃鸡行动", info.areaName)
    }

    @Test
    fun 房间信息_开播状态是字符串1也要认成开播() {
        // `"live_status":"1"` —— 直接 `== 1` 比较会得到 false
        val info = parseLiveRoomInfo(roomBaseJson)
        assertTrue(info!!.isLiving)
        assertEquals(LiveStatus.LIVING, info.liveStatus)
    }

    @Test
    fun 房间信息_取不到时返回null而不是编一个空房间() {
        // ★ null 的含义是"拿不到"，**不是**"这个直播间不存在"。
        //   界面据此说"拿不到房间信息"，而不是编一句"直播间不存在" ——
        //   后者在"接口变了"的时候是彻底的谎话
        assertNull(parseLiveRoomInfo("""{"code":-352,"message":"-352"}"""))
        assertNull(parseLiveRoomInfo("""{"code":0,"data":{"by_room_ids":{}}}"""))
        assertNull(parseLiveRoomInfo("""{"code":0,"data":null}"""))
    }

    // ------------------------------------------------------------ 取流（本组重点）

    /**
     * 取流响应。**结构照实测的三层嵌套写**。
     *
     * 关键点：`base_url` 挂在 **`codec`** 上（不在 `url_info` 上），
     * 而且它**自己以 `?` 结尾**。
     */
    private val playInfoJson = """
        {"code":0,"message":"OK","data":{
          "room_id":545068,"uid":8739477,"live_status":1,
          "playurl_info":{
            "playurl":{
              "g_qn_desc":[{"qn":10000,"desc":"原画"},{"qn":400,"desc":"蓝光"},{"qn":250,"desc":"高清"},{"qn":123456,"desc":"未支持"}],
              "cid":613333541,
              "stream":[
                {"protocol_name":"http_stream","format":[
                  {"format_name":"flv","codec":[
                    {"codec_name":"avc","current_qn":250,"accept_qn":[10000,400,250],
                     "base_url":"/live-bvc/271129/live_x_2500.flv?",
                     "url_info":[{"host":"https://d1--cn-gotcha04.bilivideo.com",
                                  "extra":"expires=1790632950&oi=613333541&trid=abc","stream_ttl":3600}]}
                  ]}
                ]},
                {"protocol_name":"http_hls","format":[
                  {"format_name":"ts","codec":[
                    {"codec_name":"avc","current_qn":250,"accept_qn":[10000,400,250],
                     "base_url":"/live-bvc/376034/live_x_2500.m3u8?",
                     "url_info":[{"host":"https://d1--cn-gotcha104.bilivideo.com",
                                  "extra":"expires=1790632950&trid=ts1","stream_ttl":3600}]}
                  ]},
                  {"format_name":"fmp4","codec":[
                    {"codec_name":"avc","current_qn":250,"accept_qn":[10000,400,250],
                     "base_url":"/live-bvc/519769/live_x_2500/index.m3u8?",
                     "url_info":[{"host":"https://d1--cn-gotcha208.bilivideo.com",
                                  "extra":"expires=1790632950&trid=fmp4avc","stream_ttl":3600}]},
                    {"codec_name":"hevc","current_qn":250,"accept_qn":[10000,400,250],
                     "base_url":"/live-bvc/519769/live_x_2500/index.m3u8?",
                     "url_info":[{"host":"https://d1--cn-gotcha208.bilivideo.com",
                                  "extra":"expires=1790632950&trid=fmp4hevc","stream_ttl":3600}]}
                  ]}
                ]}
              ]
            }
          }
        }}
    """.trimIndent()

    @Test
    fun 取流_六条线路都解析出来() {
        val info = parseLivePlayInfo(playInfoJson)
        assertNotNull(info)
        assertEquals(545068L, info!!.roomId)
        assertEquals(LiveStatus.LIVING, info.liveStatus)
        assertEquals(4, info.lines.size)   // 1 flv + 1 ts + 2 fmp4
        assertEquals(mapOf(10000 to "原画", 400 to "蓝光", 250 to "高清"), info.qualities)
    }

    @Test
    fun 取流_地址是host加codec层base加extra三段拼出来的() {
        // ★ 三条一起钉，缺一条地址就是错的：
        //   1. base_url 在 codec 层（读 url_info 层会得到空串）
        //   2. base_url 自带 `?`，所以拼出来只能有**一个**问号
        //   3. extra 要原样接在后面（里面是时效签名，缺了会 403）
        val info = parseLivePlayInfo(playInfoJson)!!
        val flv = info.lines.first { it.protocol == "http_stream" }
        assertEquals(1, flv.urls.size)
        assertEquals(
            "https://d1--cn-gotcha04.bilivideo.com/live-bvc/271129/live_x_2500.flv" +
                "?expires=1790632950&oi=613333541&trid=abc",
            flv.urls[0],
        )

        // 整条链路上不能出现 `??` —— 实测那样会 403
        for (line in info.lines) {
            for (url in line.urls) {
                assertFalse("地址里出现了双问号：$url", url.contains("??"))
            }
        }
    }

    @Test
    fun 取流_优先选HLS加fmp4() {
        // ★ 实测结论：`http_hls` + `fmp4` 是唯一在本机网络下稳定可播的组合。
        //   flv 那条走 http_stream，实测本机到该 CDN 域名直接 20 秒超时。
        val line = parseLivePlayInfo(playInfoJson)!!.preferredLine()!!
        assertTrue(line.isHls)
        assertEquals("fmp4", line.format)
        assertTrue(line.isPreferred)
    }

    @Test
    fun 取流_同一档里优先HEVC() {
        // 目标设备（鸿鹄 818）的 HEVC 硬解比 AVC 强。直播只有 250 一档清晰度，
        // 不能像点播那样靠降清晰度绕开，所以编码选对更重要
        val line = parseLivePlayInfo(playInfoJson)!!.preferredLine()!!
        assertEquals("hevc", line.codec)
        assertTrue(line.isHevc)
    }

    @Test
    fun 取流_没有fmp4时退到ts() {
        val onlyTs = """
            {"code":0,"data":{"room_id":1,"live_status":1,"playurl_info":{"playurl":{
              "stream":[{"protocol_name":"http_hls","format":[
                {"format_name":"ts","codec":[
                  {"codec_name":"avc","current_qn":250,
                   "base_url":"/live-bvc/1/a.m3u8?",
                   "url_info":[{"host":"https://h","extra":"expires=1"}]}
                ]}
              ]}]
            }}}}
        """.trimIndent()
        val line = parseLivePlayInfo(onlyTs)!!.preferredLine()!!
        assertEquals("ts", line.format)
    }

    @Test
    fun 取流_没开播时playurl_info为null要给空线路而不是null() {
        // ★ 这处区分很要紧：
        //   - 返回 LivePlayInfo（lines 空、liveStatus=2）→ 界面说"主播还没开播"
        //   - 返回 null → 界面说"拿不到直播地址（接口可能变了）"
        //   两句话让用户做的事完全不同。实测 room_id=1 就是这种人不在的状态。
        val json = """
            {"code":0,"message":"OK","data":{
              "room_id":1,"short_id":0,"uid":1,"live_status":2,
              "playurl_info":null,"all_special_types":[],
              "official_type":0,"risk_with_delay":0
            }}
        """.trimIndent()

        val info = parseLivePlayInfo(json)
        assertNotNull(info)
        assertEquals(LiveStatus.RERUN, info!!.liveStatus)
        assertTrue(info.lines.isEmpty())
        assertNull(info.preferredLine())
    }

    @Test
    fun 取流_响应不可解析时才返回null() {
        assertNull(parseLivePlayInfo("""{"code":-352,"message":"-352"}"""))
        assertNull(parseLivePlayInfo("""{"code":0,"data":null}"""))
    }

    @Test
    fun 取流_base没有问号时也能拼对() {
        // 兜底：现在实测 base_url 自带 `?`，但万一哪天服务端改了形态，
        // 拼接逻辑要自己补问号，而不是拼出一个 `...m3u8expires=...` 的怪地址
        val json = """
            {"code":0,"data":{"room_id":1,"live_status":1,"playurl_info":{"playurl":{
              "stream":[{"protocol_name":"http_hls","format":[
                {"format_name":"fmp4","codec":[
                  {"codec_name":"avc","current_qn":250,
                   "base_url":"/live-bvc/1/index.m3u8",
                   "url_info":[{"host":"https://h","extra":"expires=1&trid=x"}]}
                ]}
              ]}]
            }}}}
        """.trimIndent()
        assertEquals(
            "https://h/live-bvc/1/index.m3u8?expires=1&trid=x",
            parseLivePlayInfo(json)!!.lines[0].urls[0],
        )
    }

    @Test
    fun 线路_协议和编码的判定() {
        // 这两个判定是 `preferredLine()` 的地基，单独钉一条，
        // 免得哪天把 `http_hls` 写成 `hls` 而没人发现
        val hls = LiveStreamLine("http_hls", "fmp4", "hevc", 250, listOf("u"))
        assertTrue(hls.isHls)
        assertTrue(hls.isHevc)
        assertTrue(hls.isPreferred)

        val flv = LiveStreamLine("http_stream", "flv", "avc", 250, listOf("u"))
        assertFalse(flv.isHls)
        assertFalse(flv.isHevc)
        assertFalse(flv.isPreferred)

        // `hvc1` 也是 HEVC（和点播那边 DashStream.isHevc 同一套判据）
        assertTrue(LiveStreamLine("http_hls", "fmp4", "hvc1", 250, listOf("u")).isHevc)
    }

    @Test
    fun 取流_把实测的QN降级现象钉住() {
        // 游客态申请 qn=10000（原画）会被降级成 250。`accept_qn` 里仍然能看到
        // 10000/400 —— 这不是 bug，是"登录后才给你"。钉一条，免得后面有人
        // 看到 current_qn=250 就去改请求参数（改了也没用）
        val info = parseLivePlayInfo(playInfoJson)!!
        assertTrue(info.lines.all { it.qn == 250 })
    }
}
