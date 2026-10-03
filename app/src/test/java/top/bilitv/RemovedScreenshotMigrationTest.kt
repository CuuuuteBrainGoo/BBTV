package top.bilitv

import org.junit.Assert.*
import org.junit.Test
import top.bilitv.data.settings.RemoteAction
import top.bilitv.data.settings.SettingsCatalog
import top.bilitv.ui.player.PlayerBarButton

class RemovedScreenshotMigrationTest {
    @Test fun removesRetiredButtonWithoutRestoringHiddenButtons() {
        val saved = listOf("quality", "screenshot", "stats", "play")
        for (schema in 5..8) {
            assertEquals(listOf("quality", "play"), PlayerBarButton.upgrade(saved, schema))
        }
        assertFalse(PlayerBarButton.parse(listOf("screenshot")).any { it.id == "screenshot" })
        assertEquals(RemoteAction.NONE, RemoteAction.of("SCREENSHOT"))
        assertEquals(RemoteAction.NONE, RemoteAction.of("SHARE"))
        assertEquals(RemoteAction.NONE, RemoteAction.of("STATS"))
        assertTrue(SettingsCatalog.itemsIn(SettingsCatalog.SettingsCategory.TUNING, false).isEmpty())
        assertTrue(SettingsCatalog.SettingsItem.PLAYBACK_STATS in SettingsCatalog.itemsIn(SettingsCatalog.SettingsCategory.TUNING, true))
    }
}
