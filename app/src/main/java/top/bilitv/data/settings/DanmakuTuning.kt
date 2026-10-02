package top.bilitv.data.settings

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 弹幕细项的**档位与边界** —— 纯逻辑，无 Android 依赖，可单测。
 *
 * ## 为什么单独抽一个对象出来
 *
 * 这几项在**两个地方**都能调：播放页里的弹幕面板（`DanmakuPanel`）和
 * 设置页的高级模式。抽出来之前，两处各写一套 `-0.1f` / `coerceIn(…20)`，
 * 于是同一个设置在两个界面上会**走到不同的值**（比如行数一处 0/12/14…20，
 * 另一处 0/2/4…20），用户看到的是"同一个开关，在两个页面按一下效果不一样"。
 *
 * 更要紧的是 [SettingsStore] 的落盘夹取也在做同一件事 —— 三处各写一遍边界值，
 * 改一处忘两处是迟早的事。现在**边界只有这一个定义**，其余全部调它。
 *
 * ## 单位约定
 *  - 不透明度 / 字号：倍数（`1f` = 100%），不是百分数
 *  - 速度：**滚动弹幕横穿屏幕所需的毫秒数**，越大越慢
 *  - 行数：`0` = 不限；其余取偶数档
 */
object DanmakuTuning {

    /** 旧八档按比例迁移；8/8 = 5/5，最低档仍至少 1/5。 */
    fun migrateArea(eighths: Int): Int = (eighths.coerceIn(1, 8) * 5 / 8f).roundToInt().coerceIn(1, 5)

    // ---------------------------------------------------------------- 不透明度

    const val ALPHA_MIN = 0.1f
    const val ALPHA_MAX = 1f
    const val ALPHA_STEP = 0.1f

    fun clampAlpha(v: Float): Float = v.coerceIn(ALPHA_MIN, ALPHA_MAX)

    /** 往亮 / 往暗走一档。取整到 [ALPHA_STEP] 的整数倍，避免浮点累积成 `0.7000001` */
    fun stepAlpha(v: Float, up: Boolean): Float {
        val steps = (clampAlpha(v) / ALPHA_STEP).roundToInt()
        val next = if (up) steps + 1 else steps - 1
        return clampAlpha(next * ALPHA_STEP)
    }

    fun alphaLabel(v: Float): String = "${(clampAlpha(v) * 100).toInt()}%"

    // ---------------------------------------------------------------- 字号

    const val SCALE_MIN = 0.5f
    const val SCALE_MAX = 2f
    const val SCALE_STEP = 0.1f

    fun clampScale(v: Float): Float = v.coerceIn(SCALE_MIN, SCALE_MAX)

    fun stepScale(v: Float, up: Boolean): Float {
        val steps = (clampScale(v) / SCALE_STEP).roundToInt()
        val next = if (up) steps + 1 else steps - 1
        return clampScale(next * SCALE_STEP)
    }

    fun scaleLabel(v: Float): String = "${(clampScale(v) * 100).toInt()}%"

    // ---------------------------------------------------------------- 显示行数

    /** 上限。再多屏幕就全是弹幕了 */
    const val LINES_MAX = 20
    const val LINES_STEP = 2

    /**
     * 行数**不是**线性档位：`0` 表示"不限"，并且它是**最高**的那一档。
     *
     * 界面上按「+」是"放开一点"，所以 `20` 再往上是 `0`（不限）；
     * 从"不限"按「−」退回 [LINES_MAX]。
     * 这个语义写在一处，两个界面才不会各按各的来。
     */
    fun clampLines(v: Int): Int {
        if (v <= 0) return 0
        val even = v - v % LINES_STEP
        return even.coerceIn(LINES_STEP, LINES_MAX)
    }

    fun stepLines(v: Int, up: Boolean): Int {
        val cur = clampLines(v)
        if (up) {
            // 已经"不限"了，没有更松的档
            if (cur == 0) return 0
            val next = cur + LINES_STEP
            return if (next > LINES_MAX) 0 else next
        }
        if (cur == 0) return LINES_MAX
        return (cur - LINES_STEP).coerceAtLeast(LINES_STEP)
    }

    fun linesLabel(v: Int): String = if (clampLines(v) == 0) "不限" else clampLines(v).toString()

    // ---------------------------------------------------------------- 速度

    const val SPEED_MIN_MS = 3_000L
    const val SPEED_MAX_MS = 20_000L
    const val SPEED_STEP_MS = 1_000L

    fun clampSpeed(ms: Long): Long = ms.coerceIn(SPEED_MIN_MS, SPEED_MAX_MS)

    /**
     * 速度档位。
     *
     * ⚠️ `up = true` 指的是**更慢**（毫秒更大）—— 因为界面上这个 stepper 的
     * 左按钮是「−」、右按钮是「+」，而显示的数值是秒数：秒数变大 = 更慢。
     * 这里按"数值变大"命名，不要按"变快"命名，否则两个界面上加减方向又会反过来。
     */
    fun stepSpeed(ms: Long, up: Boolean): Long {
        val next = clampSpeed(ms) + if (up) SPEED_STEP_MS else -SPEED_STEP_MS
        return clampSpeed(next)
    }

    fun speedLabel(ms: Long): String = String.format(Locale.US, "%.1f", clampSpeed(ms) / 1000f) + "s"

    // ---------------------------------------------------------------- 默认值

    /**
     * 各档默认值。**只在这里定义一次**，`SettingsStore` 的 `getBoolean/getFloat/getInt` 兜底值调它。
     * 默认值散落在 `SettingsStore` 和界面两处时，改了一处就会出现"重置后界面显示的和存的不一样"。
     */
    const val DEF_ALPHA = 0.9f
    const val DEF_SCALE = 1f
    const val DEF_LINES = 0
    const val DEF_SPEED_MS = 8_000L
}
