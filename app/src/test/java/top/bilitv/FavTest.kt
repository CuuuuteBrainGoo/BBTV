package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.api.parseFavFolders
import top.bilitv.data.api.parseFavResources

/**
 * 收藏页两个接口的解析测试（2026-09-30）。
 *
 * ## 为什么必须钉住这一组
 *
 * 这两个接口的响应形状**和热门 / 新投稿那套完全不一样**，而第一版图省事
 * 直接复用了通用解析器 —— **登录态真机上踩到了**：
 *
 * ```
 * 收藏夹内容页：4 列卡片只有标题和时长，封面整片黑、UP 主和播放量全空
 * ```
 *
 * 原因就是字段名对不上（`pic` vs `cover`、`owner.name` vs `upper.name`、
 * `stat` vs `cnt_info`、`pubdate` vs `pubtime`）。
 *
 * **这类错误最阴的地方是它不报错**：页面有内容、布局正常、不崩溃、
 * 标题还显示得挺对（因为 `title` / `duration` 碰巧同名）——
 * 看上去像"网不好图没加载出来"，实际是**四个字段一个都没读对**。
 *
 * 所以这里用**收藏夹的真实响应形状**（照实测抄的字段名）钉死映射关系：
 * 以后谁再"顺手复用一下通用解析器"，这几条会立刻红。
 */
class FavTest {

    /** 照 `/x/v3/fav/resource/list` 实测形状抄的样本。 */
    private val mediasJson = """
        {"code":0,"message":"0","ttl":1,"data":{"medias":[
          {"id":123456,"type":2,"title":"【合集】某人的调试实录","cover":"http://i0.hdslb.com/bfs/archive/aaa.jpg",
           "intro":"","page":1,"duration":2991,"upper":{"mid":999,"name":"某UP","face":"http://i0.hdslb.com/x.jpg"},
           "cnt_info":{"collect":12,"play":407900,"danmaku":332},"pubtime":1759000000,"bvid":"BV1xx411c7mD"},
          {"id":123457,"type":2,"title":"第二个视频","cover":"http://i0.hdslb.com/bfs/archive/bbb.jpg",
           "duration":141,"upper":{"mid":999,"name":"某UP","face":""},
           "cnt_info":{"collect":0,"play":86000,"danmaku":283},"pubtime":1759000001,"bvid":"BV1yy411c7mE"},
          {"id":123458,"type":2,"title":"已失效的稿件","cover":"","duration":0,
           "upper":{"mid":999,"name":"某UP"},"cnt_info":{},"pubtime":0,"bvid":""}
        ]}}
    """.trimIndent()

    @Test
    fun `收藏夹内容 封面读的是 cover 不是 pic`() {
        val list = parseFavResources(mediasJson)
        assertEquals(2, list.size)          // 第三条没有 bvid，被丢掉
        assertEquals("http://i0.hdslb.com/bfs/archive/aaa.jpg", list[0].cover)
        assertEquals("http://i0.hdslb.com/bfs/archive/bbb.jpg", list[1].cover)
    }

    @Test
    fun `收藏夹内容 UP主读的是 upper_name`() {
        val list = parseFavResources(mediasJson)
        assertEquals("某UP", list[0].ownerName)
        assertEquals("某UP", list[1].ownerName)
    }

    @Test
    fun `收藏夹内容 播放量弹幕读的是 cnt_info`() {
        val list = parseFavResources(mediasJson)
        assertEquals(407900L, list[0].viewCount)
        assertEquals(332L, list[0].danmakuCount)
        assertEquals(86000L, list[1].viewCount)
        assertEquals(283L, list[1].danmakuCount)
    }

    @Test
    fun `收藏夹内容 发布时间读的是 pubtime`() {
        val list = parseFavResources(mediasJson)
        assertEquals(1759000000L, list[0].pubDateSec)
        assertEquals(1759000001L, list[1].pubDateSec)
    }

    @Test
    fun `收藏夹内容 时长和标题正常`() {
        val list = parseFavResources(mediasJson)
        assertEquals("【合集】某人的调试实录", list[0].title)
        assertEquals(2991, list[0].durationSec)
        assertEquals(141, list[1].durationSec)
    }

    @Test
    fun `收藏夹内容 没有 bvid 的失效稿件被跳过`() {
        val list = parseFavResources(mediasJson)
        assertTrue("失效稿件不该出现在列表里", list.none { it.title == "已失效的稿件" })
    }

    /**
     * 旧的兜底字段仍然认。
     *
     * B 站同一接口在不同灰度下字段名漂移过，多读一条的代价是零；
     * 只认一条的话下次漂移又要重踩一遍。这条测试保证"兜底"不是写了个摆设。
     */
    @Test
    fun `收藏夹内容 pic 和 owner 作为兜底仍然认`() {
        val legacy = """
            {"code":0,"data":{"medias":[
              {"bvid":"BV1zz411c7mF","title":"老形状","pic":"http://i0.hdslb.com/old.jpg",
               "owner":{"name":"老UP"},"duration":60,"stat":{"view":10,"danmaku":2},"pubdate":100}
            ]}}
        """.trimIndent()
        val list = parseFavResources(legacy)
        assertEquals(1, list.size)
        assertEquals("http://i0.hdslb.com/old.jpg", list[0].cover)
        assertEquals("老UP", list[0].ownerName)
        assertEquals(10L, list[0].viewCount)
        assertEquals(100L, list[0].pubDateSec)
    }

    // ---------------------------------------------------------------- 收藏夹列表

    @Test
    fun `收藏夹列表 从 list 数组读出 id 标题和条数`() {
        val json = """
            {"code":0,"data":{"count":4,"list":[
              {"id":111,"title":"默认收藏夹","media_count":318},
              {"id":222,"title":"smap 动漫歌谣祭","media_count":3},
              {"id":333,"title":"one ok rock","media_count":11}
            ]}}
        """.trimIndent()
        val folders = parseFavFolders(json)
        assertEquals(3, folders.size)
        assertEquals(111L, folders[0].id)
        assertEquals("默认收藏夹", folders[0].title)
        assertEquals(318, folders[0].count)
        assertEquals(11, folders[2].count)
    }

    /**
     * ★ 条数字段名也要钉住。
     *
     * 界面上「默认收藏夹 **318 条**」全靠它。原来那版读不到总数，
     * 退化成显示"已加载 19 条"，用户会以为收藏丢了。
     */
    @Test
    fun `收藏夹列表 条数读的是 media_count`() {
        val json = """{"code":0,"data":{"list":[{"id":1,"title":"A","media_count":7}]}}"""
        assertEquals(7, parseFavFolders(json)[0].count)
    }

    @Test
    fun `收藏夹列表 没有 list 时返回空而不是崩`() {
        assertEquals(0, parseFavFolders("""{"code":-101,"message":"账号未登录"}""").size)
        assertEquals(0, parseFavFolders("""{"code":0,"data":null}""").size)
    }
}
