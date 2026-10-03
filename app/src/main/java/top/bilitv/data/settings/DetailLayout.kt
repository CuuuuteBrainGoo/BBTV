package top.bilitv.data.settings

/** The play header stays reachable; optional sections keep their selected order. */
object DetailLayout {
    val ALL = listOf("HERO", "PARTS", "DESC")
    fun sections(ids: List<String>) = listOf("HERO") + ids.filter { it in ALL && it != "HERO" }.distinct()
    fun label(id: String) = when (id) { "HERO" -> "封面与播放（固定）"; "PARTS" -> "分P"; else -> "简介" }
}
