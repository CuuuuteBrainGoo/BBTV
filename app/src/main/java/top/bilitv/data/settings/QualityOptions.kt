package top.bilitv.data.settings

/** 默认档位是偏好，播放页仍只列服务端实际返回且设备可解的流；缺档／不可解时向下选择。 */
data class QualityOption(val id: Int, val label: String)

object QualityOptions {

    /** 「自动」的 id。**必须**是 `0` —— `StreamSelector` 用 `> 0` 判断"用户是否指定了档位" */
    const val AUTO_ID = 0

    val AUTO = QualityOption(AUTO_ID, "自动")

    /**
     * 按清晰度**从高到低**排。界面直接照这个顺序铺 chip，
     * 用户看到的顺序和"往左按就是降清晰度"一致。
     */
    val ALL: List<QualityOption> = listOf(
        AUTO,
        QualityOption(127, "8K"),
        QualityOption(126, "杜比视界"),
        QualityOption(125, "HDR"),
        QualityOption(120, "4K"),
        QualityOption(116, "1080P60"),
        QualityOption(112, "1080P高码率"),
        QualityOption(80, "1080P"),
        QualityOption(74, "720P60"),
        QualityOption(64, "720P"),
        QualityOption(32, "480P"),
        QualityOption(16, "360P"),
    )

    /**
     * id → 显示名。
     *
     * 认不出来 **回落成「自动」而不是空串** —— 存量的 `preferredQuality` 可能是
     * 老版本或者别处写进去的值，界面上显示空 chip 比显示"自动"更让人困惑。
     */
    fun labelOf(id: Int): String = ALL.firstOrNull { it.id == id }?.label ?: AUTO.label

    /** 是不是"用户明确指定了档位"。`StreamSelector` 的判据和这里保持一致 */
    fun isExplicit(id: Int): Boolean = id > AUTO_ID

    /** 播放按钮显示实际档位；886 高的宽银幕视频仍是 1080P，不能按裁边高度误标。 */
    fun compactLabel(id: Int, sourceLabel: String = "", height: Int = 0): String = when (id) {
        16 -> "360P"; 32 -> "480P"; 64 -> "720P"; 74 -> "720P60"
        80 -> "1080P"; 112 -> "1080P+"; 116 -> "1080P60"
        120 -> "4K"; 125 -> "HDR"; 126 -> "杜比"; 127 -> "8K"
        0 -> "…"
        else -> Regex("(?i)(8K|4K|1080P(?:60|\\+)?|720P(?:60)?|480P|360P)").find(sourceLabel)?.value?.uppercase()
            ?: if (height > 0) "${height}P" else "$id"
    }
}
