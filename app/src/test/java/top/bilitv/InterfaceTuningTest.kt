package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.settings.InterfaceTuning

class InterfaceTuningTest {
    @Test fun finishBehaviorPreservesOldAutoNextAndRejectsUnknownValues() {
        assertEquals(top.bilitv.data.settings.PlaybackTuning.EndAction.NEXT,
            top.bilitv.data.settings.PlaybackTuning.EndAction.of(null, true))
        assertEquals(top.bilitv.data.settings.PlaybackTuning.EndAction.PAUSE,
            top.bilitv.data.settings.PlaybackTuning.EndAction.of("unknown", false))
        assertEquals(top.bilitv.data.settings.PlaybackTuning.EndAction.LOOP,
            top.bilitv.data.settings.PlaybackTuning.EndAction.of("LOOP", true))
    }
    @Test fun unsupportedLayoutValuesAndResetStayWithinAppearance() {
        assertEquals(1f, InterfaceTuning.font(Float.NaN))
        assertEquals(1f, InterfaceTuning.font(Float.POSITIVE_INFINITY))
        assertEquals(0f, InterfaceTuning.sidebar(-1f))
        assertEquals(1f, InterfaceTuning.buttons(100f))
        InterfaceTuning.FONT.forEach { assertEquals(it, InterfaceTuning.font(it)) }
        InterfaceTuning.SIDEBAR.forEach { assertEquals(it, InterfaceTuning.sidebar(it)) }
        val appearance = setOf("ui_font_scale", "ui_card_size", "theme_skin", "home_sections", "nav_tabs", "player_buttons")
        val protected = setOf("cookie", "token", "history", "preferred_quality", "danmaku_scale", "subtitle_on", "player_pause_icon")
        assertEquals(appearance, InterfaceTuning.appearanceKeys(appearance + protected).toSet())
    }
}
