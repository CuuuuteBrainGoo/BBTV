package top.bilitv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.bilitv.data.history.History
import top.bilitv.data.history.HistoryEntry

/**
 * 观看记录与续播判据。
 *
 * ## 为什么这几条值得写测试
 *
 * 它们全是**"看起来对、边界上错"**的典型：
 *
 * - "看到哪了"的边界（只看了几秒 / 已经看到片尾）
 * - 200 条上限的淘汰顺序
 * - 高频写进度**不能把标题抹掉**（这是本模块最容易出的一个事故：
 *   历史页上冒出一行没名字的记录）
 *
 * 这些在真机上都要"恰好"才能复现，靠手点验证等于不验证。
 * 参考 `PositionClock` 的教训：那个洞是单测逼出来的，不是想出来的。
 */
class HistoryTest {

    private fun entry(
        key: String,
        progressMs: Long = 0L,
        durationMs: Long = 0L,
        title: String = "",
        cover: String = "",
        at: Long = 0L,
    ) = HistoryEntry(
        key = key,
        bvid = key.substringBefore('#'),
        progressMs = progressMs,
        durationMs = durationMs,
        title = title,
        cover = cover,
        updatedAtSec = at,
    )

    // ---------------------------------------------------------------- key

    @Test
    fun `key 带上 epId，同一部剧不同集不会串`() {
        val e3 = History.keyOf("", 111L, 3L)
        val e4 = History.keyOf("", 222L, 4L)
        assertTrue("两集的 key 不能相同", e3 != e4)

        // 万一两集 cid 相同，epId 必须把它们分开 —— 否则进度会互相覆盖
        val sameCidA = History.keyOf("", 999L, 3L)
        val sameCidB = History.keyOf("", 999L, 4L)
        assertTrue("cid 相同时 epId 必须仍然能区分", sameCidA != sameCidB)
    }

    // ---------------------------------------------------------------- 续播判据

    @Test
    fun `没有记录就不续播`() {
        assertNull(History.resumeTargetMs(null, 600_000L))
    }

    @Test
    fun `只看了几秒不续播`() {
        // 点开看了 3 秒就退了 —— 下次进去应该从头开始，而不是从第 3 秒
        val e = entry("bv1#1#0", progressMs = 3_000L)
        assertNull(History.resumeTargetMs(e, 600_000L))
    }

    @Test
    fun `刚过门槛就续播`() {
        val e = entry("bv1#1#0", progressMs = History.MIN_RESUME_MS)
        assertEquals(
            "刚好等于门槛应当续播",
            History.MIN_RESUME_MS,
            History.resumeTargetMs(e, 600_000L),
        )
    }

    @Test
    fun `正常看到一半，续播到那个位置`() {
        val e = entry("bv1#1#0", progressMs = 120_000L)
        assertEquals(120_000L, History.resumeTargetMs(e, 600_000L))
    }

    @Test
    fun `上次已看到片尾，不续播而是从头开始`() {
        // 看到最后 5 秒退出（多半是直接关掉）。续播会把用户扔在片尾曲里
        val e = entry("bv1#1#0", progressMs = 595_000L)
        assertNull(History.resumeTargetMs(e, 600_000L))
    }

    @Test
    fun `刚好落在片尾门槛上仍然续播`() {
        // progress == total - NEAR_END_MS 是"还没到片尾"，应当续播
        val total = 600_000L
        val e = entry("bv1#1#0", progressMs = total - History.NEAR_END_MS)
        assertEquals(total - History.NEAR_END_MS, History.resumeTargetMs(e, total))
    }

    @Test
    fun `本次时长未知时回落到记录里的时长`() {
        /*
         * 接口没给时长（播放地址解析失败重试中）时，不能因此丢掉"看到片尾"这个判断 ——
         * 否则一个已经看完的视频会被当成"看到一半"。
         */
        val e = entry("bv1#1#0", progressMs = 590_000L, durationMs = 600_000L)
        assertNull("应当用记录里的时长判出'已到片尾'", History.resumeTargetMs(e, 0L))
    }

    @Test
    fun `两边都不知道时长时，只要进度够就续播`() {
        // 时长完全未知，只能信"进度超过 10 秒"这一个条件
        val e = entry("bv1#1#0", progressMs = 60_000L, durationMs = 0L)
        assertEquals(60_000L, History.resumeTargetMs(e, 0L))
    }

    // ---------------------------------------------------------------- 合并与上限

    @Test
    fun `同一条重复写入会被挪到最前面`() {
        val a = entry("A", at = 1L)
        val b = entry("B", at = 2L)
        val list = listOf(a, b)

        val merged = History.merge(list, entry("A", progressMs = 5_000L, at = 3L))

        assertEquals("最新的排第一", "A", merged[0].key)
        assertEquals("B 还在", "B", merged[1].key)
        assertEquals("不是三条（同 key 要替换）", 2, merged.size)
        assertEquals(5_000L, merged[0].progressMs)
    }

    @Test
    fun `超过上限时淘汰最旧的`() {
        var list = emptyList<HistoryEntry>()
        // 造 205 条，key 递增；merge 每次都把新的放最前面 → 最后应剩最后 200 条
        repeat(205) { i ->
            list = History.merge(list, entry("k$i", at = i.toLong()))
        }
        assertEquals(History.MAX_ENTRIES, list.size)
        assertEquals("最新的在最前", "k204", list.first().key)
        assertEquals("最旧的 5 条被淘汰", "k5", list.last().key)
    }

    @Test
    fun `key 为空的记录不会污染列表`() {
        val list = listOf(entry("A"))
        assertSame(list, History.merge(list, entry("")))
    }

    // ---------------------------------------------------------------- 字段合并

    @Test
    fun `写进度不会把标题抹掉`() {
        /*
         * ★ 这是本模块最容易出的事故。
         *
         * 进度是每 5 秒写一次的（高频），而标题只在 load() 时拿得到一次。
         * 如果"新的整条覆盖旧的"，那么第二次进度写入就会把标题清空 ——
         * 历史页上会出现一行没名字的记录，而代码看起来完全正常。
         */
        val old = entry("A", title = "【鬼灭之刃】第三集", cover = "https://x/a.jpg", at = 100L).copy(badge = "充电视频")
        val progressWrite = entry("A", progressMs = 30_000L, at = 105L)

        val merged = History.mergeFields(old, progressWrite)

        assertEquals("标题必须保住", "【鬼灭之刃】第三集", merged.title)
        assertEquals("封面必须保住", "https://x/a.jpg", merged.cover)
        assertEquals("角标必须保住", "充电视频", merged.badge)
        assertEquals("进度取新的", 30_000L, merged.progressMs)
        assertEquals("时间取新的", 105L, merged.updatedAtSec)
    }

    @Test
    fun `给了新标题就用新的`() {
        val old = entry("A", title = "旧标题")
        val merged = History.mergeFields(old, entry("A", title = "新标题"))
        assertEquals("新标题", merged.title)
    }

    @Test
    fun `没有旧记录时原样返回`() {
        val e = entry("A", title = "标题")
        assertSame(e, History.mergeFields(null, e))
    }

    @Test
    fun `时长为 0 的新记录不会覆盖旧的时长`() {
        val old = entry("A", durationMs = 600_000L)
        val merged = History.mergeFields(old, entry("A", progressMs = 10_000L, durationMs = 0L))
        assertEquals(600_000L, merged.durationMs)
    }

    // ---------------------------------------------------------------- JSON

    @Test
    fun `编解码往返不丢字段`() {
        val list = listOf(
            HistoryEntry(
                key = "BV1xx411c7mD#123456#0",
                bvid = "BV1xx411c7mD",
                cid = 123_456L,
                epId = 0L,
                title = "带中文、符号 & 与换行\n的标题",
                cover = "https://i0.hdslb.com/bfs/archive/x.jpg",
                owner = "某个UP主",
                progressMs = 83_500L,
                durationMs = 601_000L,
                updatedAtSec = 1_790_000_000L,
                badge = "充电视频",
            ),
            HistoryEntry(key = "#55#998877", epId = 998_877L, cid = 55L, title = "番剧第 3 集"),
        )

        val back = History.decode(History.encode(list))

        assertEquals(2, back.size)
        assertEquals(list[0], back[0])
        assertEquals(list[1], back[1])
    }

    @Test
    fun `旧档零cid键迁移后续播不丢位置且重复档合并`() {
        val text = """[
            {"k":"BV1#0#0","b":"BV1","c":123,"e":0,"p":463189,"d":2185000},
            {"k":"BV1#123#0","b":"BV1","c":123,"e":0,"p":120000},
            {"k":"BV1#456#0","b":"BV1","c":456,"e":0,"p":210000}
        ]"""
        val migrated = History.decode(text)
        assertEquals(2, migrated.size)
        assertEquals("BV1#123#0", migrated[0].key)
        assertEquals(463189L, History.resumeTargetMs(migrated[0], 2185000L))
        assertEquals("BV1#456#0", migrated[1].key)
        assertEquals(migrated, History.decode(History.encode(migrated)))
    }

    @Test
    fun `空存档解析成空列表`() {
        assertEquals(emptyList<HistoryEntry>(), History.decode(null))
        assertEquals(emptyList<HistoryEntry>(), History.decode(""))
        assertEquals(emptyList<HistoryEntry>(), History.decode("[]"))
    }

    @Test
    fun `存档损坏时返回空列表而不是抛异常`() {
        // SharedPreferences 里的东西可能是被截断的、或者上一版写的旧格式
        assertEquals(emptyList<HistoryEntry>(), History.decode("{不是数组"))
    }

    @Test
    fun `坏记录只丢它自己`() {
        // 一条缺 key、一条是纯字符串、一条正常
        val text = """[{"t":"没 key 的"}, "不是对象", {"k":"good","t":"好的","p":12345}]"""
        val back = History.decode(text)
        assertEquals(1, back.size)
        assertEquals("good", back[0].key)
        assertEquals(12_345L, back[0].progressMs)
    }

    // ---------------------------------------------------------------- 相对时间

    @Test
    fun `相对时间按量级降档`() {
        val now = 1_800_000_000L
        assertEquals("刚刚", History.relativeTime(now - 5, now))
        assertEquals("刚刚", History.relativeTime(now - 59, now))
        assertEquals("3 分钟前", History.relativeTime(now - 3 * 60, now))
        assertEquals("2 小时前", History.relativeTime(now - 2 * 3600, now))
        assertEquals("4 天前", History.relativeTime(now - 4 * 86_400, now))
        assertEquals("2 个月前", History.relativeTime(now - 2 * 2_592_000, now))
    }

    @Test
    fun `没有时间戳就不显示`() {
        assertEquals("", History.relativeTime(0L, 1_800_000_000L))
    }

    @Test
    fun `时钟倒退（设备时间被改过）当成刚刚`() {
        val now = 1_800_000_000L
        assertEquals("刚刚", History.relativeTime(now + 3600, now))
    }

    // ---------------------------------------------------------------- UI 用的派生值

    @Test
    fun `进度比例夹在 0 到 1 之间`() {
        assertEquals(0.5f, entry("A", progressMs = 300_000L, durationMs = 600_000L).fraction)
        assertEquals(0f, entry("A", progressMs = 100L, durationMs = 0L).fraction)
        // 进度超过时长（换了源、时长变短了）不能画出超过 100% 的条
        assertEquals(1f, entry("A", progressMs = 900_000L, durationMs = 600_000L).fraction)
    }
    @Test fun `剧集身份跨存档和旧记录安全迁移`() {
        val current = HistoryEntry(key = "#7#9", cid = 7, epId = 9, seasonId = 123, progressMs = 30000)
        assertEquals(current, History.decode(History.encode(listOf(current))).single())
        val old = History.decode("[{\"k\":\"#7#9\",\"c\":7,\"e\":9}]").single()
        assertEquals(0L, old.seasonId)
        assertEquals(123L, History.mergeFields(current, old).seasonId)
        assertEquals(0L, History.mergeFields(current, old).progressMs)
    }
}
