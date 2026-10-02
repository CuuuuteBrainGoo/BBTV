package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.api.parseFeedRecommend
import top.bilitv.data.api.parsePlayInfo
import top.bilitv.data.api.parseVideoDetail
import top.bilitv.data.sponsor.SponsorBlockApi
import top.bilitv.data.sponsor.SponsorCategory
import top.bilitv.data.sponsor.SponsorSegment
import top.bilitv.data.sponsor.SkipPlanner
import top.bilitv.data.sponsor.parseSponsorHashed
import top.bilitv.data.sponsor.pickSegmentsForCid

/**
 * 广告跳过 + 响应解析的单测。
 *
 * 这里覆盖的都是"实现时最容易写错、错了又不容易发现"的点：
 * 分P 串用、跳过时序、取消/撤销、宽松解析。
 */
class SponsorAndParseTest {

    // ---------------------------------------------------------- 分P 过滤

    private fun seg(start: Long, end: Long, cid: Long?) =
        SponsorSegment(start, end, SponsorCategory.SPONSOR, "skip", null, cid)

    @Test
    fun pickSegments_prefersExactCid() {
        val all = listOf(seg(0, 1000, 111), seg(5000, 6000, 222))
        assertEquals(listOf(seg(5000, 6000, 222)), pickSegmentsForCid(all, 222))
    }

    @Test
    fun pickSegments_allNullCidMeansAppliesToEverything() {
        val all = listOf(seg(0, 1000, null), seg(5000, 6000, null))
        assertEquals(all, pickSegmentsForCid(all, 999))
    }

    @Test
    fun pickSegments_singleKnownCidAppliesToEverything() {
        val all = listOf(seg(0, 1000, 111), seg(5000, 6000, 111))
        assertEquals(all, pickSegmentsForCid(all, 999))
    }

    @Test
    fun pickSegments_multipleCidsWithoutMatchReturnsEmpty() {
        // 关键：宁可不跳，也不能把别的分P的片段用上来
        val all = listOf(seg(0, 1000, 111), seg(5000, 6000, 222))
        assertTrue(pickSegmentsForCid(all, 333).isEmpty())
    }

    // ---------------------------------------------------------- 哈希过滤

    @Test
    fun parseHashed_filtersByExactBvid() {
        val json = """
        [
          {"videoID":"BV1aaaaaaaaa","segments":[
            {"segment":[1.0,2.0],"category":"sponsor","actionType":"skip","UUID":"x"}]},
          {"videoID":"BV1bbbbbbbbb","segments":[
            {"segment":[10.0,20.0],"category":"intro","actionType":"skip","UUID":"y"},
            {"segment":[30.0,40.0],"category":"outro","actionType":"skip","UUID":"z"}]}
        ]
        """.trimIndent()
        val got = parseSponsorHashed(json, "BV1bbbbbbbbb")
        assertEquals(2, got.size)
        assertEquals(10_000L, got[0].startMs)
        assertEquals(SponsorCategory.INTRO, got[0].category)
        assertTrue(parseSponsorHashed(json, "BV1ccccccccc").isEmpty())
    }

    @Test
    fun parseSegments_dropsInvalidRangeButKeepsPoi() {
        val json = """
        [
          {"segment":[10.0,10.0],"category":"sponsor","actionType":"skip"},
          {"segment":[20.0,30.0],"category":"sponsor","actionType":"skip"},
          {"segment":[50.0,50.0],"category":"poi_highlight","actionType":"poi"}
        ]
        """.trimIndent()
        val got = top.bilitv.data.sponsor.parseSponsorSegments(json)
        // 零长度区间被丢弃，但 POI（点）保留
        assertEquals(2, got.size)
        assertTrue(got.any { it.isPoi })
    }

    // ---------------------------------------------------------- 跳过时序

    private val sponsorSeg = SponsorSegment(10_000, 20_000, SponsorCategory.SPONSOR, "skip", "u1", 100L)

    private fun planner() = SkipPlanner(lookAheadMs = 1_000, confirmDelayMs = 2_000).apply {
        setSegments(listOf(sponsorSeg), setOf(SponsorCategory.SPONSOR))
    }

    @Test
    fun planner_armsInAdvance_thenSkipsAfterDelay() {
        val p = planner()
        // 提前 1 秒进入候选
        assertTrue(p.onPosition(9_500, 60_000, nowMs = 0) is SkipPlanner.Decision.Armed)
        // 未到延迟 → 不动
        assertTrue(p.onPosition(10_100, 60_000, nowMs = 1_000) is SkipPlanner.Decision.None)
        // 到延迟 → 跳
        val d = p.onPosition(10_200, 60_000, nowMs = 2_000)
        assertTrue(d is SkipPlanner.Decision.Skip)
        assertEquals(20_000L, (d as SkipPlanner.Decision.Skip).toMs)
    }

    @Test
    fun planner_clampsTargetBeforeEnd() {
        val p = planner()
        p.onPosition(9_500, 60_000, nowMs = 0)
        // 片段末尾已贴近视频结尾 → 目标要 clamp 到尾前 500ms
        val d = p.onPosition(10_100, 20_200, nowMs = 5_000)
        assertEquals(19_700L, (d as SkipPlanner.Decision.Skip).toMs)
    }

    @Test
    fun planner_doesNotSkipTwice() {
        val p = planner()
        p.onPosition(9_500, 60_000, nowMs = 0)
        assertTrue(p.onPosition(10_100, 60_000, nowMs = 5_000) is SkipPlanner.Decision.Skip)
        // 再次经过同一片段不应重复 armed
        assertTrue(p.onPosition(10_200, 60_000, nowMs = 6_000) is SkipPlanner.Decision.None)
    }

    @Test
    fun planner_cancelMarksSegmentHandled() {
        val p = planner()
        p.onPosition(9_500, 60_000, nowMs = 0)
        p.cancelPending()
        assertNull(p.pendingSegment)
        // 取消后本次不再自动跳
        assertTrue(p.onPosition(11_000, 60_000, nowMs = 9_000) is SkipPlanner.Decision.None)
    }

    @Test
    fun planner_userSeekDropsCountdownButCanArmAgain() {
        val p = planner()
        p.onPosition(9_500, 60_000, nowMs = 0)
        p.onUserSeek()
        assertNull(p.pendingSegment)
        // 用户回到片段前，仍可重新排定（因为没有 skip 过、也没取消过）
        assertTrue(p.onPosition(9_800, 60_000, nowMs = 1_000) is SkipPlanner.Decision.Armed)
    }

    @Test
    fun planner_ignoresUnselectedCategory() {
        val p = SkipPlanner().apply {
            setSegments(listOf(sponsorSeg), setOf(SponsorCategory.INTRO))
        }
        assertTrue(p.onPosition(9_500, 60_000, nowMs = 0) is SkipPlanner.Decision.None)
    }

    @Test
    fun planner_resetClearsState() {
        val p = planner()
        p.onPosition(9_500, 60_000, nowMs = 0)
        p.reset()
        assertNull(p.pendingSegment)
        assertTrue(p.onPosition(9_500, 60_000, nowMs = 1) is SkipPlanner.Decision.None)
    }

    // ---------------------------------------------------------- 解析

    @Test
    fun sha256PrefixIsFourLowercaseHex() {
        val h = SponsorBlockApi.sha256Hex("BV1bbbbbbbbb")
        assertEquals(64, h.length)
        assertEquals(h.substring(0, 4), h.substring(0, 4).lowercase())
    }

    @Test
    fun parseVideoDetail_toleratesMissingFields() {
        val json = """{"code":0,"data":{"bvid":"BV1xx","aid":1,"cid":22,"title":"t"}}"""
        val d = parseVideoDetail(json)
        assertEquals("BV1xx", d?.bvid)
        assertEquals(22L, d?.cid)
        assertEquals(0, d?.pages?.size)          // pages 缺失不崩
        assertEquals("", d?.ownerName)
        assertNull(parseVideoDetail("""{"code":-404,"data":null}"""))
    }

    @Test
    fun parsePlayInfo_detectsCodecsAndPrefersHevc() {
        val json = """
        {"code":0,"data":{"timelength":212000,"dash":{
          "duration":212,
          "video":[
            {"id":32,"codecs":"avc1.64001F","bandwidth":100,"width":852,"height":480,"baseUrl":"https://a/1"},
            {"id":32,"codecs":"hev1.1.6.L120.90","bandwidth":90,"width":852,"height":480,
             "baseUrl":"https://a/2","backupUrl":["https://b/2"]},
            {"id":16,"codecs":"avc1.64001E","bandwidth":50,"width":640,"height":360,"baseUrl":"https://a/3"}
          ],
          "audio":[{"id":30280,"codecs":"mp4a.40.2","bandwidth":30,"baseUrl":"https://a/a"}]}}}
        """.trimIndent()
        val p = parsePlayInfo(json)
        assertEquals(212_000L, p?.durationMs)
        assertEquals(3, p?.videos?.size)
        assertEquals(1, p?.audios?.size)

        // 同清晰度(32) 优先 HEVC，避开 AVC
        val picked = p!!.preferredVideo()
        assertTrue(picked!!.isHevc)
        assertEquals("https://a/2", picked.baseUrl)
        assertEquals(1, picked.backupUrls.size)

        assertTrue(p.videos[0].isAvc)
        assertTrue(p.videos[0].isHevc.not())
    }

    @Test
    fun parseFeedRecommend_skipsAdsAndNonBv() {
        val json = """
        {"code":0,"data":{"item":[
          {"bvid":"BV1ok","title":"正常","pic":"https://c/1","duration":10,"owner":{"name":"up"},"stat":{"view":5}},
          {"goto":"ad","title":"广告位","pic":"https://c/x"},
          {"bvid":"av12345","title":"非BV","pic":"https://c/2"},
          {"bvid":"BV1ok2","title":"另一个","pic":"https://c/3","duration":20,"owner":{"name":"up2"}}
        ]}}
        """.trimIndent()
        val list = parseFeedRecommend(json)
        assertEquals(2, list.size)
        assertEquals("BV1ok", list[0].bvid)
        assertEquals("up2", list[1].ownerName)
    }
}
