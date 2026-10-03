package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.danmaku.DanmakuItem
import top.bilitv.data.settings.*
import top.bilitv.ui.player.DanmakuEngine
import top.bilitv.ui.player.DanmakuLayout

class PlaybackResourceAndKeysTest {
    @Test fun `resource modes bound work and retain valid native buffer ordering`() {
        for (mode in PlaybackPerformance.entries) {
            assertTrue(mode.minBufferMs >= 3000)
            assertTrue(mode.maxBufferMs >= mode.minBufferMs)
            assertTrue(mode.danmakuLimit <= 180)
            assertFalse(mode.drawFrame(1_000_000_000, 1_001_000_000))
            assertTrue(mode.drawFrame(1_000_000_000, 1_040_000_000))
        }
        assertEquals(PlaybackPerformance.BALANCED, PlaybackPerformance.of("invalid"))
        val engine = DanmakuEngine<DanmakuLayout>()
        val measure: (DanmakuItem, String) -> DanmakuLayout = { _, _ -> object : DanmakuLayout { override val widthPx = 20 } }
        val crowd = (1..500).map { DanmakuItem(1000, 1, 25, 0xFFFFFF, "text$it") }
        engine.update(0, emptyList(), 1920, 1080, 25, 0, 8000, false, overlap = true, measure = measure, maxActive = 90)
        engine.update(1000, crowd, 1920, 1080, 25, 0, 8000, false, overlap = true, measure = measure, maxActive = 90)
        assertEquals(90, engine.active.size)
    }

    @Test fun `long press fires once never adds short action and cancelled presses do nothing`() {
        val press = RemoteKeyPress()
        press.start(23, RemoteAction.PLAY, RemoteAction.TRIPLE)
        assertEquals(RemoteAction.TRIPLE, press.fireLong())
        assertNull(press.fireLong())
        assertNull(press.release(23))
        press.start(23, RemoteAction.PLAY, RemoteAction.TRIPLE)
        assertNull(press.release(19))
        assertEquals(RemoteAction.PLAY, press.release(23))
        press.start(23, RemoteAction.PLAY, RemoteAction.TRIPLE)
        assertNull(press.release(23, cancelled = true))
        assertNull(press.fireLong())
        press.start(23, RemoteAction.PLAY, RemoteAction.BOOST)
        press.clear()
        assertNull(press.fireLong())
        assertNull(press.release(23))
        assertEquals(23, PlayerKeyBindings.canonical(66))
        assertEquals(23, PlayerKeyBindings.canonical(160))
        for (code in listOf(4, 24, 25, 26, 111, 164, 187, 219, Int.MAX_VALUE)) assertFalse(PlayerKeyBindings.allowed(code))
        for (code in listOf(21, 22, 23, 82)) assertFalse(PlayerKeyBindings.editable(code, false))
        assertTrue(PlayerKeyBindings.editable(23, true))
        assertTrue(PlayerKeyBindings.editable(85, false))
        assertFalse(RemoteAction.BOOST in PlayerKeyBindings.SHORT_ACTIONS)
    }
}
