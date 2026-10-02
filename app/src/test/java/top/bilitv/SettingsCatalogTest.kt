package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.settings.SettingsCatalog
import top.bilitv.data.settings.SettingsCatalog.SettingsCategory
import top.bilitv.data.settings.SettingsCatalog.SettingsItem
import top.bilitv.ui.components.NavigationFocus

class SettingsCatalogTest {
    @Test fun startupTargetsDoNotStealNavigationOrOpenHiddenPages() {
        val visible = top.bilitv.ui.NavTab.parse(listOf("HOME", "SEARCH", "MINE", "SETTINGS"))
        assertEquals(top.bilitv.ui.NavTab.HOME, top.bilitv.ui.NavTab.startup("CINEMA", visible))
        assertEquals(top.bilitv.ui.NavTab.SEARCH, top.bilitv.ui.NavTab.startup("SEARCH", visible))
        assertEquals(top.bilitv.ui.NavTab.MINE, top.bilitv.ui.NavTab.startup("FAV", visible))
        assertEquals(top.bilitv.ui.NavTab.HOME, top.bilitv.ui.NavTab.startup("unknown", visible))
        val role = top.bilitv.data.settings.StartupFocus.TABS
        assertEquals(top.bilitv.data.settings.StartupFocus.RAIL, top.bilitv.ui.NavTab.HISTORY.startupFocus(role))
        assertEquals(role, top.bilitv.ui.NavTab.HOME.startupFocus(role))
        assertEquals(top.bilitv.data.settings.StartupFocus.RAIL, top.bilitv.data.settings.StartupFocus.fromName("old"))
        for (target in top.bilitv.data.settings.StartupFocus.entries) {
            val nav = NavigationFocus(target)
            assertTrue(nav.blocksAutoFocus)
            top.bilitv.data.settings.StartupFocus.entries.forEach { assertEquals(it == target, nav.allowsRequest(it)) }
            nav.pendingStartup = null
            nav.tabsFocused = true
            assertFalse(nav.allowsRequest(top.bilitv.data.settings.StartupFocus.CONTENT))
            nav.tabsFocused = false
            assertTrue(nav.allowsRequest(top.bilitv.data.settings.StartupFocus.CONTENT))
        }
    }

    @Test fun navigationFocusBlocksEveryLateRequestUntilUserLeaves() {
        val focus = NavigationFocus()
        assertFalse(focus.blocksAutoFocus)
        focus.railFocused = true
        repeat(3) { assertTrue(focus.blocksAutoFocus) }
        focus.tabsFocused = true
        focus.railFocused = false
        assertTrue(focus.blocksAutoFocus)
        focus.tabsFocused = false
        assertFalse(focus.blocksAutoFocus)
    }

    @Test fun categoriesCoverEverySettingOnce() {
        val items = SettingsCategory.entries.flatMap { SettingsCatalog.itemsIn(it, true) }
        assertEquals(SettingsItem.entries.size, items.size)
        assertEquals(SettingsItem.entries.toSet(), items.toSet())
        assertEquals(SettingsCategory.entries.size, SettingsCategory.entries.map { it.label }.toSet().size)
    }

    @Test fun basicModeHasNoEmptyOrAdvancedCategories() {
        val categories = SettingsCatalog.visibleCategories(false)
        assertTrue(SettingsCategory.DEFAULT in categories)
        assertFalse(SettingsCategory.TUNING in categories)
        assertFalse(SettingsCategory.STORAGE in categories)
        categories.forEach {
            assertTrue(SettingsCatalog.itemsIn(it, false).isNotEmpty())
            assertTrue(SettingsCatalog.itemsIn(it, false).none { item -> item.advancedOnly })
        }
    }

    @Test fun advancedModeKeepsSponsorAndDanmakuControlsReachable() {
        assertTrue(SettingsItem.SPONSOR_CATEGORIES in SettingsCatalog.itemsIn(SettingsCategory.AD, true))
        assertFalse(SettingsItem.SPONSOR_CATEGORIES in SettingsCatalog.itemsIn(SettingsCategory.AD, false))
        assertTrue(SettingsItem.DANMAKU_ALPHA in SettingsCatalog.itemsIn(SettingsCategory.DANMAKU, true))
        assertTrue(SettingsItem.SPEED_DEFAULT in SettingsCatalog.itemsIn(SettingsCategory.PLAYBACK, true))
        assertTrue(SettingsCatalog.itemsIn(SettingsCategory.STORAGE, false).isEmpty())
    }

    @Test fun subtitlesHaveBasicPlaybackSwitchAndOptionalToolbarButton() {
        assertTrue(SettingsItem.SUBTITLE_ON in SettingsCatalog.itemsIn(SettingsCategory.PLAYBACK, false))
        val button = top.bilitv.ui.player.PlayerBarButton.SUBTITLE
        assertFalse(button.pinned)
        assertTrue(button in top.bilitv.ui.player.PlayerBarButton.parse(listOf("play", "subtitle")))
        assertFalse(button in top.bilitv.ui.player.PlayerBarButton.parse(listOf("play")))
    }
}
