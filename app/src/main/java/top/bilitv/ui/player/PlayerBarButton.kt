package top.bilitv.ui.player

/** 设置页与播放页共用目录；返回由遥控器返回键处理，设置／日志保留在其他入口。 */
enum class PlayerBarButton(val id: String, val label: String) {
    PLAY("play", "播放 / 暂停"),
    SPEED("speed", "倍速"),
    QUALITY("quality", "画质"),
    DANMAKU("danmaku", "弹幕开关"),
    SUBTITLE("subtitle", "字幕开关"),
    LIKE("like", "点赞（长按三连）"),
    COIN("coin", "投币"),
    FAVORITE("favorite", "收藏"),
    UP("up", "UP 主"),
    LINE("line", "直播线路"),
    ;
    val pinned: Boolean get() = this == PLAY
    companion object {
        val DEFAULT: List<PlayerBarButton> = entries.toList()
        fun byId(id: String): PlayerBarButton? = entries.firstOrNull { it.id == id }
        fun parse(ids: List<String>): List<PlayerBarButton> {
            val list = ids.mapNotNull { byId(it) }.distinct()
            if (list.isEmpty()) return DEFAULT
            return if (PLAY in list) list else list + PLAY
        }
        /** 仅升级时补新增项，保留已有排序；日后隐藏新增按钮不会被反复补回。 */
        fun migrateLegacy(ids: List<String>): List<String> =
            if (ids.isEmpty()) DEFAULT.map { it.id }
            else (parse(ids) + listOf(QUALITY, LIKE, COIN, FAVORITE, UP, LINE)).distinct().map { it.id }
    }
}
