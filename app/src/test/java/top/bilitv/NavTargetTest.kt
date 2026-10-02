package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.ui.Screen

/**
 * 「点视频卡去哪一页」这个开关的方向测试（2026-09-30，少爷实机反馈 7）。
 *
 * ## 为什么这个开关值得单测
 *
 * 它是一个**布尔量决定两条完全不同的路**的地方，而写反了在界面上**看不出来**：
 *
 * - 写成"开 = 跳过详情"，用户打开开关 → 还是直接进播放 → 他会觉得"开关没生效"
 *   （而不是"开关反了"），于是反复开关、重启、最后报"你这个开关是假的"。
 * - 写成"关 = 进详情"，等于**少爷要的默认行为根本没发生** —— 这正是本轮
 *   核对台账时抓出来的原始问题（`docs/38` §5 任务 28：「详情页默认关」）。
 *
 * 所以这里钉两件事：**方向**（哪一端去详情）和 **`cid` 必须是 0**。
 *
 * ## `cid = 0` 为什么也必须钉
 *
 * 跳过详情页的唯一技术代价是"没人告诉你 cid"。敢填 0 的前提是
 * `PlayerViewModel.load` 的 UGC 分支会自己补（`realCid = if (cid != 0L) cid else detail?.cid ?: 0L`）。
 * 假如哪天有人"顺手"把这里改成从别处取 cid、或者把播放页那个兜底删了，
 * 这个用例会立刻变成红的 —— 比等用户报"点卡进去黑屏"早一步。
 */
class NavTargetTest {

    private val bvid = "BV1xx411c7mD"

    /** 开关**关**（默认）= 跳过详情页，直接进播放。这是少爷要的默认行为。 */
    @Test
    fun detailOffGoesStraightToPlayer() {
        val target = Screen.targetForVideoCard(detailPageEnabled = false, bvid = bvid)
        assertTrue("关掉详情页之后应当是播放页，实际=$target", target is Screen.Player)
        target as Screen.Player
        assertEquals(bvid, target.bvid)
        // 必须留 0：播放页会自己拉 view 补 cid，这里填一个假 cid 会把播放引到错的分段
        assertEquals(0L, target.cid)
        // 不是 PGC、不是直播
        assertEquals(0L, target.epId)
        assertEquals(0L, target.seasonId)
    }

    /** 开关**开** = 恢复原行为，先进详情页。 */
    @Test
    fun detailOnGoesToDetailScreen() {
        val target = Screen.targetForVideoCard(detailPageEnabled = true, bvid = bvid)
        assertEquals(Screen.Detail(bvid), target)
    }

    /** 两个方向必须**互斥** —— 不能出现"两种设置都进播放"这种静默失效。 */
    @Test
    fun theTwoDirectionsAreDifferent() {
        val off = Screen.targetForVideoCard(false, bvid)
        val on = Screen.targetForVideoCard(true, bvid)
        assertTrue("开关两端必须走不同的页面，实际都是 $off", off != on)
    }

    /** `cid = 0` 的空 bvid 防御：bvid 原样透传，不做任何"聪明"的加工。 */
    @Test
    fun bvidIsPassedThroughVerbatim() {
        val weird = "BV1  weird/../bv"
        assertEquals(Screen.Detail(weird), Screen.targetForVideoCard(true, weird))
        assertEquals(
            weird,
            (Screen.targetForVideoCard(false, weird) as Screen.Player).bvid,
        )
    }
}
