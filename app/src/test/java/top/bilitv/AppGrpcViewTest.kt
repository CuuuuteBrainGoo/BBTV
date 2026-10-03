package top.bilitv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.api.AppGrpcCodec
import top.bilitv.data.api.AppGrpcView
import top.bilitv.data.api.detailWithFallback
import top.bilitv.data.settings.VideoApiSource

class AppGrpcViewTest {
    private val bv = "BV1aJa869EaJ"
    private fun m() = AppGrpcCodec.Message()
    private fun captured() = AppGrpcView.detail(javaClass.getResourceAsStream("/app-grpc-view.pb")!!.use { it.readBytes() }, bv)

    @Test fun `captured anonymous HTTP2 View maps actual archive and page identities`() {
        val detail = captured()
        assertEquals(bv, detail.bvid)
        assertEquals(117342684187646L, detail.aid)
        assertEquals(42248701171L, detail.cid)
        assertEquals(1705, detail.durationSec)
        assertEquals(2030885L, detail.viewCount)
        assertEquals(7835L, detail.danmakuCount)
        assertEquals("Captured public video", detail.title)
        assertEquals("Captured description", detail.desc)
        assertEquals("Example UP", detail.ownerName)
        assertEquals("https://example.invalid/cover.jpg", detail.cover)
        assertEquals(1, detail.pages.size)
        assertEquals(detail.cid, detail.pages.single().cid)
        assertFalse(detail.chargingExclusive); assertFalse(detail.paidContent)
        assertEquals(bv, AppGrpcCodec.Fields(AppGrpcCodec.unframe(AppGrpcView.request(bv))).text(2))
    }

    @Test fun `multipage and collection fields stay separate and missing rights metadata falls back`() {
        fun page(cid: Long, index: Int) = m().bytes(1, m().number(1, cid).number(2, index.toLong())
            .text(4, "P$index").number(5, 20).build()).build()
        val arc = m().number(1, 99).number(2, 2).number(5, 1).text(7, "Title").number(16, 40).number(29, 10)
        val episode = m().number(3, 900).text(4, "Other video").text(5, "https://example.invalid/poster")
            .text(9, "BV1xx411c7mD").build()
        val season = m().text(2, "Collection").number(13, 1).bytes(5, m().bytes(4, episode).build()).build()
        fun root(a: ByteArray = arc.build()) = m().bytes(1, a).text(14, bv).bytes(2, page(100, 1)).bytes(2, page(200, 2))
        val good = root().bytes(24, season).build()
        val detail = AppGrpcView.detail(good, bv)
        assertEquals(listOf(100L, 200L), detail.pages.map { it.cid })
        assertEquals(listOf("BV1xx411c7mD"), detail.collection.map { it.bvid })
        assertEquals("Collection", detail.collectionTitle)
        listOf(root().build(), root().bytes(24, m().number(13, 2).build()).build(),
            m().bytes(1, arc.build()).text(14, bv).bytes(2, page(100, 1)).bytes(24, season).build(),
            m().bytes(1, arc.build()).text(14, bv).bytes(2, page(0, 1)).bytes(24, season).build(),
            root().bytes(24, season).number(28, 1).build(),
            root().bytes(24, season).bytes(69, m().build()).build(),
            root().bytes(24, season).bytes(68, m().number(1, 1).build()).build(),
            root(arc.bytes(21, m().number(9, 1).build()).build()).bytes(24, season).build()).forEach {
            assertThrows(Exception::class.java) { AppGrpcView.detail(it, bv) }
        }
        assertThrows(IllegalStateException::class.java) { AppGrpcView.detail(good, "BV1yy411c7mE") }
        assertThrows(Exception::class.java) { AppGrpcView.detail(byteArrayOf(10, 100, 1), bv) }
        assertThrows(IllegalArgumentException::class.java) { AppGrpcView.request("invalid") }
    }

    @Test fun `detail fallback is single and cancellation or failed Web never restart App`() = runBlocking {
        val calls = mutableListOf<VideoApiSource>()
        val good = captured()
        val successful = detailWithFallback(VideoApiSource.APP, bv) { calls += it; good }
        assertEquals(good, successful); assertEquals(listOf(VideoApiSource.APP), calls)
        calls.clear()
        val fallback = detailWithFallback(VideoApiSource.APP, bv) {
            calls += it; if (it == VideoApiSource.APP) good.copy(bvid = "BV1yy411c7mE") else good
        }
        assertEquals(good, fallback); assertEquals(listOf(VideoApiSource.APP, VideoApiSource.WEB), calls)
        calls.clear()
        assertNull(detailWithFallback(VideoApiSource.APP, bv) { calls += it; null })
        assertEquals(listOf(VideoApiSource.APP, VideoApiSource.WEB), calls)
        calls.clear()
        assertThrows(CancellationException::class.java) { runBlocking {
            detailWithFallback(VideoApiSource.APP, bv) { calls += it; throw CancellationException() }
        } }
        assertEquals(listOf(VideoApiSource.APP), calls)
        calls.clear()
        assertThrows(java.io.IOException::class.java) { runBlocking {
            detailWithFallback(VideoApiSource.APP, bv) { calls += it; throw java.io.IOException() }
        } }
        assertEquals(listOf(VideoApiSource.APP, VideoApiSource.WEB), calls)
        calls.clear()
        assertNull(detailWithFallback(VideoApiSource.WEB, bv) { calls += it; good.copy(bvid = "different") })
        assertEquals(listOf(VideoApiSource.WEB), calls)
    }
}
