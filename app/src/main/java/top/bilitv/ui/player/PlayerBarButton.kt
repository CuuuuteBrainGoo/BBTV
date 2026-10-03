package top.bilitv.ui.player

import top.bilitv.R

/** 设置页与播放页共用目录；显示标签与持久化 ID 分离。 */
enum class PlayerBarButton(val id: String, val labelRes: Int, val defaultVisible: Boolean = true) {
    PLAY("play", R.string.remote_play),
    SPEED("speed", R.string.player_speed),
    QUALITY("quality", R.string.player_quality),
    DANMAKU("danmaku", R.string.settings_danmaku_toggle),
    SUBTITLE("subtitle", R.string.settings_subtitle),
    LIKE("like", R.string.player_like_hold),
    COIN("coin", R.string.player_coin),
    FAVORITE("favorite", R.string.remote_favorite),
    UP("up", R.string.player_creator),
    LINE("line", R.string.player_live_source),
    SETTINGS("settings", R.string.setting_playback),
    COMMENTS("comments", R.string.player_comments),
    CATALOGUE("catalogue", R.string.remote_catalogue, false),
    RECOMMEND("recommend", R.string.remote_recommend, false),
    PREVIOUS("previous", R.string.remote_previous, false),
    NEXT("next", R.string.remote_next, false),
    REFRESH("refresh", R.string.remote_refresh, false),
    LOOP("loop", R.string.end_loop, false),
    ;
    val pinned: Boolean get() = this == PLAY
    companion object {
        val DEFAULT: List<PlayerBarButton> = entries.filter { it.defaultVisible }
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

        fun upgrade(ids: List<String>, schema: Int): List<String> {
            val retained = ids.filter { it !in listOf("screenshot", "stats") }
            if (schema >= 5) return retained
            if (ids.isEmpty()) return DEFAULT.map { it.id }
            val legacy = if (schema < 1) migrateLegacy(ids) else if (schema < 2) ids + listOf("up", "line") else ids
            val additions = if (schema < 3) listOf("subtitle", "settings", "comments")
                else if (schema < 5) listOf("settings", "comments") else emptyList()
            return (legacy + additions).filter { it !in listOf("screenshot", "stats") }.distinct()
        }
    }
}
