package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.model.DashStream
import top.bilitv.player.StreamSelector

class AdaptiveQualityTest {
    @Test fun sustainedDropsLowerPixelsWithoutChoosingUnsupportedOrHigherFrameRate() {
        assertFalse(StreamSelector.shouldLowerQuality(50, 1_000, 30f))
        assertFalse(StreamSelector.shouldLowerQuality(37, 47_000, 30f))
        assertTrue(StreamSelector.shouldLowerQuality(50, 8_000, 25f))
        assertFalse(StreamSelector.shouldLowerQuality(0, 10_000, Float.NaN))
        fun stream(q: Int, w: Int, h: Int) = DashStream(q, "hvc1", 1, w, h, "", emptyList())
        val current = stream(112, 1920, 1080)
        val videos = listOf(current, stream(80, 1920, 1080), stream(74, 1280, 720),
            stream(64, 1280, 720), stream(32, 854, 480))
        assertEquals(64, StreamSelector.lowerResolution(videos, current) { true }?.qualityId)
        assertEquals(32, StreamSelector.lowerResolution(videos, current) { it.height < 720 }?.qualityId)
        assertNull(StreamSelector.lowerResolution(videos, videos.last()) { true })
    }
}
