package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.ui.player.touchSeekTarget
import top.bilitv.ui.player.touchDragAxis
import top.bilitv.ui.settings.reorderPick
import top.bilitv.ui.home.HomeSection

class PlaybackTouchTest {
    @Test fun diagonalGesturesWaitForClearDirection() {
        assertEquals(0, touchDragAxis(20f, 20f))
        assertEquals(0, touchDragAxis(0f, 0f))
        assertEquals(1, touchDragAxis(-40f, 20f))
        assertEquals(2, touchDragAxis(20f, -40f))
    }
    @Test fun dragSeekIsBoundedAndRelative() {
        assertEquals(70_000L, touchSeekTarget(10_000, 500f, 1000f, 600_000))
        assertEquals(0L, touchSeekTarget(10_000, -500f, 1000f, 600_000))
        assertEquals(60_000L, touchSeekTarget(50_000, 1000f, 1000f, 60_000))
        assertEquals(10_000L, touchSeekTarget(10_000, 100f, 0f, 60_000))
    }
    @Test fun reorderingPreservesLockedSlotsAndHiddenItems() {
        val ids = listOf("home", "search", "live", "settings")
        assertEquals(listOf("home", "live", "search", "settings"), reorderPick(ids, "search", "live", setOf("home", "settings")))
        assertEquals(ids, reorderPick(ids, "search", "home", setOf("home", "settings")))
        assertEquals(ids, reorderPick(ids, "search", "hidden", emptySet()))
        assertEquals(ids, reorderPick(ids, "home", "settings", setOf("home", "settings")))
    }
    @Test fun mandatoryHomeSectionsSurviveLegacyHiddenConfiguration() {
        assertEquals(listOf(HomeSection.MUSIC, HomeSection.RECOMMEND, HomeSection.POPULAR), HomeSection.parse(listOf("music")))
        assertEquals(listOf(HomeSection.POPULAR, HomeSection.MUSIC, HomeSection.RECOMMEND), HomeSection.parse(listOf("popular", "music", "recommend")))
    }
}
