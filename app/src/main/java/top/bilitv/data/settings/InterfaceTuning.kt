package top.bilitv.data.settings

/** Only finite, supported choices reach layout; reset never touches account or playback data. */
object InterfaceTuning {
    val FONT = listOf(.85f, 1f, 1.15f, 1.3f)
    val SIDEBAR = listOf(0f, 1f / 3, .5f, 2f / 3)
    val BUTTONS = listOf(.85f, 1f, 1.2f)
    fun font(v: Float) = FONT.firstOrNull { it == v } ?: 1f
    fun sidebar(v: Float) = SIDEBAR.firstOrNull { it == v } ?: 0f
    fun buttons(v: Float) = BUTTONS.firstOrNull { it == v } ?: 1f
    fun appearanceKeys(keys: Set<String>) = keys.filter { it.startsWith("ui_") ||
        it in setOf("theme_skin", "home_sections", "nav_tabs", "player_buttons") }
}
