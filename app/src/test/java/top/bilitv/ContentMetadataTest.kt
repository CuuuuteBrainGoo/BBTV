package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.api.*
import top.bilitv.data.model.toFeedItem

class ContentMetadataTest {
    @Test fun `角标使用真实结构 推荐理由作者会员不虚构付费身份`() {
        val items = listOf(
            """"badge":{"text":"动态视频"}""",
            """"badge_info":{"text":"会员抢先看"}""",
            """"is_upower_exclusive":true,"rcmd_reason":{"content":"高质量"}""",
            """"badge":"会员专享"""",
            """"badge":{"text":"特价（大会员额外付费）"}""",
            """"owner":{"vip":{"type":2}},"rcmd_reason":{"content":"限时优惠"}""",
            """"rights":{"is_ugc_pay":1}""",
            """"is_charging_arc":1""",
        ).mapIndexed { i, fields -> """{"bvid":"BV$i",$fields}""" }.joinToString(",")
        val parsed = parsePopular("""{"code":0,"data":{"list":[$items]}}""")
        assertEquals(listOf("动态视频","抢先看","充电视频","大会员","特价","","付费视频","充电视频"), parsed.map { it.badge })
    }

    @Test fun `PGC根级result及v2画质字段正常解析 首页转换保留角标`() {
        val detail = parsePgcDetail("""{"code":0,"result":{"season_id":33802,"title":"测试番剧","episodes":[{"id":1,"cid":2,"title":"1","duration":1000,"badge":{"text":"会员抢先看"}}]}}""")!!
        assertEquals("抢先看", detail.episodes.single().badge)
        assertEquals(1, detail.episodes.single().durationSec)
        val payload = """{"dash":{"duration":10,"video":[{"id":64,"width":1280,"height":720,"codecs":"avc1","base_url":"https://example.com/video"}],"audio":[]},"support_formats":[{"quality":64,"new_description":"720P 高清"}]}"""
        val play = parsePlayInfo("""{"code":0,"result":$payload}""")!!
        assertEquals("720P 高清", play.qualityLabels[64])
        assertEquals(play, parsePlayInfo("""{"code":0,"data":{"video_info":$payload}}"""))
        assertNull(parsePlayInfo("""{"code":-101,"result":$payload}"""))
        val season = parsePgcIndex("""{"code":0,"data":{"list":[{"season_id":4,"title":"测试","badge_info":{"text":"特价"}}]}}""").single()
        assertEquals("特价", season.toFeedItem().badge)
    }
}
