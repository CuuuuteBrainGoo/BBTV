package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.danmaku.*

class DanmakuFilterTest {
    @Test fun commandMetadataReadOnlyTextAndDensityLimits() {
        fun number(field: Int, value: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream(); out.write(field shl 3)
            var n = value
            do { out.write(((n and 127) or if (n > 127) 128 else 0).toInt()); n = n ushr 7 } while (n != 0L)
            return out.toByteArray()
        }
        fun text(field: Int, value: String): ByteArray {
            val body = value.toByteArray()
            return number(field, body.size.toLong()).let { byteArrayOf((field shl 3 or 2).toByte()) + it.drop(1) + body }
        }
        fun command(kind: String, content: String, time: Long, extra: String = ""): ByteArray {
            val body = text(4, kind) + text(5, content) + number(6, time) + text(9, extra)
            return byteArrayOf(0x4a) + number(1, body.size.toLong()).drop(1) + body
        }
        val bytes = command("#UP#", "真实文字", 1000) + command("#LINK#", "", 11000, """{"title":"关联标题","url":"https://invalid.example/never-open"}""") +
            command("#ATTENTION#", "", 21000, """{"type":2}""") + command("#UNKNOWN#", "", 31000) +
            command("#UP#", "真实文字", 22000) + command("#UP#", "新内容", 21500) + command("#UP#", "真实文字", 61000)
        val parsed = parseDanmakuCloudProfile(bytes).interactions
        assertEquals(6, parsed.size); assertTrue(parsed.all { it.interaction && it.mode == 5 })
        assertTrue(parsed.any { it.content == "关联视频 · 关联标题" })
        assertTrue(parsed.any { it.content == "UP 主提示 · 关注 UP 主，点赞、投币、收藏" })
        assertEquals(listOf(1000L,11000L,21000L,61000L), filterDanmaku(parsed, DanmakuFilterOptions()).map { it.timeMs })
        assertTrue(filterDanmaku(parsed, DanmakuFilterOptions(allowInteraction = false)).isEmpty())
        assertFalse(filterDanmaku(parsed, DanmakuFilterOptions(level = 5)).isEmpty()) // 互动没有普通弹幕权重
        assertTrue(filterDanmaku(parsed, DanmakuFilterOptions(), localRules = DanmakuRules(keywords = listOf("真实文字"))).none { it.content.contains("真实文字") })
        assertTrue(parseDanmakuCloudProfile(command("#LINK#", "", 1, "broken")).interactions.isEmpty())
        assertTrue(parseDanmakuCloudProfile(command("#UP#", "文字", 86_400_001)).interactions.isEmpty())
        assertTrue(runCatching { parseDanmakuCloudProfile(ByteArray(1_048_577)) }.isFailure)
        val profile = parseDanmakuCloudProfile(byteArrayOf(0x52,4,0x10,1,0x18,7) + bytes)
        assertEquals(7, profile.level); assertEquals(parsed, profile.interactions)
    }
    @Test fun liveReceiveProtocolBoundsAndCompressedFrames() {
        val body = """{"cmd":"DANMU_MSG:4:0:2:2:2:0","info":[[0,1,25,16711680,0,0,0,"1234abcd"],"直播内容"]}""".toByteArray()
        val frame = livePacket(5, body)
        val zipped = java.io.ByteArrayOutputStream().also { out -> java.util.zip.DeflaterOutputStream(out).use { it.write(frame + frame) } }.toByteArray()
        val packets = livePackets(livePacket(5, zipped, 2))
        assertEquals(2, packets.size)
        assertEquals("直播内容", liveMessage(packets[0].second)!!.content)
        assertEquals(0xFF0000, liveMessage(packets[0].second)!!.color)
        assertTrue(livePackets(byteArrayOf(0,0,0,1) + ByteArray(20)).isEmpty())
        val oversized = java.io.ByteArrayOutputStream().also { out -> java.util.zip.DeflaterOutputStream(out).use { it.write(ByteArray(300000)) } }.toByteArray()
        assertTrue(livePackets(livePacket(5, oversized, 2)).isEmpty())
        assertNull(liveMessage("""{"cmd":"SEND_GIFT"}""".toByteArray()))
    }
    private fun item(weight: Int = 0, mode: Int = 1, time: Long = 0, text: String = "内容", color: Int = 0xFFFFFF) =
        DanmakuItem(time, mode, 25, color, text, weight = weight)

    @Test fun weightHashParsingAndMonotonicLevels() {
        val elem = byteArrayOf(0x10, 123, 0x32, 8) + "1234abcd".toByteArray() + byteArrayOf(0x3a, 2, 111, 107, 0x48, 7)
        val parsed = parseDanmakuSeg(byteArrayOf(0x0a, elem.size.toByte()) + elem).single()
        assertEquals(7, parsed.weight); assertEquals("1234abcd", parsed.midHash)
        val source = (0..10).map { item(it, text = "权重$it") }
        var previous = source.toSet()
        for (level in 0..5) {
            val current = filterDanmaku(source, DanmakuFilterOptions(level = level)).toSet()
            assertTrue(previous.containsAll(current)); previous = current
            if (level in 1..4) assertTrue(current.any { it.weight == 0 })
        }
        assertEquals(setOf(9, 10), previous.map { it.weight }.toSet())
        assertEquals(0, parseDanmakuSeg(byteArrayOf(0x0a, (elem.size-2).toByte()) + elem.dropLast(2)).single().weight)
    }

    @Test fun typeCloudRepeatAndBoundedRegexHaveActualConsumers() {
        val source = (1..9).map { item(mode = it) }
        assertEquals(listOf(1,2,3,6), filterDanmaku(source,
            DanmakuFilterOptions(allowTop = false, allowBottom = false)).map { it.mode })
        assertTrue(filterDanmaku(listOf(item(color = 0xFF0000)), DanmakuFilterOptions(allowColor = false)).isEmpty())
        val repeats = listOf(0L,1000L,2000L,10_000L).map { item(time = it) }
        assertEquals(listOf(0L,1000L,10_000L), filterDanmaku(repeats, DanmakuFilterOptions(hideRepeated = true)).map { it.timeMs })
        val cloud = parseDanmakuCloudRules("""{"code":0,"data":{"rule":[{"type":0,"filter":"广告"},{"type":2,"filter":"1234abcd"}]}}""")
        assertTrue(filterDanmaku(listOf(item(text = "广告")), DanmakuFilterOptions(cloud = true), cloudRules = cloud).isEmpty())
        assertEquals(1, filterDanmaku(listOf(item(text = "广告")), DanmakuFilterOptions(), cloudRules = cloud).size)
        assertTrue(cloud.blocks(item().copy(midHash = "1234abcd")))
        assertFalse(parseDanmakuCloudRules("""{"code":0,"data":{"rule":null}}""").blocks(item()))
        for (body in listOf("""{"code":-101}""", """{"code":0,"data":{"rule":"invalid"}}""")) {
            assertTrue(runCatching { parseDanmakuCloudRules(body) }.isFailure)
        }
        val risky = DanmakuRules(regexes = listOf("(a+)+$", "!$"))
        val start = System.nanoTime()
        assertTrue(risky.blocks(item(text = "a".repeat(2000) + "!")))
        assertTrue(risky.rejectedRegexes > 0)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000)
        assertEquals(normalizeDmUser("uid:12345"), normalizeDmUser("uid:12345".uppercase()))
    }

    @Test fun cloudProfileAndAdvancedAnimationRespectMetadataBoundaries() {
        val profile = parseDanmakuCloudProfile(byteArrayOf(0x52,4,0x10,1,0x18,7))
        assertEquals(7, profile.level)
        assertEquals(0, parseDanmakuCloudProfile(byteArrayOf(0x52,2,0x18,7)).level)
        val a = parseAdvancedDanmaku("""[0.1,0.2,"1-0",5,"文字",30,15,0.8,0.9,2000,1000,true,"sans"]""")!!
        assertEquals(5000L, a.durationMs)
        assertEquals(0f, a.moveFraction(500)); assertEquals(.5f, a.moveFraction(2000)); assertEquals(1f, a.moveFraction(4000))
        assertEquals(192f, advancedCoordinate(.1f, 1920, 682f), .001f)
        assertNull(parseAdvancedDanmaku("[1,2,3]"))
        assertNull(parseAdvancedDanmaku("""[0,0,"NaN",5,"文字"]"""))
    }
}
