package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.api.*
import top.bilitv.data.settings.PlaybackTuning

class PlayerCatalogueTest {
    @Test fun 真实合集与推荐结构不混淆分P且失败不冒充空列表() {
        val detail = parseVideoDetail("""{"code":0,"data":{"bvid":"BV1tLth6rE1n","pages":[{"cid":41459846527,"page":1,"part":"正片"}],
          "ugc_season":{"title":"孙中山","sections":[{"episodes":[
          {"bvid":"BV1tLth6rE1n","cid":41459846527,"title":"孙中山（上）","arc":{"pic":"cover"}},
          {"bvid":"BV1abcde1234","cid":12,"title":"孙中山（下）"}, {"bvid":"","aid":3}]}]}}}""")!!
        assertEquals(1, detail.pages.size)
        assertEquals("孙中山", detail.collectionTitle)
        assertEquals(2, detail.collection.size)
        assertEquals(41459846527L, detail.collection.first().cid)
        assertEquals("cover", detail.collection.first().cover)
        assertEquals(1, parseRelatedVideos("""{"code":0,"data":[{"bvid":"BV1HUao6UEXN","title":"孙中山"},{"bvid":"ad"}]}""")!!.size)
        assertNull(parseRelatedVideos("""{"code":-412}"""))
        assertTrue(parseRelatedVideos("""{"code":0,"data":[]}""")!!.isEmpty())
        assertEquals(31779L, parseRelatedSeasons("""{"code":0,"data":{"season":[{"season_id":31779,"title":"作品"}],"relates":[]}}""")!!.single().seasonId)
        assertEquals(PlaybackTuning.SideAction.RECOMMEND, PlaybackTuning.SideAction.of("future", PlaybackTuning.SideAction.RECOMMEND))
    }
}
