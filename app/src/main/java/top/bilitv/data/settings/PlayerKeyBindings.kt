package top.bilitv.data.settings

enum class RemoteAction(val label: String) {
    NONE("不响应"), PLAY("播放 / 暂停"), CONTROLS("播放器控制条"), SETTINGS("播放设置侧栏"),
    CATALOGUE("分P与播放列表"), RECOMMEND("相关推荐"), UP_LIST("UP主视频"),
    DANMAKU("弹幕开关"), SUBTITLE("字幕开关"), SPEED("切换倍速"), QUALITY("选择画质"),
    NEXT("下一个视频"), LIKE("点赞"), COIN("投币（默认2枚）"), FAVORITE("收藏"), TRIPLE("一键三连"),
    OPEN_UP("打开UP主空间"), BOOST("按住临时2倍速"),
    PREVIOUS("上一个视频"), REFRESH("刷新播放地址"), COMMENTS("评论侧栏");

    companion object { fun of(id: String?) = if (id in listOf("SCREENSHOT", "SHARE", "STATS")) NONE else entries.firstOrNull { it.name == id } }
}

object PlayerKeyBindings {
    val BUILT_IN = listOf(23, 19, 20, 82)
    val SHORT_ACTIONS = RemoteAction.entries.filter { it != RemoteAction.BOOST }
    const val HOLD_MS = 1500L
    fun canonical(code: Int) = if (code == 66 || code == 160) 23 else code
    // System navigation, power and volume always retain their Android behavior.
    fun allowed(code: Int) = canonical(code) in 7..288 && code !in setOf(24, 25, 26, 79, 111, 164, 171, 187, 219)
    fun editable(code: Int, long: Boolean) = allowed(code) && canonical(code) !in listOf(21, 22) &&
        (long || canonical(code) !in listOf(23, 82))
    fun label(code: Int) = when (canonical(code)) {
        23 -> "确定键"; 19 -> "上键"; 20 -> "下键"; 21 -> "左键"; 22 -> "右键"; 82 -> "菜单键"
        else -> "按键 $code"
    }
    fun side(action: PlaybackTuning.SideAction?) = when (action) {
        PlaybackTuning.SideAction.CATALOGUE -> RemoteAction.CATALOGUE
        PlaybackTuning.SideAction.RECOMMEND -> RemoteAction.RECOMMEND
        PlaybackTuning.SideAction.UP_UPLOADS -> RemoteAction.UP_LIST
        null -> RemoteAction.NONE
    }
}

/** One physical press: OS repeats do not restart it; long action suppresses the release click. */
class RemoteKeyPress {
    var key = -1; private set
    private var short: RemoteAction? = null
    private var long: RemoteAction? = null
    private var fired = false
    fun start(code: Int, shortAction: RemoteAction?, longAction: RemoteAction?) {
        key = code; short = shortAction; long = longAction; fired = false
    }
    fun fireLong(): RemoteAction? {
        if (key < 0 || fired || long == null || long == RemoteAction.NONE) return null
        fired = true; return long
    }
    fun release(code: Int, cancelled: Boolean = false): RemoteAction? {
        if (code != key) return null
        val action = short.takeUnless { fired || cancelled }
        clear(); return action
    }
    fun clear() { key = -1; short = null; long = null; fired = false }
}
