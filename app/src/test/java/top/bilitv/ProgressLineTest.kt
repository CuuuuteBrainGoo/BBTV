package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.ui.player.progressFraction

/**
 * 控制栏进度条的填充比例（2026-09-30，少爷实机反馈 6：「播放器控制栏怎么没有做进度条？」）。
 *
 * ## 为什么这个一行函数也要单测
 *
 * 它算错的时候**界面不会报错，只会难看**：
 *
 * - `duration = 0` 时除零 → `NaN`/`Infinity` → `fillMaxWidth(NaN)` 的结果是
 *   **进度条整条不见**（或铺满），而"时长还没拿到"在播放页的**前几百毫秒是常态**。
 * - `position > duration` → 填充比 `1f` 大 → `fillMaxWidth(1.4f)` **真的会画到容器外面**，
 *   在电视上表现为一条线戳穿屏幕右边。
 *
 * 这两条都属于"编译过、跑得起来、就是画错"，正是本项目最怕的一类缺陷
 * （见 `docs/99` 的头号判据）。
 */
class ProgressLineTest {

    @Test
    fun normalPositionMapsToHalf() {
        assertEquals(0f, progressFraction(0L, 100_000L), 0.0001f)
        assertEquals(0.5f, progressFraction(50_000L, 100_000L), 0.0001f)
        assertEquals(1f, progressFraction(100_000L, 100_000L), 0.0001f)
    }

    /** 时长未知（还没拿到 / 直播）→ 空条，**不是满条**。 */
    @Test
    fun unknownDurationIsEmptyNotFull() {
        assertEquals(0f, progressFraction(0L, 0L), 0.0001f)
        assertEquals(0f, progressFraction(12_345L, 0L), 0.0001f)
        assertEquals(0f, progressFraction(12_345L, -1L), 0.0001f)
    }

    /** 位置超出时长（拖到末尾 / 换线路后时间轴重置）→ 夹到 1，不许溢出。 */
    @Test
    fun positionBeyondDurationIsClamped() {
        assertEquals(1f, progressFraction(120_000L, 100_000L), 0.0001f)
        assertEquals(1f, progressFraction(Long.MAX_VALUE, 1L), 0.0001f)
    }

    /**
     * 负位置（过渡态 `C.TIME_UNSET` 附近会返回小负数）→ 夹到 0，
     * 否则进度条会"倒退一格"闪一下。
     */
    @Test
    fun negativePositionIsClamped() {
        assertEquals(0f, progressFraction(-1L, 100_000L), 0.0001f)
        assertEquals(0f, progressFraction(-999_999L, 100_000L), 0.0001f)
    }

    /** 任何输入都必须落在 `[0, 1]` 内且是有限数（`fillMaxWidth` 不接受 NaN / 无穷）。 */
    @Test
    fun alwaysFiniteAndInRange() {
        val cases = listOf(
            0L to 0L,
            0L to -5L,
            -1L to 0L,
            1L to 1L,
            999_999_999L to 1L,
            0L to Long.MAX_VALUE,
        )
        for ((pos, dur) in cases) {
            val f = progressFraction(pos, dur)
            assertFalse("fraction 必须是有限数：pos=$pos dur=$dur f=$f", f.isNaN() || f.isInfinite())
            assertTrue("fraction 必须落在 [0,1]：pos=$pos dur=$dur f=$f", f in 0f..1f)
        }
    }
}
