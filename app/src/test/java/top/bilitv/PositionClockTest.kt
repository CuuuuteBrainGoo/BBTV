package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.ui.player.PositionClock

/**
 * 弹幕位置时钟的单测。
 *
 * 这个测试存在的唯一理由，是**锁住一个已经犯过的错**：
 * 曾经弹幕直接吃"250ms 采一次"的播放位置，一秒 60 帧里只有 4 帧拿到新值，
 * 中间 15 帧钉在原地再猛跳一下 —— 用户看到的就是"弹幕卡顿、移动不连贯"。
 *
 * 光看代码很不容易发现（`position = { vm.positionMs }` 长得毫无破绽），
 * 所以用"逐帧位移"把它钉死：**任何一帧原地不动，就是回归**。
 */
class PositionClockTest {

    /** 假时钟：时间完全由测试推进，`SystemClock` 在 JVM 上是空壳，用不了 */
    private var t = 0L
    private val clock = PositionClock { t }

    private companion object {
        /** 60fps 一帧 ≈ 16.67ms，取整 16 便于断言 */
        const val FRAME_MS = 16L

        /**
         * 每 15 帧采一次 = 240ms，就是真实 ticker 那个 250ms 的采样间隔。
         * 这里不追求精确对齐 250ms —— 要复现的是"低频采样喂 60fps 动画"这个结构，
         * 240 还是 250 对结论没有影响。
         */
        const val FRAMES_PER_SAMPLE = 15L
    }

    // ------------------------------------------------------------ 主用例

    @Test
    fun `采样间隔 250ms 时弹幕位置仍然逐帧推进`() {
        clock.sample(0L)

        var prev = clock.read(playing = true)
        val steps = mutableListOf<Long>()

        repeat(FRAMES_PER_SAMPLE.toInt() * 8) { i ->
            t += FRAME_MS
            // 模拟 ticker：每 250ms 采一次，采到的正好是真实位置
            if ((i + 1) % FRAMES_PER_SAMPLE == 0L) {
                clock.sample((i + 1) * FRAME_MS)
            }
            val cur = clock.read(playing = true)
            steps += cur - prev
            prev = cur
        }

        val frozen = steps.count { it == 0L }
        assertEquals("有 $frozen 帧原地不动 —— 弹幕就是这样卡起来的", 0, frozen)
        assertTrue(
            "每帧位移应当恒等于帧间隔 $FRAME_MS，实际是：$steps",
            steps.all { it == FRAME_MS },
        )
    }

    @Test
    fun `修复前的写法会被这个测试抓住`() {
        // 反证：直接读低频采样值（修复前的行为），必然出现大量 0 位移。
        // 这个用例保证上面的断言真的有能力发现问题，而不是恒真。
        clock.sample(0L)
        var prev = clock.sampledMs
        val steps = mutableListOf<Long>()
        repeat(FRAMES_PER_SAMPLE.toInt() * 4) { i ->
            t += FRAME_MS
            if ((i + 1) % FRAMES_PER_SAMPLE == 0L) clock.sample((i + 1) * FRAME_MS)
            val cur = clock.sampledMs
            steps += cur - prev
            prev = cur
        }
        assertTrue("旧写法本来就该是卡顿的，用例造假了", steps.count { it == 0L } > 0)
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun `暂停时位置定住不动`() {
        clock.sample(1_000L)
        t += 5_000L
        assertEquals(1_000L, clock.read(playing = false))
    }

    @Test
    fun `暂停五分钟再恢复_不会把那五分钟一次性补到位置上`() {
        clock.sample(1_000L)
        t += 300_000L
        assertEquals(1_000L, clock.read(playing = false))
        // 恢复播放：必须从暂停时的位置接着走，而不是窜到 301000
        assertEquals(1_000L, clock.read(playing = true))
        t += 100L
        assertEquals(1_100L, clock.read(playing = true))
    }

    @Test
    fun `暂停期间 ticker 照常采样_恢复播放也无缝`() {
        clock.sample(1_000L)
        // 暂停中 ticker 仍在跑，只是播放器位置不动（这是真实行为）
        repeat(20) {
            t += 250L
            clock.sample(1_000L)
        }
        assertEquals(1_000L, clock.read(playing = true))
        t += 100L
        assertEquals(1_100L, clock.read(playing = true))
    }

    @Test
    fun `拖动进度后立即跟手_不用等到下一次采样`() {
        clock.sample(1_000L)
        t += 300L
        // 用户按了 +10s：seekBy 里紧跟着重采样
        clock.sample(11_000L)
        assertEquals(11_000L, clock.read(playing = true))
    }

    @Test
    fun `换视频 reset 后第一次采样之前返回 0`() {
        clock.sample(500_000L)
        clock.reset()
        assertEquals(0L, clock.read(playing = true))
        assertEquals(0L, clock.read(playing = false))
    }

    @Test
    fun `采样值本身始终是播放器给的那个低频值`() {
        clock.sample(1_234L)
        t += 9_999L
        // 进度文字用的是它：不该被外推污染
        assertEquals(1_234L, clock.sampledMs)
    }
}
