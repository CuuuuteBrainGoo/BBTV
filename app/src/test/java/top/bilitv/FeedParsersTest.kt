package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.api.parsePgcIndex
import top.bilitv.data.api.parsePopular
import top.bilitv.data.api.parseRegionNewList
import top.bilitv.data.api.parseSearchVideoPage
import top.bilitv.data.api.parseWeeklyOne
import top.bilitv.data.api.parseWeeklySeries
import top.bilitv.ui.components.formatPubDate
import top.bilitv.ui.components.formatDuration

/**
 * 列表页数据源的解析测试。
 *
 * 为什么这些必须有单测：**解析失败和没网在界面上长得一模一样** ——
 * 都是一句「暂时没有内容」。真机上看到这句话时，无法区分是接口变了还是我写错了。
 * 所以把"接口返回长这样 → 应该解析出什么"钉死在测试里，
 * 接口一变测试先红，而不是等用户在电视上看到空页面。
 */
class FeedParsersTest {

    // ------------------------------------------------------------ 热门

    @Test
    fun 热门_读出bvid标题UP主和播放量() {
        val json = """
            {"code":0,"data":{"list":[
              {"aid":1,"bvid":"BV1nAh261Epy","pic":"http://i2.hdslb.com/a.jpg",
               "title":"《胸型审美类别》","duration":221,"pubdate":1790338084,"tname":"健身",
               "owner":{"mid":414682640,"name":"Joshua-Zhang221"},
               "stat":{"view":546312,"danmaku":434}},
              {"aid":2,"bvid":"","title":"没有bvid的条目"}
            ]}}
        """.trimIndent()
        val list = parsePopular(json)
        assertEquals(1, list.size)
        with(list[0]) {
            assertEquals("BV1nAh261Epy", bvid)
            assertEquals("《胸型审美类别》", title)
            assertEquals("Joshua-Zhang221", ownerName)
            assertEquals(221, durationSec)
            assertEquals(546312L, viewCount)
            assertEquals(434L, danmakuCount)
            assertEquals(1790338084L, pubDateSec)
            assertEquals("健身", tname)
        }
    }

    @Test
    fun 热门_非零code返回空列表() {
        assertTrue(parsePopular("""{"code":-352,"message":"-352"}""").isEmpty())
        assertTrue(parsePopular("不是 JSON").isEmpty())
    }

    // ------------------------------------------------------------ 分区最新

    @Test
    fun 分区最新_条目在archives字段里() {
        // 这是 newlist 和 popular 唯一的差别：外层容器叫 archives 不叫 list
        val json = """
            {"code":0,"data":{"archives":[
              {"bvid":"BV1Rma56EEGT","pic":"http://i0.hdslb.com/b.jpg","title":"新番动漫",
               "duration":300,"pubdate":1790000000,
               "owner":{"name":"某UP"},"stat":{"view":1234,"danmaku":56}}
            ],"page":{"count":0,"num":1,"size":2}}}
        """.trimIndent()
        val list = parseRegionNewList(json)
        assertEquals(1, list.size)
        assertEquals("BV1Rma56EEGT", list[0].bvid)
        assertEquals("某UP", list[0].ownerName)
        assertEquals(1234L, list[0].viewCount)
    }

    // ------------------------------------------------------------ 每周必看

    @Test
    fun 每周必看_期号列表() {
        val json = """
            {"code":0,"data":{"list":[
              {"number":392,"subject":"宏大交响琵琶曲","status":2,"name":"2026第392期 09.18 - 09.24"},
              {"number":391,"subject":"Re0但是学园生活","status":2,"name":"2026第391期"}
            ]}}
        """.trimIndent()
        val list = parseWeeklySeries(json)
        assertEquals(2, list.size)
        assertEquals(392, list[0].number)
        assertEquals("宏大交响琵琶曲", list[0].subject)
        assertEquals("2026第391期", list[1].name)
    }

    @Test
    fun 每周必看_期号列表跳过没有期号的条目() {
        val json = """{"code":0,"data":{"list":[{"subject":"没有期号"},{"number":392,"subject":"有期号"}]}}"""
        val list = parseWeeklySeries(json)
        assertEquals(1, list.size)
        assertEquals(392, list[0].number)
    }

    @Test
    fun 每周必看_当期视频列表() {
        val json = """
            {"code":0,"data":{"config":{"number":392},"reminder":"","list":[
              {"bvid":"BV1aa411c7dd","pic":"http://i0.hdslb.com/c.jpg","title":"琵琶曲",
               "duration":480,"pubdate":1790100000,"owner":{"name":"民乐UP"},
               "stat":{"view":999,"danmaku":12}}
            ]}}
        """.trimIndent()
        val list = parseWeeklyOne(json)
        assertEquals(1, list.size)
        assertEquals("BV1aa411c7dd", list[0].bvid)
        assertEquals("民乐UP", list[0].ownerName)
    }

    // ------------------------------------------------------------ PGC

    @Test
    fun PGC_评分与排序都是字符串不能当数字读() {
        val json = """
            {"code":0,"data":{"list":[
              {"season_id":48001,"media_id":22006270,
               "cover":"https://i0.hdslb.com/d.png","title":"剧场版 咒术回战 0",
               "subTitle":"爱与诅咒的物语","index_show":"全1话",
               "order":"1342.5万追番","order_type":"3","score":"8.8","badge":"大会员",
               "season_type":1,"first_ep":{"cover":"x","ep_id":826152},
               "link":"https://www.bilibili.com/bangumi/play/ss48001?theme=movie"}
            ]}}
        """.trimIndent()
        val list = parsePgcIndex(json)
        assertEquals(1, list.size)
        with(list[0]) {
            assertEquals(48001L, seasonId)
            assertEquals(826152L, epId)
            assertEquals("剧场版 咒术回战 0", title)
            assertEquals("爱与诅咒的物语", subtitle)
            assertEquals("全1话", indexShow)
            // 关键：这两个是字符串，转成数字会变成 0
            assertEquals("1342.5万追番", order)
            assertEquals("8.8", score)
            assertTrue(hasScore)
            assertEquals("大会员", badge)
        }
    }

    @Test
    fun PGC_没有season_id时从link里抠() {
        val json = """
            {"code":0,"data":{"list":[
              {"title":"只有链接","link":"https://www.bilibili.com/bangumi/play/ss9999","cover":"x"}
            ]}}
        """.trimIndent()
        val list = parsePgcIndex(json)
        assertEquals(1, list.size)
        assertEquals(9999L, list[0].seasonId)
    }

    @Test
    fun PGC_既没有seasonid也没有链接就跳过() {
        val json = """{"code":0,"data":{"list":[{"title":"什么都没有"},{"season_id":7,"title":"有id"}]}}"""
        val list = parsePgcIndex(json)
        assertEquals(1, list.size)
        assertEquals(7L, list[0].seasonId)
    }

    @Test
    fun PGC_评分是0或空不算有评分() {
        assertTrue(!parsePgcIndex("""{"code":0,"data":{"list":[{"season_id":1,"score":"0"}]}}""")[0].hasScore)
        assertTrue(!parsePgcIndex("""{"code":0,"data":{"list":[{"season_id":1,"score":""}]}}""")[0].hasScore)
        assertTrue(parsePgcIndex("""{"code":0,"data":{"list":[{"season_id":1,"score":"9.6"}]}}""")[0].hasScore)
    }

    // ------------------------------------------------------------ 搜索

    @Test
    fun 搜索_剥掉关键词高亮标签并且时长按字符串解析() {
        val json = """
            {"code":0,"data":{"seeder":"","result":[
              {"type":"video","bvid":"BV1xx411c7mD","pic":"//i0.hdslb.com/e.jpg",
               "title":"【<em class=\"keyword\">测试</em>】标题 &amp; 实体","author":"某UP",
               "duration":"04:47","play":12345,"video_review":678,"pubdate":1790000000,
               "typename":"生活"},
              {"type":"bili_user","title":"这是用户不是视频"},
              {"type":"video","bvid":"","title":"没有bvid"}
            ]}}
        """.trimIndent()
        val list = parseSearchVideoPage(json, 1).items
        assertEquals(1, list.size)
        with(list[0]) {
            assertEquals("BV1xx411c7mD", bvid)
            // 高亮标签必须剥掉，否则电视机上会显示 <em class="keyword">
            assertEquals("【测试】标题 & 实体", title)
            assertEquals(287, durationSec)   // 4*60 + 47
            assertEquals(12345L, viewCount)
            assertEquals(678L, danmakuCount)
        }
    }

    @Test
    fun 搜索_时长解析支持小时制() {
        assertEquals(3723, parseSearchVideoPage(
            """{"code":0,"data":{"result":[{"type":"video","bvid":"BV1","duration":"1:02:03"}]}}""", 1
        ).items[0].durationSec)
    }

    // ------------------------------------------------------------ 时间显示

    @Test
    fun 投稿时间按距离远近给不同粒度() {
        val now = 1_790_000_000L
        assertEquals("刚刚", formatPubDate(now - 10, now))
        assertEquals("5分钟前", formatPubDate(now - 300, now))
        assertEquals("3小时前", formatPubDate(now - 3 * 3600, now))
        assertEquals("昨天", formatPubDate(now - 30 * 3600, now))
        // 没有时间就返回空串，界面那一行会自动收起来
        assertEquals("", formatPubDate(0L, now))
        // 时钟不同步（发布时间在未来）也不显示负数
        assertEquals("", formatPubDate(now + 9999, now))
    }

    @Test
    fun 时长格式化的边界() {
        assertEquals("--:--", formatDuration(0))
        assertEquals("--:--", formatDuration(-5))
        assertEquals("00:59", formatDuration(59))
        assertEquals("01:00", formatDuration(60))
        assertEquals("1:00:00", formatDuration(3600))
    }
}
