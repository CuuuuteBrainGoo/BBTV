package top.bilitv.data.settings

/**
 * 播放倍速与画面比例的**纯逻辑**：档位、步进、循环、以及"存进去的是什么"。
 *
 * ## 为什么单独抽一个对象
 *
 * 这两项都会"静默出错"，而且错了看不出来：
 * - 倍速档位是个浮点数。`1.1f * 3` 不等于 `3.3f`（IEEE754），
 *   按"每次 +0.1"实现，加到 3.0 之后会得到 `2.9999998`，显示成 `2.9x`。
 *   所以档位必须**预置成表**、按下标取，不做浮点累加。见 [SPEEDS]。
 * - 画面比例存的是**字符串 id 不是 Media3 的 int 常量值** ——
 *   理由同 [ThemeSkin]：常量值是库内部的，升级 Media3 时万一变了，
 *   老用户存的值就会指向另一个模式。字符串 id 只由我们定义，稳定。
 *
 * 抽出来之后这两个函数可以纯 JVM 单测（`SettingsLogicTest` 同款）。
 */
object PlaybackTuning {

    fun resetKeys(keys: Set<String>) = keys.filter { it.startsWith("screenshot_") || it.startsWith("touch_") || it.startsWith("up_speed_") || it.startsWith("player_key_") || it in setOf(
        "preferred_quality", "preferred_audio_quality", "playback_performance", "seek_seconds", "auto_lower_quality", "prefer_hevc", "auto_next", "playback_end_action",
        "detail_page_on", "return_details_on_exit", "ask_resume", "single_back_exit", "player_up_enabled", "player_down_enabled", "player_up_action", "player_down_action",
        "playback_speed_index", "aspect_mode", "subtitle_on", "remember_up_speed", "player_show_progress", "player_pause_icon",
        "player_hide_controls_start", "player_progress_time", "skip_official_intro_outro") }

    enum class EndAction(val label: String) {
        PAUSE("暂停播放"), NEXT("自动播放下一集 / 下一P"), LOOP("循环当前视频");
        companion object {
            fun of(id: String?, oldAutoNext: Boolean) = entries.firstOrNull { it.name == id }
                ?: if (oldAutoNext) NEXT else PAUSE
        }
    }

    enum class SideAction(val label: String) {
        CATALOGUE("分P与播放列表"), RECOMMEND("推荐视频"), UP_UPLOADS("UP主投稿");
        companion object {
            fun of(id: String?, fallback: SideAction) = entries.firstOrNull { it.name == id } ?: fallback
        }
    }

    val SEEK_SECONDS = listOf(5, 10, 15, 20)
    fun seekSeconds(stored: Int): Int = stored.takeIf { it in SEEK_SECONDS } ?: 10
    /** 长按加速只改变预览位置，松手才向播放器提交最终目标。 */
    fun holdSeekStep(baseMs: Long, heldMs: Long): Long = baseMs * when {
        heldMs >= 8_000L -> 12
        heldMs >= 4_000L -> 6
        heldMs >= 2_000L -> 3
        else -> 1
    }

    /**
     * 倍速档位表。**不做浮点运算，只查表。**
     *
     * 上限 2.0：再快就不适合看正片了（2 倍以上人声已经听不清），
     * 而下限 0.5 是留给"慢放看细节"的。档位少而准，比"能滑到 3.7x"有用。
     */
    val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    /** 默认倍速的下标（1.0x 在表里的位置） */
    val DEFAULT_SPEED_INDEX = SPEEDS.indexOf(1.0f)

    /**
     * 取档位下标。**认不出来的一律回落到默认**（不抛异常、不返回 -1）。
     *
     * 这样做的理由：这个值来自 SharedPreferences，可能是老版本写的、
     * 也可能是手改过。宁可静默用 1.0x，也不能让播放页崩在启动时。
     */
    fun speedIndexOf(stored: Int): Int =
        if (stored in SPEEDS.indices) stored else DEFAULT_SPEED_INDEX

    /** 下标 → 倍速值。越界的调用方自己负责，但这里仍兜一手，不让 `[]` 抛出去 */
    fun speedOf(index: Int): Float = SPEEDS[speedIndexOf(index)]

    /**
     * 循环切下一档。到末尾回到第一档。
     *
     * 为什么是**循环**而不是"到头停住"：遥控器上按一下就该有反应，
     * 到头停住会让用户以为"按坏了"。循环的代价只是多按几下，
     * 比"没反应"好判断得多。
     */
    fun nextSpeedIndex(index: Int): Int = (speedIndexOf(index) + 1) % SPEEDS.size

    /** 显示文本，如 `1.0x`。用于按钮和提示 */
    fun speedLabel(index: Int): String = formatSpeed(speedOf(index))

    /**
     * 倍速的显示文本。**手写而不是 String.format("%.2f")** ——
     * 后者会把 1.0 显示成 `1.00x`、0.75 显示成 `0.75x`，宽度不一致，
     * 按钮文字会跳。这里统一成"最多两位、去掉末尾 0"。
     */
    fun formatSpeed(speed: Float): String {
        val hundredths = Math.round(speed * 100)
        val s = when {
            hundredths % 100 == 0 -> "${hundredths / 100}.0"
            hundredths % 10 == 0 -> "${hundredths / 100}.${hundredths % 100 / 10}"
            else -> "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
        }
        return "${s}x"
    }

    // ------------------------------------------------------------ 画面比例

    /**
     * 画面比例档位。`id` 是**我们自己定义的稳定字符串**，落到 Media3 常量由 [resizeModeOf] 翻。
     *
     * | id | 显示 | 效果 |
     * |---|---|---|
     * | `fit` | 适应 | 保持比例、留黑边（**默认**，Media3 原生行为） |
     * | `fill` | 拉伸 | 拉满全屏、比例失真（老 4:3 片源铺满用） |
     * | `zoom` | 裁切 | 保持比例、裁掉溢出部分（去掉黑边） |
     */
    val ASPECTS = listOf(
        AspectOption("fit", "适应（留黑边）"),
        AspectOption("fill", "拉伸（铺满）"),
        AspectOption("zoom", "裁切（去黑边）"),
    )

    /** 默认档位 = `fit`。它等于 Media3 的原生默认，所以"没动过设置的人"行为零变化 */
    const val DEFAULT_ASPECT_ID = "fit"

    /** 按 id 取档位；认不出来回落默认。存的是 id，所以升级 Media3 也不会错位 */
    fun aspectOf(id: String?): AspectOption =
        ASPECTS.firstOrNull { it.id == id } ?: ASPECTS.first { it.id == DEFAULT_ASPECT_ID }

    /** 下一次循环到的档位 id（同倍速：循环，不让按键"没反应"） */
    fun nextAspectId(id: String?): String {
        val cur = aspectOf(id)
        val i = ASPECTS.indexOfFirst { it.id == cur.id }
        return ASPECTS[(i + 1) % ASPECTS.size].id
    }
}

/**
 * 一个画面比例档位。
 *
 * @param id 落盘用的稳定字符串（**不是** Media3 的 int 常量）
 * @param label 显示文本
 */
data class AspectOption(val id: String, val label: String)
