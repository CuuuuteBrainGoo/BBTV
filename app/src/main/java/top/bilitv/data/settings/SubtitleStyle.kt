package top.bilitv.data.settings

data class SubtitleStyle(
    val font: Int = 2, val background: Int = 60,
) {
    companion object {
        val FONTS = listOf("极小", "小", "中", "大", "极大")
        val FONT_SP = listOf(14f, 18f, 22f, 28f, 36f)
    }
}
