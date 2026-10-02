package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.model.*
import top.bilitv.data.api.parsePgcDetail

class SubtitleTest {
    @Test fun 字幕协议与时间轴支持空隙快退重叠边界并拒绝外部地址() {
        val tracks = parseSubtitleTracks("""{"code":0,"data":{"subtitle":{"subtitles":[
          {"id_str":"9007199254740993","lan":"en","lan_doc":"English","subtitle_url":"//i0.hdslb.com/bfs/subtitle/1.json"},
          {"id":2,"lan":"zh-CN","lan_doc":"中文","subtitle_url":"https://aisubtitle.hdslb.com/subtitle/2.json"},
          {"id":3,"lan":"bad","subtitle_url":"https://hdslb.com.evil.test/subtitle.json"}]}}}""")
        assertEquals(2, tracks.size)
        assertEquals("9007199254740993", tracks.first().id)
        assertEquals("zh-CN", preferredSubtitle(tracks, "")!!.language)
        assertEquals("en", preferredSubtitle(tracks, "en")!!.language)
        assertNull(subtitleUrl("https://evil@i0.hdslb.com/subtitle.json"))
        assertNull(subtitleUrl("http://i0.hdslb.com/subtitle.json"))
        val timeline = SubtitleTimeline.parse("""{"body":[
          {"from":4,"to":5,"content":"末句"},{"from":1,"to":3,"content":"第一句"},
          {"from":2,"to":4,"content":"重叠"},{"from":-1,"to":2,"content":"无效"},
          {"from":8,"to":7,"content":"倒置"}]}""")
        assertEquals(3, timeline.cues.size)
        assertEquals("", timeline.textAt(500))
        assertEquals("第一句\n重叠", timeline.textAt(2500))
        assertEquals("末句", timeline.textAt(4000))
        assertEquals("", timeline.textAt(5000))
        assertEquals("第一句", timeline.textAt(1000))
        assertEquals("重叠", timeline.textAt(3500))
        val pgc = parsePgcDetail("""{"code":0,"data":{"season_id":31779,"episodes":[{"id":316548,"cid":160640640,"aid":62685015,"bvid":"BV1ct41177qK","title":"中文"}]}}""")!!
        assertEquals(62685015L, pgc.episodes.single().aid)
        assertEquals("BV1ct41177qK", pgc.episodes.single().bvid)
        assertThrows(IllegalStateException::class.java) { parseSubtitleTracks("""{"code":-400}""") }
    }
}
