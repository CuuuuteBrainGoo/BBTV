package top.bilitv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.api.AppGrpcCodec
import top.bilitv.data.api.playbackWithFallback
import top.bilitv.data.model.PlayInfo
import top.bilitv.data.settings.VideoApiSource
import java.util.zip.GZIPOutputStream

class AppGrpcPlaybackTest {
    private fun captured(): PlayInfo = AppGrpcCodec.playInfo(javaClass.getResourceAsStream("/app-grpc-ugc.pb")!!.use { it.readBytes() }, false)

    @Test fun `captured anonymous HTTP2 reply maps actual media and no locked quality placeholders`() {
        val play = captured()
        assertEquals(1704832L, play.durationMs)
        assertEquals(listOf(32, 16), play.videos.map { it.qualityId })
        assertTrue(play.videos.all { it.isHevc && it.width > 0 && it.height > 0 })
        assertEquals(setOf(30216, 30232, 30280), play.audios.map { it.qualityId }.toSet())
        assertTrue(play.videos.all { it.baseUrl == "https://cdn.example.invalid/video.m4s" })
        assertFalse(play.isPreview)
        assertFalse(play.qualityLabels.containsKey(127))
    }

    @Test fun `unary framing rejects broken lengths compression and inflated payloads`() {
        val payload = byteArrayOf(8, 1)
        assertArrayEquals(payload, AppGrpcCodec.unframe(byteArrayOf(0, 0, 0, 0, 2, 8, 1)))
        fun reject(bytes: ByteArray, encoding: String? = null) {
            try { AppGrpcCodec.unframe(bytes, encoding); fail("invalid frame accepted") } catch (_: IllegalArgumentException) { }
        }
        reject(byteArrayOf(0, 0, 0, 0, 3, 8, 1))
        reject(byteArrayOf(0, -1, -1, -1, -1))
        reject(byteArrayOf(1, 0, 0, 0, 2, 8, 1))
        reject(byteArrayOf(2, 0, 0, 0, 2, 8, 1))
        val output = java.io.ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload) }
        val gzip = AppGrpcCodec.frame(output.toByteArray()).also { it[0] = 1 }
        assertArrayEquals(payload, AppGrpcCodec.unframe(gzip, "gzip"))
        output.reset(); GZIPOutputStream(output).use { it.write(ByteArray(AppGrpcCodec.MAX_BYTES + 1)) }
        reject(AppGrpcCodec.frame(output.toByteArray()).also { it[0] = 1 }, "gzip")
    }

    @Test fun `empty errored DRM streams and unsupported codecs never become playable options`() {
        fun m() = AppGrpcCodec.Message()
        val good = m().bytes(1, m().number(1, 80).text(11, "1080P").build())
            .bytes(2, m().text(1, "https://example.invalid/v").number(4, 7).number(10, 1920).number(11, 1080).build()).build()
        val locked = m().bytes(1, m().number(1, 120).number(4, 6002001).text(11, "4K").build())
            .bytes(2, m().text(1, "https://example.invalid/locked").number(4, 12).build()).build()
        val drm = m().bytes(1, m().number(1, 127).build())
            .bytes(2, m().text(1, "https://example.invalid/drm").number(4, 12).text(12, "pssh").build()).build()
        val placeholder = m().bytes(1, m().number(1, 125).text(11, "HDR").build()).build()
        val vod = m().number(3, 60000).bytes(5, good).bytes(5, locked).bytes(5, drm).bytes(5, placeholder).build()
        val reply = m().bytes(1, vod).bytes(3, m().number(1, 1).number(22, 30000).build()).build()
        val parsed = AppGrpcCodec.playInfo(reply, true)
        assertEquals(listOf(80), parsed.videos.map { it.qualityId }); assertEquals(setOf(80), parsed.qualityLabels.keys)
        assertTrue(parsed.isPreview); assertTrue(parsed.audios.isEmpty())
        assertEquals(30000L, parsed.previewLimitMs)
        val ugc = AppGrpcCodec.playInfo(m().bytes(1, vod).bytes(6, m().number(9, 1).number(10, 20000).build()).build(), false)
        assertEquals(20000L, ugc.previewLimitMs)
        try { AppGrpcCodec.playInfo(m().bytes(1, vod).bytes(3, m().number(1, 1).build()).build(), true); fail("unbounded preview") }
        catch (_: java.io.IOException) { }
        try { AppGrpcCodec.playInfo(m().bytes(1, m().bytes(5, locked).build()).build(), false); fail() }
        catch (_: java.io.IOException) { }
        try { AppGrpcCodec.playInfo(byteArrayOf(10, 100, 1), false); fail() }
        catch (_: IndexOutOfBoundsException) { }
    }

    @Test fun `App failures incompatible media and cancellation have bounded fallback`() = runBlocking {
        val calls = mutableListOf<VideoApiSource>()
        val play = captured()
        val fallback = playbackWithFallback(VideoApiSource.APP) {
            calls += it; if (it == VideoApiSource.APP) throw java.io.IOException("unavailable") else play
        }!!
        assertEquals(listOf(VideoApiSource.APP, VideoApiSource.WEB), calls)
        assertEquals(VideoApiSource.WEB, fallback.source); assertNotNull(fallback.notice)
        calls.clear()
        val incompatible = playbackWithFallback(VideoApiSource.APP, { false }) { calls += it; play }!!
        assertEquals(listOf(VideoApiSource.APP, VideoApiSource.WEB), calls); assertNotNull(incompatible.notice)
        calls.clear()
        val successful = playbackWithFallback(VideoApiSource.APP) { calls += it; play }!!
        assertEquals(listOf(VideoApiSource.APP), calls); assertEquals(VideoApiSource.APP, successful.source)
        calls.clear()
        try { playbackWithFallback(VideoApiSource.APP) { calls += it; throw CancellationException() }; fail() }
        catch (_: CancellationException) { }
        assertEquals(listOf(VideoApiSource.APP), calls)
        calls.clear()
        try { playbackWithFallback(VideoApiSource.APP) { calls += it; throw java.io.IOException() }; fail() }
        catch (_: java.io.IOException) { }
        assertEquals(listOf(VideoApiSource.APP, VideoApiSource.WEB), calls)
        assertEquals(VideoApiSource.WEB, VideoApiSource.of("unknown"))
    }
}
