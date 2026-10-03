package top.bilitv.data.settings

/** Explicitly dark choices keep existing white card text readable. Default follows the skin. */
enum class BackgroundStyle(val label: String, val argb: Long?) {
    DEFAULT("跟随皮肤", null), BLACK("纯黑", 0xFF000000),
    GRAY("深灰", 0xFF191919), BLUE("暗蓝", 0xFF101820), WARM("暖灰", 0xFF201B18);
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: DEFAULT }
}
