package top.bilitv.data.settings

data class SubtitleStyle(
    val font: Int = 2, val color: Int = 0, val position: Int = 0,
    val background: Int = 60, val fade: Boolean = false,
    val x: Int = 50, val y: Int = 90,
) {
    companion object {
        val FONTS = listOf("极小", "小", "中", "大", "极大")
        val FONT_SP = listOf(14f, 18f, 22f, 28f, 36f)
        val COLORS = listOf("白", "赤", "橙", "黄", "绿", "青", "蓝", "紫")
        val COLOR_ARGB = listOf(0xFFFFFFFF, 0xFFFF4040, 0xFFFF9800, 0xFFFFEB3B, 0xFF66DD66, 0xFF00DDDD, 0xFF6699FF, 0xFFCC88FF).map { it.toInt() }
        val POSITIONS = listOf("底部居中", "顶部居中", "左上角", "右上角", "左下角", "右下角", "自定义")
    }
}
