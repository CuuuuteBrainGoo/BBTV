package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.bilitv.data.model.PgcEpisode
import top.bilitv.data.model.VideoPage
import top.bilitv.data.settings.PlaybackTuning
import top.bilitv.player.NextEpisode

/**
 * 「播放器三连增强」的纯逻辑测试（2026-09-29）。
 *
 * 三块：倍速档位 / 画面比例档位 / 自动连播的"下一个是谁"。
 *
 * ## 这一组钉的是什么
 *
 * 三块都会**静默出错**，而且错了表面上看不出来：
 *
 * 1. **倍速用浮点累加**（`speed += 0.1f`）→ 加到 3.0 会得到 `2.9999998`，
 *    界面显示 `2.9x`。用户只会觉得"这软件数不对"，不会来报 bug。
 *    → 所以实现改成**查表**，这里钉死"表里的值就是界面显示的值"。
 * 2. **画面比例存 Media3 的 int 常量**→ 库升级时常量挪位，老用户存的值
 *    指向另一个模式（"我设的适应，怎么变拉伸了"）。→ 存字符串 id，这里钉 id 的稳定性。
 * 3. **自动连播的边界**（[NextEpisode]）→ 这是最危险的：算错了不是崩，
 *    而是**播错内容**（最后一集回头播第一集 = 无限重播；当前项找不到跳到第一集 = 从头再来）。
 *    这些"看起来正常"的错，只有单测能挡住。
 */
class PlaybackTuningTest {

    // ============================================================ 倍速

    /**
     * 档位是**查表**不是浮点运算：表里的每个值都必须能原样显示出来。
     *
     * 这条测试是"浮点累加"那个坑的守门员 —— 如果哪天有人把
     * `speedLabel` 改成 `String.format("%.1f", idx * 0.1f)`，这条会立刻红。
     */
    @Test
    fun speedLabelsAreExactAndStable() {
        assertEquals("0.5x", PlaybackTuning.speedLabel(0))
        assertEquals("0.75x", PlaybackTuning.speedLabel(1))
        assertEquals("1.0x", PlaybackTuning.speedLabel(2))
        assertEquals("1.25x", PlaybackTuning.speedLabel(3))
        assertEquals("1.5x", PlaybackTuning.speedLabel(4))
        assertEquals("1.75x", PlaybackTuning.speedLabel(5))
        assertEquals("2.0x", PlaybackTuning.speedLabel(6))
    }

    /**
     * 倍速值本身要和显示文本对得上（显示 `1.25x` 就必须真的设成 1.25）。
     *
     * 浮点比较用 delta —— 0.75f 这类值在 float 里本来就不精确，
     * 用 `assertEquals(0.75f, x)` 两参重载会不稳定。这是 JUnit 用法的规定动作。
     */
    @Test
    fun speedValueMatchesLabel() {
        assertEquals(1.0f, PlaybackTuning.speedOf(2), 0.0001f)
        assertEquals(1.25f, PlaybackTuning.speedOf(3), 0.0001f)
        assertEquals(0.75f, PlaybackTuning.speedOf(1), 0.0001f)
    }

    /**
     * 越界 / 脏数据一律回落 1.0x，**不能抛异常**。
     *
     * 值来自 SharedPreferences，可能是旧版本写的或手改的。
     * 这里抛出去的表现是"播放页打不开"，代价远大于"静默用 1.0x"。
     */
    @Test
    fun badSpeedIndexFallsBackToNormal() {
        assertEquals(PlaybackTuning.DEFAULT_SPEED_INDEX, PlaybackTuning.speedIndexOf(-1))
        assertEquals(PlaybackTuning.DEFAULT_SPEED_INDEX, PlaybackTuning.speedIndexOf(99))
        assertEquals(1.0f, PlaybackTuning.speedOf(-5), 0.0001f)
        assertEquals(1.0f, PlaybackTuning.speedOf(100), 0.0001f)
    }

    /** 循环：到末尾回到开头（不让遥控器按键"没反应"） */
    @Test
    fun speedIndexWrapsAround() {
        assertEquals(0, PlaybackTuning.nextSpeedIndex(PlaybackTuning.SPEEDS.lastIndex))
        assertEquals(1, PlaybackTuning.nextSpeedIndex(0))
        /*
         * 脏数据（99）先被归一化到默认档 1.0x（下标 2），再往后一格 = 3（1.25x）。
         *
         * 注意**不是** 0 —— 这里刻意不用 `% size` 硬算越界值，而是"先归一化、再前进"。
         * 理由：用户从"设置被写坏"的状态里恢复时，最自然的预期是"从 1.0x 开始正常循环"，
         * 而不是"因为存了个 99，所以直接跳到 0.5x"。
         */
        assertEquals(3, PlaybackTuning.nextSpeedIndex(99))
        assertEquals(3, PlaybackTuning.nextSpeedIndex(-1))
    }

    // ============================================================ 画面比例

    /**
     * 存的是**字符串 id**，且 id 是稳定的字面量。
     *
     * 这条测的是"契约"：`SettingsStore.aspectMode` 的 getter/setter 都靠
     * [PlaybackTuning.aspectOf] 做归一化。id 变了老用户的设置就失效。
     */
    @Test
    fun aspectIdsAreStable() {
        assertEquals("fit", PlaybackTuning.ASPECTS[0].id)
        assertEquals("fill", PlaybackTuning.ASPECTS[1].id)
        assertEquals("zoom", PlaybackTuning.ASPECTS[2].id)
        assertEquals("fit", PlaybackTuning.DEFAULT_ASPECT_ID)
    }

    /** 认不出来的 id 回落 fit —— 等价于 Media3 原生默认，所以"没动过设置"的人行为零变化 */
    @Test
    fun unknownAspectFallsBackToFit() {
        assertEquals("fit", PlaybackTuning.aspectOf(null).id)
        assertEquals("fit", PlaybackTuning.aspectOf("").id)
        assertEquals("fit", PlaybackTuning.aspectOf("拉伸").id)
        assertEquals("fill", PlaybackTuning.aspectOf("fill").id)
    }

    /** 循环切换 */
    @Test
    fun aspectWrapsAround() {
        assertEquals("fill", PlaybackTuning.nextAspectId("fit"))
        assertEquals("zoom", PlaybackTuning.nextAspectId("fill"))
        assertEquals("fit", PlaybackTuning.nextAspectId("zoom"))
        // 脏数据也当成 fit 处理
        assertEquals("fill", PlaybackTuning.nextAspectId("nonsense"))
    }

    // ============================================================ 自动连播

    private fun ep(id: Long) = PgcEpisode(
        epId = id, cid = id * 10, number = "$id", longTitle = "第 $id 集",
        cover = "", durationSec = 100,
    )

    private fun pg(cid: Long, index: Int) = VideoPage(cid = cid, index = index, title = "P$index", durationSec = 100)

    /** 正常：返回列表里的下一个 */
    @Test
    fun pgcNextReturnsFollowingEpisode() {
        val list = listOf(ep(1), ep(2), ep(3))
        assertEquals(2L, NextEpisode.nextPgc(list, 1L)?.epId)
        assertEquals(3L, NextEpisode.nextPgc(list, 2L)?.epId)
    }

    /**
     * ★ 最后一集返回 null，**绝不回绕到第一集**。
     *
     * 如果实现用 `list[(i + 1) % size]`，这里会返回 ep1 → 表现是"看完整部剧又从头开始"，
     * 用户会觉得"这软件疯了"。这条是本次最关键的断言。
     */
    @Test
    fun pgcLastEpisodeReturnsNullNotWrapAround() {
        val list = listOf(ep(1), ep(2), ep(3))
        assertNull(NextEpisode.nextPgc(list, 3L))
    }

    /**
     * ★ 当前项不在列表里 → null，**不能因为 `indexOfFirst` 返回 -1 而跳到第一集**。
     *
     * 这是 `-1 + 1 == 0` 那个经典的 off-by-one 陷阱。场景：用户在别处播放了
     * 一个不在本剧列表里的 epId（比如从历史记录直接进来），播完自动连播时
     * 就会错误地播第一集。
     */
    @Test
    fun pgcUnknownCurrentReturnsNull() {
        val list = listOf(ep(1), ep(2), ep(3))
        assertNull(NextEpisode.nextPgc(list, 99L))
        assertNull(NextEpisode.nextPgc(list, 0L))
        assertNull(NextEpisode.nextPgc(emptyList(), 1L))
    }

    /** UGC 多 P：按 cid 匹配，返回下一 P */
    @Test
    fun ugcNextReturnsFollowingPage() {
        val list = listOf(pg(100, 1), pg(200, 2), pg(300, 3))
        assertEquals(200L, NextEpisode.nextPage(list, 100L)?.cid)
        assertEquals(300L, NextEpisode.nextPage(list, 200L)?.cid)
        assertNull(NextEpisode.nextPage(list, 300L))
    }

    /** UGC 同类边界 */
    @Test
    fun ugcEdgeCasesReturnNull() {
        val list = listOf(pg(100, 1), pg(200, 2))
        assertNull(NextEpisode.nextPage(list, 999L))
        assertNull(NextEpisode.nextPage(list, 0L))
        assertNull(NextEpisode.nextPage(emptyList(), 100L))
    }

    /**
     * 单 P（只有一个）也必须是 null —— 否则"只有一个分P的视频"播完会自己重播。
     */
    @Test
    fun singleItemReturnsNull() {
        assertNull(NextEpisode.nextPgc(listOf(ep(1)), 1L))
        assertNull(NextEpisode.nextPage(listOf(pg(100, 1)), 100L))
    }
}
