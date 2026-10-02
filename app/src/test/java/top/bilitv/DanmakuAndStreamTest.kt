package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.danmaku.DanmakuItem
import top.bilitv.data.danmaku.danmakuSegmentCount
import top.bilitv.data.danmaku.danmakuSegmentIndex
import top.bilitv.data.danmaku.mergeDanmaku
import top.bilitv.data.danmaku.parseDanmakuSeg
import top.bilitv.data.danmaku.window
import top.bilitv.data.model.DashStream
import top.bilitv.data.model.PlayInfo
import top.bilitv.player.StreamSelector
import java.io.ByteArrayOutputStream

/**
 * 弹幕 protobuf 与选流逻辑的单测。
 *
 * 这两块都是**纯逻辑**，不需要真机就能验证；也正因为如此，它们是最容易悄悄写错、
 * 又最难在电视上排查的部分 —— 弹幕解析错了就是"没弹幕"，选流错了就是"黑屏"。
 */
class DanmakuAndStreamTest {

    // ------------------------------------------------------------ protobuf 编码（仅测试用）

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun key(out: ByteArrayOutputStream, field: Int, wire: Int) =
        varint(out, ((field shl 3) or wire).toLong())

    private fun vint(out: ByteArrayOutputStream, field: Int, value: Long) {
        key(out, field, 0)
        varint(out, value)
    }

    private fun bytes(out: ByteArrayOutputStream, field: Int, value: ByteArray) {
        key(out, field, 2)
        varint(out, value.size.toLong())
        out.write(value)
    }

    private fun str(out: ByteArrayOutputStream, field: Int, value: String) =
        bytes(out, field, value.toByteArray(Charsets.UTF_8))

    private fun elem(
        progressMs: Long,
        mode: Int = 1,
        fontsize: Int = 25,
        color: Int = 0xFFFFFF,
        content: String = "测试",
        extraFields: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        vint(out, 1, 123456789L) // id
        vint(out, 2, progressMs)
        vint(out, 3, mode.toLong())
        vint(out, 4, fontsize.toLong())
        vint(out, 5, color.toLong())
        str(out, 6, "deadbeef") // midHash
        str(out, 7, content)
        if (extraFields) {
            // 未知字段必须被安全跳过，不能影响已知字段
            vint(out, 99, 42L)
            str(out, 98, "未来才有的字段")
        }
        return out.toByteArray()
    }

    private fun seg(vararg elems: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        elems.forEach { bytes(out, 1, it) }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ 弹幕解析

    @Test
    fun parseDanmaku_basicFields() {
        val data = seg(
            elem(progressMs = 1500, mode = 1, fontsize = 25, color = 0xFF0000, content = "第一条"),
            elem(progressMs = 4200, mode = 5, fontsize = 18, color = 0x00FF00, content = "顶部"),
        )

        val list = parseDanmakuSeg(data)

        assertEquals(2, list.size)
        assertEquals(1500L, list[0].timeMs)
        assertEquals("第一条", list[0].content)
        assertEquals(0xFF0000, list[0].color)
        assertTrue(list[0].isScroll)
        assertTrue(list[1].isTop)
        assertEquals(18, list[1].fontsize)
    }

    @Test
    fun parseDanmaku_skipsUnknownFields() {
        val data = seg(elem(progressMs = 100, content = "带未知字段", extraFields = true))
        val list = parseDanmakuSeg(data)
        assertEquals(1, list.size)
        assertEquals("带未知字段", list[0].content)
    }

    /**
     * 关键防线：B 站接口结构变了、返回体被截断、代理插了广告页 ——
     * 都不能让 App 崩。最坏丢掉部分弹幕。
     */
    @Test
    fun parseDanmaku_truncatedDoesNotThrow() {
        val data = seg(elem(100, content = "完整的"), elem(200, content = "会被截断的"))
        val truncated = data.copyOfRange(0, data.size - 6)

        val list = parseDanmakuSeg(truncated)

        assertTrue("至少不应崩，且尽量保住已解析的部分", list.size <= 2)
    }

    @Test
    fun parseDanmaku_garbageReturnsEmpty() {
        assertTrue(parseDanmakuSeg(ByteArray(0)).isEmpty())
        assertTrue(parseDanmakuSeg(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())).isEmpty())
    }

    @Test
    fun parseDanmaku_dropsEntryWithoutContent() {
        // 有 progress 但没有 content 的条目应被丢弃（渲染不出来）
        val out = ByteArrayOutputStream()
        vint(out, 2, 500L)
        vint(out, 3, 1L)
        val list = parseDanmakuSeg(seg(out.toByteArray()))
        assertTrue(list.isEmpty())
    }

    @Test
    fun parseDanmaku_clampsFontsizeAndMasksColor() {
        // color 带高位（有些片源会给 alpha），必须被掩成纯 RGB
        val data = seg(elem(1, fontsize = 999, color = 0x7FFFFFFF))
        val item = parseDanmakuSeg(data).single()
        assertEquals(48, item.fontsize)
        assertEquals(0xFFFFFF, item.color)
    }

    // ------------------------------------------------------------ 分段

    @Test
    fun segmentIndexAndCount() {
        assertEquals(1, danmakuSegmentIndex(0L))
        assertEquals(1, danmakuSegmentIndex(359_999L))
        assertEquals(2, danmakuSegmentIndex(360_000L))
        assertEquals(3, danmakuSegmentIndex(720_000L))

        assertEquals(1, danmakuSegmentCount(0L))
        assertEquals(1, danmakuSegmentCount(212_000L))
        assertEquals(2, danmakuSegmentCount(360_001L))
        assertEquals(3, danmakuSegmentCount(1_080_000L))
    }

    // ------------------------------------------------------------ 时间窗

    @Test
    fun window_returnsOnlyItemsInsideRange() {
        val list = (0..10).map { DanmakuItem(it * 1_000L, 1, 25, 0xFFFFFF, "d$it") }

        val w = list.window(3_000L, 6_000L)

        assertEquals(listOf(3_000L, 4_000L, 5_000L, 6_000L), w.map { it.timeMs })
    }

    @Test
    fun window_emptyInputsAndInvertedRange() {
        val list = (0..3).map { DanmakuItem(it * 1_000L, 1, 25, 0xFFFFFF, "d$it") }
        assertTrue(list.window(5_000L, 1_000L).isEmpty())
        assertTrue(emptyList<DanmakuItem>().window(0L, 100L).isEmpty())
    }

    @Test
    fun mergeDanmaku_keepsAscendingOrder() {
        val a = listOf(DanmakuItem(0, 1, 25, 0, "a"), DanmakuItem(500, 1, 25, 0, "b"))
        val b = listOf(DanmakuItem(200, 1, 25, 0, "c"))

        val merged = mergeDanmaku(a, b)

        assertEquals(listOf(0L, 200L, 500L), merged.map { it.timeMs })

        // 空输入直接返回原对象语义（不额外排序，省一次开销）
        assertEquals(a, mergeDanmaku(a, emptyList()))
    }

    // ------------------------------------------------------------ 选流

    private fun dash(
        q: Int,
        codecs: String,
        bandwidth: Long = 1_000_000L,
        w: Int = 1920,
        h: Int = 1080,
    ) = DashStream(q, codecs, bandwidth, w, h, "https://x/$q-$codecs.m4s", emptyList())

    private val hevc1080 = dash(80, "hev1.1.6.L120.90")
    private val avc1080 = dash(80, "avc1.640028")
    private val av1_1080 = dash(80, "av01.0.08M.08")
    private val avc4k = dash(120, "avc1.640033", w = 3840, h = 2160)
    private val hevc4k = dash(120, "hev1.1.6.L150.90", w = 3840, h = 2160)

    private fun play(videos: List<DashStream>, audios: List<DashStream> = emptyList()) =
        PlayInfo(durationMs = 100_000L, videos = videos, audios = audios)

    @Test
    fun selectVideo_sameQualityPrefersHevc() {
        val picked = StreamSelector.pickVideo(
            listOf(avc1080, hevc1080, av1_1080),
            canVideo = { true },
        )
        assertEquals("hev1.1.6.L120.90", picked?.codecs)
    }

    @Test
    fun selectVideo_fallsBackWhenHevcUnsupported() {
        val picked = StreamSelector.pickVideo(
            listOf(avc1080, hevc1080, av1_1080),
            canVideo = { !it.isHevc },
        )
        assertEquals(
            "HEVC 解不了就该退到 AVC，而不是 AV1",
            "avc1.640028",
            picked?.codecs,
        )
    }

    @Test
    fun selectVideo_dropsToLowerQualityWhenTopIsUndecodable() {
        // 4K 全解不了 → 必须自动降到 1080P，而不是报错
        val picked = StreamSelector.pickVideo(
            listOf(avc4k, hevc4k, hevc1080, avc1080),
            canVideo = { it.height <= 1080 },
        )
        assertEquals(80, picked?.qualityId)
    }

    @Test
    fun selectVideo_preferHevcOffFlipsAvcFirst() {
        val picked = StreamSelector.pickVideo(
            listOf(avc1080, hevc1080),
            canVideo = { true },
            preferHevc = false,
        )
        assertEquals("avc1.640028", picked?.codecs)
    }

    /**
     * 逃生路：HEVC 在 Media3 1.5.x 上解析会崩（`HevcConfig.parseImpl`，见 `docs/08` §8）。
     * `onlyAvc = true` 必须**无条件**避开 HEVC / AV1 —— 哪怕 `preferHevc = true`。
     */
    @Test
    fun selectVideo_onlyAvcSkipsHevcAndAv1() {
        val picked = StreamSelector.pickVideo(
            listOf(hevc1080, av1_1080, avc1080),
            canVideo = { true },
            preferHevc = true,
            onlyAvc = true,
        )
        assertEquals("哪怕优先 HEVC，逃生路也只能给 AVC", "avc1.640028", picked?.codecs)
    }

    @Test
    fun selectVideo_onlyAvcReturnsNullWhenNoAvcExists() {
        assertNull(
            StreamSelector.pickVideo(
                listOf(hevc1080, av1_1080),
                canVideo = { true },
                onlyAvc = true,
            )
        )
        assertNull(StreamSelector.pickVideo(emptyList(), canVideo = { true }, onlyAvc = true))
    }

    @Test
    fun selectVideo_onlyAvcStillDropsToLowerQuality() {
        // 高清档位只有 HEVC → 只要 AVC 就得整体降档，而不是直接返回 null 让用户看黑屏
        val picked = StreamSelector.pickVideo(
            listOf(hevc4k, avc1080),
            canVideo = { true },
            onlyAvc = true,
        )
        assertEquals("avc1.640028", picked?.codecs)
        assertEquals(80, picked?.qualityId)
    }

    @Test
    fun select_onlyAvcKeepsAudio() {
        // 换编码只该换视频。音频照给，否则"降级"就变成"静音"了，白白损失
        val sel = StreamSelector.select(
            play = play(listOf(hevc1080, avc1080), audios = listOf(dash(30280, "mp4a.40.2"))),
            canVideo = { true },
            canAudio = { true },
            onlyAvc = true,
        )
        assertEquals("avc1.640028", sel?.video?.codecs)
        assertEquals("mp4a.40.2", sel?.audio?.codecs)
    }

    @Test
    fun selectVideo_respectsExplicitQuality() {
        val picked = StreamSelector.pickVideo(
            listOf(avc4k, hevc4k, hevc1080),
            canVideo = { it.height <= 1080 },
            qualityId = 80,
        )
        assertEquals(80, picked?.qualityId)
    }

    @Test fun preferredHighQualityFallsDownWhenUndecodableOrMissing() {
        val streams = listOf(hevc4k, hevc1080)
        assertEquals(80, StreamSelector.pickVideo(streams, { it.height <= 1080 }, qualityId = 120)?.qualityId)
        assertEquals(80, StreamSelector.pickVideo(streams, { true }, qualityId = 112)?.qualityId)
        assertTrue(listOf(120,125,126,127).all { id -> top.bilitv.data.settings.QualityOptions.ALL.any { it.id == id } })
    }

    @Test
    fun selectVideo_unknownQualityFallsBackToAuto() {
        // 用户记住的清晰度这一集没有 → 回退为自动，而不是播不了
        val picked = StreamSelector.pickVideo(
            listOf(hevc1080),
            canVideo = { true },
            qualityId = 999,
        )
        assertEquals(80, picked?.qualityId)
    }

    @Test
    fun selectVideo_noneDecodableReturnsNull() {
        assertNull(StreamSelector.pickVideo(listOf(hevc4k), canVideo = { false }))
        assertNull(StreamSelector.pickVideo(emptyList(), canVideo = { true }))
    }

    @Test
    fun pickAudio_byHighestBandwidth() {
        val low = dash(30216, "mp4a.40.2", bandwidth = 64_000L)
        val high = dash(30280, "mp4a.40.2", bandwidth = 192_000L)
        val mid = dash(30232, "mp4a.40.2", bandwidth = 132_000L)

        assertEquals(high, StreamSelector.pickAudio(listOf(low, mid, high), canAudio = { true }))
        // 最高码率解不了就退到次高
        assertEquals(mid, StreamSelector.pickAudio(listOf(high, mid), canAudio = { it.bandwidth < 190_000L }))
        assertNull(StreamSelector.pickAudio(emptyList(), canAudio = { true }))
    }

    @Test
    fun select_audioUnavailableStillReturnsVideo() {
        // 音频全解不了也要出画面（静音播放），不能整体失败
        val sel = StreamSelector.select(
            play = play(listOf(hevc1080), audios = listOf(dash(30280, "ec-3"))),
            canVideo = { true },
            canAudio = { false },
        )
        assertEquals("hev1.1.6.L120.90", sel?.video?.codecs)
        assertNull(sel?.audio)
    }
}
