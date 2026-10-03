package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.api.parseVideoDetail
import top.bilitv.data.api.parsePlayInfo
import top.bilitv.data.model.PlaybackPreview
import top.bilitv.data.model.explicitPreviewState

class PlaybackPreviewTest {
    @Test fun `preview availability purchase toast and unknown flags never claim playback rights`() {
        val raw = """{"code":0,"data":{"bvid":"BVtest","is_upower_exclusive":true,"is_upower_preview":true,
          "is_upower_play":false,"preview_toast":"购买观看完整视频","rights":{"ugc_pay":1}}}"""
        val detail = parseVideoDetail(raw)!!
        assertTrue(detail.chargingExclusive); assertEquals(true, detail.chargingPreviewAvailable); assertTrue(detail.paidContent)
        assertNull(explicitPreviewState(raw))
        assertNull(parseVideoDetail("""{"code":0,"data":{}}""")!!.chargingPreviewAvailable)
        assertNull(explicitPreviewState("""{"code":0,"data":{"is_upower_play":true,"is_preview":"unknown"}}"""))
        assertEquals(false, explicitPreviewState("""{"code":0,"data":{"is_ugc_pay_preview":false}}"""))
        assertEquals(true, explicitPreviewState("""{"code":0,"data":{"video_info":{"is_preview":1}}}"""))
        try { explicitPreviewState("""{"code":-403,"data":{"is_preview":true}}"""); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `whole film metadata cannot advertise a longer playable preview`() {
        val raw = """{"code":0,"result":{"is_preview":1,"video_info":{"dash":{"duration":5441,
          "video":[{"id":32,"codecs":"avc1","base_url":"https://example.invalid/v"}]}}}}"""
        assertTrue(parsePlayInfo(raw)!!.isPreview)
        assertEquals(0L, PlaybackPreview.duration(true, 5441000, 5441000, null))
        assertEquals(360000L, PlaybackPreview.duration(true, 5440167, 5441000, 360000))
        assertEquals(360000L, PlaybackPreview.duration(true, -9223372036854775807L, 5441000, 360000))
        assertEquals(180000L, PlaybackPreview.duration(true, 180000, 5441000, 360000))
        assertEquals(5441000L, PlaybackPreview.duration(false, 0, 5441000, null))
        assertEquals(0L, PlaybackPreview.duration(false, 0, -1, null))
        try { PlaybackPreview.requireBound(parsePlayInfo(raw)!!); fail("missing limit accepted") }
        catch (_: java.io.IOException) { }
        try { PlaybackPreview.requireBound(parsePlayInfo(raw)!!.copy(previewLimitMs = Long.MAX_VALUE)); fail("overflow accepted") }
        catch (_: java.io.IOException) { }
    }
}
