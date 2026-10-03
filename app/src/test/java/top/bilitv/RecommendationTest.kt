package top.bilitv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.api.parseAppFeedRecommend
import top.bilitv.data.api.parseFeedRecommend
import top.bilitv.data.api.recommendWithFallback
import top.bilitv.data.model.bvidOfAid
import top.bilitv.data.settings.RecommendSource

class RecommendationTest {
    @Test fun realModernArchiveIdsMatchWebMetadataAndKnownVectors() {
        assertEquals("BV1aJa869EaJ", bvidOfAid(117342684187646L))
        assertEquals("BV17x411w7KC", bvidOfAid(170001))
        assertEquals("BV1Q541167Qg", bvidOfAid(455017605))
        assertNull(bvidOfAid(0)); assertNull(bvidOfAid(-1)); assertNull(bvidOfAid(1L shl 51))
    }
    @Test fun appCardsReadTheirOwnFieldsAndSkipAdsAndNonVideoCards() {
        val raw = """{"code":0,"data":{"items":[
          {"card_goto":"av","param":"117342684187646","title":"视频","cover":"https://i0.hdslb.com/a.jpg",
            "player_args":{"aid":117342684187646,"duration":1705},"args":{"up_name":"作者"},
            "cover_left_text_1":"188.1万","cover_left_text_2":"7462","cover_right_text":"28:25"},
          {"card_goto":"av","param":"170001","title":"广告","cover":"a","ad_info":{}},
          {"card_goto":"bangumi","param":"1","title":"剧集","cover":"a"},
          {"card_goto":"av","param":"-1","title":"错误","cover":"a"}]}}"""
        val item = parseAppFeedRecommend(raw).single()
        assertEquals("BV1aJa869EaJ", item.bvid); assertEquals("作者", item.ownerName)
        assertEquals(1705, item.durationSec); assertEquals(1881000L, item.viewCount); assertEquals(7462L, item.danmakuCount)
        assertEquals(0L, item.pubDateSec)
        assertEquals(1, parseFeedRecommend("""{"code":0,"data":{"items":[{"bvid":"BV17x411w7KC"}]}}""").size)
    }
    @Test fun fallbackIsSingleAndCancellationOrValidEmptyNeverFallsBack() = runBlocking {
        val calls = mutableListOf<RecommendSource>()
        val page = recommendWithFallback(RecommendSource.APP) { source ->
            calls += source; if (source == RecommendSource.APP) throw java.io.IOException("unavailable") else emptyList()
        }
        assertEquals(listOf(RecommendSource.APP, RecommendSource.WEB), calls)
        assertEquals(RecommendSource.WEB, page.source); assertNotNull(page.notice)
        calls.clear()
        try {
            recommendWithFallback(RecommendSource.APP) { source -> calls += source; throw CancellationException() }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf(RecommendSource.APP), calls)
        calls.clear()
        assertEquals(RecommendSource.APP, recommendWithFallback(RecommendSource.APP) { calls += it; emptyList() }.source)
        assertEquals(listOf(RecommendSource.APP), calls)
        try {
            recommendWithFallback(RecommendSource.WEB) { throw java.io.IOException("both failed") }
            fail("Web failures must reach the retry UI")
        } catch (_: java.io.IOException) { }
    }
}
