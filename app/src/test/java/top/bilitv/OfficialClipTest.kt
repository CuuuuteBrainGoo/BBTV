package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.api.AppGrpcCodec
import top.bilitv.data.api.parsePlayInfo
import top.bilitv.data.model.OfficialClip

class OfficialClipTest {
    @Test fun `Web markers use seconds including long films and reject unknown reversed and out of media ranges`() {
        val play = parsePlayInfo("""{"code":0,"result":{
          "dash":{"duration":14400,"video":[{"id":80,"baseUrl":"https://example.invalid/v","codecs":"avc1"}]},
          "clip_info_list":[
            {"clipType":"CLIP_TYPE_OP","start":0,"end":89.5},
            {"clip_type":"CLIP_TYPE_ED","start":12000,"end":12100},
            {"clip_type":1,"start":0,"end":89.5},
            {"clip_type":"CLIP_TYPE_AD","start":90,"end":120},
            {"clip_type":"CLIP_TYPE_HE","start":120,"end":150},
            {"clip_type":"CLIP_TYPE_ED","start":15000,"end":16000},
            {"clip_type":"CLIP_TYPE_OP","start":-1,"end":30},
            {"clip_type":"CLIP_TYPE_OP","end":30},
            {"clip_type":"CLIP_TYPE_ED","start":50,"end":40}
          ]}}""")!!
        assertEquals(listOf(OfficialClip(0, 89500, true), OfficialClip(12000000, 12100000, false)), play.officialClips)
        assertNull(OfficialClip.fromSeconds(Double.NaN, 10.0, true, 100000))
        assertNull(OfficialClip.fromSeconds(0.0, Double.POSITIVE_INFINITY, true, 100000))
        assertNull(OfficialClip.fromSeconds(0.0, 0.0001, true, 100000))
        assertNull(OfficialClip.fromSeconds(0.0, 120.0, true, 30000))
        assertTrue(parsePlayInfo("""{"code":0,"data":{"dash":{"duration":30,"video":[{"id":16,"baseUrl":"https://example.invalid/v"}]}}}""")!!.officialClips.isEmpty())
    }

    @Test fun `PGC protobuf business clips map only OP and ED and UGC ignores that field`() {
        fun m() = AppGrpcCodec.Message()
        fun clip(type: Long, start: Long, end: Long) = m().number(2, start).number(3, end).number(4, type).build()
        val video = m().bytes(1, m().number(1, 80).build())
            .bytes(2, m().text(1, "https://example.invalid/v").number(4, 7).build()).build()
        val vod = m().number(3, 1440000).bytes(5, video).build()
        val business = m().bytes(6, clip(1, 0, 90)).bytes(6, clip(2, 1350, 1440))
            .bytes(6, clip(3, 30, 60)).bytes(6, clip(5, 30, 60))
            .bytes(6, clip(1, 0, 90)).bytes(6, clip(2, 1440, 1500)).build()
        assertEquals(listOf(OfficialClip(0, 90000, true), OfficialClip(1350000, 1440000, false)),
            AppGrpcCodec.playInfo(m().bytes(1, vod).bytes(3, business).build(), true).officialClips)
        assertTrue(AppGrpcCodec.playInfo(m().bytes(1, vod).bytes(6, business).build(), false).officialClips.isEmpty())
    }
}
