package top.bilitv

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.ui.player.seekDeltaFor

/**
 * 遥控器 ← / → 的快进快退步长（2026-09-30，少爷实机反馈 6）。
 *
 * 少爷原话：
 * > 快进10秒和后退10秒不要做成图标在播放器控制栏……快进快退的实现和按键逻辑也抄BT的。
 *
 * 依据 BT 的「按键设置（播放时）」（`docs/37` §4.4）：
 * **左键 = 快退 x 秒、右键 = 快进 x 秒，两项锁死不可改**，步长取 BT 默认「10 秒」。
 *
 * ## 为什么这个两行函数也要单测
 *
 * 它只有三个可能出错的地方，而**三个都不会报错**：
 *
 * | 错法 | 界面上的表现 |
 * |---|---|
 * | **左右反了** | 按左键画面往前走 —— 用户会以为"网络卡了 / 视频跳了"，不会想到是符号写错 |
 * | 步长写成别的数 | 与 BT 的「10 秒」不一致，而界面上除了"手感不对"没有任何线索 |
 * | 把上下键也认成快进快退 | 控制栏隐藏时按 ↓（本该呼出控制栏）反而跳进度 |
 *
 * 第三条尤其险：`Key.DirectionUp/Down` 和 `Left/Right` 在代码里长得一模一样，
 * 一个复制粘贴就串了。
 */
class PlayerKeySeekTest {

    @Test fun configuredSeekAndLongHoldAccelerateWithoutChangingDirection() {
        for (s in listOf(5, 10, 15, 20)) assertEquals(s * 1000L, seekDeltaFor(Key.DirectionRight, s * 1000L))
        assertEquals(10, top.bilitv.data.settings.PlaybackTuning.seekSeconds(999))
        assertEquals(10000L, top.bilitv.data.settings.PlaybackTuning.holdSeekStep(10000L, 1999L))
        assertEquals(-30000L, top.bilitv.data.settings.PlaybackTuning.holdSeekStep(-10000L, 2000L))
        assertEquals(120000L, top.bilitv.data.settings.PlaybackTuning.holdSeekStep(10000L, 8000L))
        assertEquals(listOf(1,1,2,3,3,4,4,5), (1..8).map(top.bilitv.data.settings.DanmakuTuning::migrateArea))
    }

    /** BT 的默认「快进快退秒数 = 10 秒」。改这个数等于改用户手感，必须是一次**刻意**的改动。 */
    private val stepMs = 10_000L

    @Test
    fun leftKeySeeksBackward() {
        val delta = seekDeltaFor(Key.DirectionLeft)
        assertTrue("左键没被识别成快进快退（返回了 null）", delta != null)
        assertEquals(-stepMs, delta!!)
    }

    @Test
    fun rightKeySeeksForward() {
        val delta = seekDeltaFor(Key.DirectionRight)
        assertTrue("右键没被识别成快进快退（返回了 null）", delta != null)
        assertEquals(stepMs, delta!!)
    }

    /** 上下键**不**参与快进快退：↓ 在隐藏态是"呼出控制栏"（BT 的「下键 = 播放面板」）。 */
    @Test
    fun upDownKeysAreNotSeek() {
        assertNull(seekDeltaFor(Key.DirectionUp))
        assertNull(seekDeltaFor(Key.DirectionDown))
    }

    /** 其它键一律不管：确认键在隐藏态只负责唤醒控制栏。 */
    @Test
    fun otherKeysAreNotSeek() {
        assertNull(seekDeltaFor(Key.DirectionCenter))
        assertNull(seekDeltaFor(Key.Enter))
        assertNull(seekDeltaFor(Key.Back))
        assertNull(seekDeltaFor(Key.Menu))
    }

    /**
     * 左右必须是**严格相反**的两个数。
     *
     * 这条是"防我自己"：把 `seekDeltaFor` 改成"两个都返回正数"（比如忘了负号）
     * 上面两条单个断言仍然可能通过（如果 step 也被改了），而这条会当场挂掉。
     */
    @Test
    fun leftAndRightAreExactlyOpposite() {
        val l = seekDeltaFor(Key.DirectionLeft)
        val r = seekDeltaFor(Key.DirectionRight)
        assertEquals("左右必须互为相反数", r, l?.let { -it })
    }
}
