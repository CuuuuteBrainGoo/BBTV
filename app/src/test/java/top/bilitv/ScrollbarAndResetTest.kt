package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.ui.components.scrollbarRange
import top.bilitv.data.settings.PlaybackTuning

class ScrollbarAndResetTest {
    @Test fun emptyAndOverscrolledRangesStayInsideViewport() {
        assertNull(scrollbarRange(0f, 0, 100))
        assertNull(scrollbarRange(Float.NaN, 10, 100))
        val start = scrollbarRange(-5f, 10, 100)!!
        assertEquals(0f, start.top, .0001f)
        assertEquals(.1f, start.length, .0001f)
        val end = scrollbarRange(1000f, 10, 100)!!
        assertEquals(1f, end.top + end.length, .0001f)
        assertTrue(scrollbarRange(0f, 100, 100)!!.length < 1f)
    }
    @Test fun playbackResetPreservesAccountHistoryAndOtherCategories() {
        val cleared = setOf("preferred_quality", "subtitle_on", "playback_end_action", "touch_seek", "up_speed_123", "remember_up_speed", "ask_resume", "return_details_on_exit")
        val kept = setOf("cookie", "token", "history", "ui_font_scale", "danmaku_scale", "subtitle_language", "decoder_name", "theme_skin")
        assertEquals(cleared, PlaybackTuning.resetKeys(cleared + kept).toSet())
    }
}
