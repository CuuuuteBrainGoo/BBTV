package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.api.parseDynamicFeed
import top.bilitv.data.model.CODE_NOT_LOGGED_IN
import top.bilitv.data.model.CODE_UNPARSEABLE
import top.bilitv.data.model.DynamicItem
import top.bilitv.data.model.mergeDynamicItems
import top.bilitv.ui.components.dynamicAuthorSuffix
import top.bilitv.ui.components.dynamicStatLine

/**
 * 动态页的解析与文案测试（2026-09-29）。
 *
 * ## ⚠️ 先说清楚这一组**测不出**什么
 *
 * 动态流 `/x/polymer/web-dynamic/v1/feed/all` 是**登录门禁**接口：
 * 游客态一律 `-101`（实测见 `tools/probe_dynamic.py`）。所以这里的 JSON
 * **照的是官方接口文档的字段名，不是实测抓下来的响应** ——
 * 这一组能钉住的是**我们自己写的逻辑**（筛什么、丢什么、缺字段怎么办），
 * **不能**证明字段名和线上一致。登录后要拿真实响应再核一遍。
 *
 * 换句话说：这些用例的价值在于"改坏了会被发现"，不在于"证明它是对的"。
 * 把它们当回归网，不要当认可书。
 *
 * ## 这一组钉的四件事
 *
 * 1. **只有 `MAJOR_TYPE_ARCHIVE` 能活下来** —— 其余类型（图文/纯文字/专栏/音频）
 *    一律丢掉，因为遥控器上点它们没反应（`docs/18` §1.3 否掉"关注页做成时间流"
 *    就是同一个理由）。缺 `bvid` 的视频也丢。
 * 2. **转发要从 `orig` 里取视频**，作者取**转发者**，并标 `forwarded`。
 * 3. **计数缺失时必须记 `null`，不许是 0** —— 这是直播页角标踩过的坑
 *    （`docs/19` §2：兜底值被当结论用，30 张卡 30 句谎话）。
 * 4. **`-101` 必须活到界面层**（`code` 原样带上来），因为"登录过期"
 *    和"网络不通"要给两句不同的话。
 */
class DynamicTest {
    @Test fun overlappingPagesAreUniqueAndNewestFirst() {
        fun page(vararg entries: String) = parseDynamicFeed(
            """{"code":0,"data":{"items":[${entries.joinToString(",")}]}}""").items
        val first = page(videoItem("old", pubTs = "100"), videoItem("new", pubTs = "300"))
        val next = page(videoItem("old", pubTs = "100"), videoItem("middle", pubTs = "200"))
        val merged = mergeDynamicItems(first, next)
        assertEquals(listOf("new", "middle", "old"), merged.map { it.id })
        assertEquals(merged, mergeDynamicItems(merged, next))
    }

    // ------------------------------------------------------------ 造数据

    /** 一条视频动态，字段名照官方文档 */
    private fun videoItem(
        id: String,
        bvid: String = "BV1vK411U7de",
        title: String = "标题$id",
        action: String = "投稿了视频",
        pubTs: String = "1667129967",
        stat: String = """{"danmaku":"46","play":"123456"}""",
        mstat: String = """{"comment":{"count":4},"forward":{"count":1},"like":{"count":54}}""",
    ) = """
        {
          "id_str": "$id",
          "type": "DYNAMIC_TYPE_AV",
          "visible": true,
          "basic": {"comment_id_str": "474518278", "comment_type": 1},
          "modules": {
            "module_author": {
              "mid": 2062760,
              "name": "某个UP$id",
              "face": "https://i2.hdslb.com/bfs/face/$id.jpg",
              "pub_action": "$action",
              "pub_time": "3小时前",
              "pub_ts": $pubTs
            },
            "module_dynamic": {
              "desc": null,
              "major": {
                "type": "MAJOR_TYPE_ARCHIVE",
                "archive": {
                  "aid": "474518278",
                  "bvid": "$bvid",
                  "cover": "http://i1.hdslb.com/bfs/archive/$id.jpg",
                  "desc": "简介",
                  "disable_preview": 0,
                  "duration_text": "9:54:23",
                  "jump_url": "//www.bilibili.com/video/$bvid",
                  "stat": $stat,
                  "title": "$title",
                  "type": 1
                }
              }
            },
            "module_more": {"three_point_items": [{"label": "举报", "type": "THREE_POINT_REPORT"}]},
            "module_stat": $mstat
          }
        }
    """.trimIndent()

    /** 一条纯文字动态：有 desc、没有 major、也没有 orig */
    private fun wordItem(id: String) = """
        {
          "id_str": "$id",
          "type": "DYNAMIC_TYPE_WORD",
          "visible": true,
          "modules": {
            "module_author": {"mid": 9, "name": "文字UP", "pub_action": "", "pub_ts": 1667129967},
            "module_dynamic": {
              "desc": {"text": "今天天气不错", "rich_text_nodes": []},
              "major": null
            }
          }
        }
    """.trimIndent()

    /** 一条带图动态：major 是 DRAW */
    private fun drawItem(id: String) = """
        {
          "id_str": "$id",
          "type": "DYNAMIC_TYPE_DRAW",
          "modules": {
            "module_author": {"mid": 10, "name": "图UP", "pub_ts": 1667129967},
            "module_dynamic": {
              "desc": {"text": "九张图"},
              "major": {"type": "MAJOR_TYPE_DRAW", "draw": {"id": 1, "items": []}}
            }
          }
        }
    """.trimIndent()

    /** `major` 片段：视频 */
    private fun archiveMajor(bvid: String, title: String = "被转发的视频") = """
        {"type":"MAJOR_TYPE_ARCHIVE","archive":{"aid":"1","bvid":"$bvid",
         "cover":"//i0.hdslb.com/x.jpg","duration_text":"1:02",
         "title":"$title","stat":{"play":"9"}}}
    """.trimIndent()

    /** `major` 片段：图片 */
    private fun drawMajor() = """{"type":"MAJOR_TYPE_DRAW","draw":{"id":1,"items":[]}}"""

    /**
     * 一条转发动态：自己 `major=null`，主体在 `orig` 里。
     *
     * @param forwardDesc 转发语。传 `null` 表示"转发的时候没写话"（接口给 `desc: null`）。
     */
    private fun forwardItem(
        id: String,
        origMajor: String,
        forwardDesc: String? = "这个好看",
        origDesc: String? = "原视频的简介",
    ): String {
        val descJson = if (forwardDesc == null) "null" else """{"text": "$forwardDesc"}"""
        val origDescJson = if (origDesc == null) "null" else """{"text": "$origDesc"}"""
        return """
            {
              "id_str": "$id",
              "type": "DYNAMIC_TYPE_FORWARD",
              "modules": {
                "module_author": {
                  "mid": 7, "name": "转发的人$id",
                  "pub_action": "转发动态", "pub_ts": 1667129000
                },
                "module_dynamic": {
                  "desc": $descJson,
                  "major": null
                }
              },
              "orig": {
                "id_str": "orig-$id",
                "type": "DYNAMIC_TYPE_AV",
                "modules": {
                  "module_author": {"mid": 2062760, "name": "原作者", "pub_ts": 1667000000},
                  "module_dynamic": {
                    "desc": $origDescJson,
                    "major": $origMajor
                  }
                }
              }
            }
        """.trimIndent()
    }

    private fun feed(vararg items: String) = """
        {
          "code": 0, "message": "0", "ttl": 1,
          "data": {
            "has_more": true,
            "offset": "722805011403243538",
            "update_baseline": "722805011403243538",
            "update_num": 3,
            "items": [${items.joinToString(",")}]
          }
        }
    """.trimIndent()

    // ------------------------------------------------------------ 基本解析

    @Test
    fun 视频动态_字段一个个读对() {
        val f = parseDynamicFeed(feed(videoItem("1")))
        assertEquals(0, f.code)
        assertEquals(1, f.items.size)
        val it = f.items[0]
        assertEquals("1", it.id)
        assertEquals("BV1vK411U7de", it.bvid)
        assertEquals("标题1", it.title)
        assertEquals("9:54:23", it.durationText)
        assertEquals(2062760L, it.ownerMid)
        // ★ 作者名来自 `name`，**不是** `uname` —— 这个接口独一份的命名
        assertEquals("某个UP1", it.ownerName)
        assertEquals("投稿了视频", it.action)
        assertEquals(1667129967L, it.pubTs)
        assertEquals("http://i1.hdslb.com/bfs/archive/1.jpg", it.cover)
        assertFalse(it.forwarded)
        // `desc` 是 null → 正文空串
        assertEquals("", it.caption)
    }

    @Test
    fun 分页游标和hasMore_原样带出来() {
        val f = parseDynamicFeed(feed(videoItem("1")))
        assertEquals("722805011403243538", f.nextOffset)
        assertTrue(f.hasMore)
    }

    // ------------------------------------------------------------ 只留能播的

    @Test
    fun 非视频动态_一律丢掉() {
        val f = parseDynamicFeed(feed(videoItem("1"), wordItem("2"), drawItem("3")))
        // 三条里只有第一条能播
        assertEquals(1, f.items.size)
        assertEquals("BV1vK411U7de", f.items[0].bvid)
    }

    @Test
    fun 视频动态缺bvid_也丢掉_不显示成一张点不动的卡() {
        val broken = videoItem("1", bvid = "")
        val f = parseDynamicFeed(feed(videoItem("2"), broken))
        assertEquals(1, f.items.size)
        assertEquals("标题2", f.items[0].title)
    }

    @Test
    fun 一页全是非视频_结果是空列表但code仍是0() {
        // ★ 这个组合正是界面要说"最近没有能播的动态"（而不是"接口坏了"）的依据
        val f = parseDynamicFeed(feed(wordItem("1"), drawItem("2")))
        assertEquals(0, f.code)
        assertEquals(0, f.items.size)
    }

    // ------------------------------------------------------------ 转发

    @Test
    fun 转发动态_视频从orig里取_作者是转发者() {
        val f = parseDynamicFeed(feed(forwardItem("1", archiveMajor("BV1xx411c7mD"))))
        assertEquals(1, f.items.size)
        val it = f.items[0]
        assertEquals("BV1xx411c7mD", it.bvid)
        assertEquals("被转发的视频", it.title)
        // "这条是谁发的" = 转发者，不是原作者
        assertEquals("转发的人1", it.ownerName)
        assertEquals(7L, it.ownerMid)
        assertTrue(it.forwarded)
        // 转发语优先
        assertEquals("这个好看", it.caption)
    }

    @Test
    fun 转发没写话时_退回落用被转发那条的正文() {
        val f = parseDynamicFeed(feed(forwardItem("1", archiveMajor("BV1x"), forwardDesc = null)))
        assertEquals(1, f.items.size)
        assertEquals("原视频的简介", f.items[0].caption)
    }

    @Test
    fun 转发一条非视频_同样丢掉() {
        val f = parseDynamicFeed(feed(forwardItem("1", drawMajor())))
        assertEquals(0, f.items.size)
    }

    // ------------------------------------------------------------ 缺字段的纪律

    @Test
    fun 计数缺失_必须是null而不是0() {
        // 接口一个计数都没给（`stat` / `module_stat` 都缺）
        val f = parseDynamicFeed(feed(videoItem("1", stat = "{}", mstat = "{}")))
        assertEquals(1, f.items.size)
        val it = f.items[0]
        assertNull(it.play)
        assertNull(it.danmaku)
        assertNull(it.like)
        assertNull(it.comment)
        assertNull(it.forward)
    }

    @Test
    fun 计数是显式null_也是取不到() {
        val it = parseDynamicFeed(
            feed(videoItem("1", stat = """{"play": null, "danmaku": "7"}"""))
        ).items[0]
        assertNull(it.play)
        assertEquals(7L, it.danmaku)
    }

    @Test
    fun 计数是字符串数字_能读成数字() {
        // 接口把播放量给成字符串（官方文档示例就是 "1"，不是 1）
        val it = parseDynamicFeed(feed(videoItem("1"))).items[0]
        assertEquals(123456L, it.play)
        assertEquals(46L, it.danmaku)
        assertEquals(54L, it.like)
        assertEquals(4L, it.comment)
        assertEquals(1L, it.forward)
    }

    @Test
    fun 缺pub_ts_记null_界面就不显示时间() {
        val it = parseDynamicFeed(feed(videoItem("1", pubTs = "null"))).items[0]
        assertNull(it.pubTs)
    }

    @Test
    fun has_more缺失_当没有下一页_这是安全方向() {
        val noFlag = """
            {"code":0,"data":{"offset":"x","items":[${videoItem("1")}]}}
        """.trimIndent()
        val f = parseDynamicFeed(noFlag)
        assertEquals(1, f.items.size)
        assertFalse(f.hasMore)
    }

    // ------------------------------------------------------------ 错误路径

    @Test
    fun 未登录101_码要活到界面层() {
        val f = parseDynamicFeed("""{"code":-101,"message":"账号未登录","data":null}""")
        assertEquals(CODE_NOT_LOGGED_IN, f.code)
        assertEquals(0, f.items.size)
    }

    @Test
    fun 不是JSON_给一个我们自己编的码_和网络失败区分开() {
        val f = parseDynamicFeed("<html>风控拦截页</html>")
        assertEquals(CODE_UNPARSEABLE, f.code)
        assertEquals(0, f.items.size)
    }

    @Test
    fun data是null_不崩_也不编条目() {
        val f = parseDynamicFeed("""{"code":0,"data":null}""")
        assertEquals(0, f.items.size)
        assertFalse(f.hasMore)
    }

    // ------------------------------------------------------------ 界面文案（纯函数）

    private fun item(
        play: Long? = null,
        danmaku: Long? = null,
        like: Long? = null,
        comment: Long? = null,
        forward: Long? = null,
        action: String = "",
        pubTs: Long? = null,
    ) = DynamicItem(
        id = "1", bvid = "BV1", title = "t", cover = "", durationText = "",
        ownerMid = 0, ownerName = "n", ownerFace = "",
        action = action, pubTs = pubTs, caption = "", forwarded = false,
        play = play, danmaku = danmaku, like = like, comment = comment, forward = forward,
    )

    @Test
    fun 计数一行_一个都没有时返回空串_界面据此整行不画() {
        assertEquals("", dynamicStatLine(item()))
    }

    @Test
    fun 计数一行_只画拿到的那些_没拿到的一个字都不出现() {
        val line = dynamicStatLine(item(play = 12345, like = 67))
        assertEquals("播放 1.2万   ·   点赞 67", line)
        // 弹幕/评论/转发一个字都不许出现
        assertFalse(line.contains("弹幕"))
        assertFalse(line.contains("评论"))
        assertFalse(line.contains("转发"))
    }

    @Test
    fun 作者后缀_两个来源缺谁就少谁_不缺留孤零零的分隔符() {
        val now = 1667129967L + 3 * 3600  // 让"3小时前"能算出来
        assertEquals(
            "投稿了视频 · 3小时前",
            dynamicAuthorSuffix(item(action = "投稿了视频", pubTs = 1667129967L), now),
        )
        assertEquals("投稿了视频", dynamicAuthorSuffix(item(action = "投稿了视频"), now))
        assertEquals("3小时前", dynamicAuthorSuffix(item(pubTs = 1667129967L), now))
        assertEquals("", dynamicAuthorSuffix(item(), now))
    }
}
