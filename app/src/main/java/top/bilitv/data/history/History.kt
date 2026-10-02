package top.bilitv.data.history

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条观看记录。
 *
 * ## 为什么本机自己记一份，而不是直接读 B 站的云端历史
 *
 * 少爷要的是「历史**续播**」—— 列表只是外壳，**能接着上次的位置播下去**才是目的。
 * 而云端历史接口有两个硬问题：
 *
 * 1. **必须登录**。没登录时它什么都不返回，功能等于不存在。
 * 2. **延迟不可控**。播放中每几秒上报一次云端的做法既慢又不必要。
 *
 * 所以续播的位置**必须**是本机记的（读一次 SharedPreferences 是微秒级），
 * 云端历史只能当"另一个数据源"，不能当续播的依据。
 *
 * 另外本机这份还顺手解决了两个小需求：**没登录也能看自己看过什么**、
 * **断网也照样能续播**。
 *
 * @param key 唯一键，见 [History.keyOf]。存下来是为了删/改的时候不用重算。
 * @param epId > 0 表示这是番剧/影视（PGC）。PGC 没有 bvid，续播要靠 epId + cid。
 * @param cover 封面。PGC 的封面由剧集详情页带进来（播放页自己拿不到）。
 */
data class HistoryEntry(
    val key: String,
    val bvid: String = "",
    val cid: Long = 0L,
    val epId: Long = 0L,
    val title: String = "",
    val cover: String = "",
    val owner: String = "",
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 最后一次观看的时间（Unix 秒）。用来排序和显示"多久之前" */
    val updatedAtSec: Long = 0L,
    val badge: String = "",
) {
    /** 是不是番剧/影视 */
    val isPgc: Boolean get() = epId > 0L

    /** 进度百分比，0~1。时长未知时返回 0 */
    val fraction: Float
        get() = if (durationMs > 0L) (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * 观看记录的纯逻辑。
 *
 * ## 为什么单独抽一个 object、而不是全塞进 `HistoryStore`
 *
 * `HistoryStore` 拿着 SharedPreferences，**JVM 单测里造不出来**（本项目的
 * `android.jar` 是空壳，会返回默认值而不是真实现）。而这几个判断恰恰是最该被测试钉住的：
 *
 * - 「续播到哪」的边界（看到一半、快看完了、刚点开两秒）
 * - 「200 条上限」的淘汰顺序
 * - JSON 编解码的往返
 *
 * 2026-09-28 的教训就在眼前：`PositionClock` 那个"恢复播放时把整段暂停时间一次性补上"
 * 的洞，是**单测逼出来的，不是想出来的**。所以这类逻辑一律抽成纯函数。
 */
object History {

    /** 本机最多存多少条。超出按最后观看时间淘汰最旧的 */
    const val MAX_ENTRIES = 200

    /**
     * 短于这个时长不记续播位置。
     *
     * 点开看一眼（几秒钟）就退出的视频满地都是，给它们存续播点毫无意义，
     * 用户下次点进去反而会莫名其妙"从第 8 秒开始"。10 秒是"真的看了一会儿"的下限。
     */
    const val MIN_RESUME_MS = 10_000L

    /**
     * 离结束还剩这么多以内，就**不再**续播了，从头开始。
     *
     * 上一遍已经看到最后一二十秒（大多数是看到片尾曲直接退出），
     * 这时候"续播"等于把用户扔在片尾，得自己往回拖 —— 比从头开始还烦。
     */
    const val NEAR_END_MS = 15_000L

    /** 播放中每隔这么久落一次盘。太勤会一直在写 SharedPreferences，太慢退出时丢的进度多 */
    const val SAVE_INTERVAL_MS = 5_000L

    /**
     * 一条记录的唯一键。
     *
     * ★ **必须带上 epId**。同一部剧切集时 `bvid` 是空的、`cid` 一般会变，
     * 但**万一两集的 cid 相同**（花絮 / PV 有可能），只按 `bvid#cid` 存
     * 就会让第 3 集的进度覆盖第 4 集 —— 用户会发现"看过的剧集进度全乱"。
     * 播放页的 `loadedKey` 用的是同一套规则，两处必须一致。
     */
    fun keyOf(bvid: String, cid: Long, epId: Long): String = "$bvid#$cid#$epId"

    /**
     * 该从哪一毫秒续播。返回 `null` = **从头播**。
     *
     * 三种"不续播"的情况，每一种都有具体的用户场景：
     *
     * | 情况 | 场景 |
     * |---|---|
     * | 没有记录 | 第一次看 |
     * | 进度不足 [MIN_RESUME_MS] | 点开看了两眼就退了 |
     * | 进度已到 [NEAR_END_MS] 以内 | 上次看完了（或看到片尾退出） |
     *
     * @param durationMs 本次拿到的时长（接口给的，最准）。<=0 时回落到记录里存的时长。
     */
    fun resumeTargetMs(entry: HistoryEntry?, durationMs: Long): Long? {
        if (entry == null) return null
        val progress = entry.progressMs
        if (progress < MIN_RESUME_MS) return null
        val total = if (durationMs > 0L) durationMs else entry.durationMs
        if (total > 0L && progress > total - NEAR_END_MS) return null
        return progress
    }

    /**
     * 把一条记录并入列表：**同 key 的替换、并且挪到最前面**，然后截到 [limit] 条。
     *
     * 列表本身按"最后观看时间倒序"维护，所以最新的排第一。
     * 用 `key` 而不是下标去重 —— 下标会因为顺序变化而错位（同 `HomeScreen` 里
     * "焦点记忆为什么记 bvid 不记下标"）。
     */
    fun merge(
        existing: List<HistoryEntry>,
        incoming: HistoryEntry,
        limit: Int = MAX_ENTRIES,
    ): List<HistoryEntry> {
        if (incoming.key.isBlank()) return existing
        val out = ArrayList<HistoryEntry>(existing.size + 1)
        out.add(incoming)
        existing.forEach { if (it.key != incoming.key) out.add(it) }
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    /**
     * 两条记录合并：**进度取新的，标题/封面等元信息取非空的**。
     *
     * 为什么要这么细分：进度是高频写的（每 5 秒一次），而标题/封面只在 `load()` 时
     * 拿得到一次。如果一律用"新的整条覆盖旧的"，那么一次"只有进度没有标题"的写入
     * 就会把标题抹掉 —— 历史页上会出现一行没有名字的记录。
     */
    fun mergeFields(old: HistoryEntry?, incoming: HistoryEntry): HistoryEntry {
        if (old == null) return incoming
        return incoming.copy(
            bvid = incoming.bvid.ifBlank { old.bvid },
            cid = if (incoming.cid != 0L) incoming.cid else old.cid,
            epId = if (incoming.epId != 0L) incoming.epId else old.epId,
            title = incoming.title.ifBlank { old.title },
            cover = incoming.cover.ifBlank { old.cover },
            owner = incoming.owner.ifBlank { old.owner },
            badge = incoming.badge.ifBlank { old.badge },
            durationMs = if (incoming.durationMs > 0L) incoming.durationMs else old.durationMs,
            updatedAtSec = if (incoming.updatedAtSec > 0L) incoming.updatedAtSec else old.updatedAtSec,
        )
    }

    // ------------------------------------------------------------------ JSON

    /*
     * 手写 JSON，不引 kotlinx.serialization。
     *
     * 理由：整个项目已经在用 `org.json`（接口解析层就是它），为了一个 10 字段的
     * 数据类再引一个序列化框架，多出来的编译期插件和运行时都白背。
     * 键名用短名（`p` 而不是 `progressMs`）：200 条 × 10 字段，短名能省掉一半的字符串，
     * 而 SharedPreferences 里存的是**整条 String**，每次读都要解析一遍。
     */

    fun encode(list: List<HistoryEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("k", e.key)
                    put("b", e.bvid)
                    put("c", e.cid)
                    put("e", e.epId)
                    put("t", e.title)
                    put("cv", e.cover)
                    put("o", e.owner)
                    put("p", e.progressMs)
                    put("d", e.durationMs)
                    put("at", e.updatedAtSec)
                    if (e.badge.isNotBlank()) put("bg", e.badge)
                }
            )
        }
        return arr.toString()
    }

    /**
     * 解析。**任何一条坏数据只丢它自己，不影响其余** ——
     * 存档是长期存在的（跨版本升级），一条解析不了就让整个历史页空掉是不可接受的。
     */
    fun decode(text: String?): List<HistoryEntry> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<HistoryEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("k")
            if (key.isBlank()) continue
            val bvid = o.optString("b")
            val cid = o.optLong("c")
            val epId = o.optLong("e")
            // 旧版起播时 cid=0，取详情后只更新了字段，没更新 key。
            val resolvedKey = if (cid > 0 && (bvid.isNotBlank() || epId > 0)) keyOf(bvid, cid, epId) else key
            out.add(
                HistoryEntry(
                    key = resolvedKey,
                    bvid = bvid,
                    cid = cid,
                    epId = epId,
                    title = o.optString("t"),
                    cover = o.optString("cv"),
                    owner = o.optString("o"),
                    progressMs = o.optLong("p"),
                    durationMs = o.optLong("d"),
                    updatedAtSec = o.optLong("at"),
                    badge = o.optString("bg"),
                )
            )
        }
        return out.distinctBy { it.key }
    }

    // ------------------------------------------------------------------ 显示

    /**
     * 「多久之前」。
     *
     * 历史列表上直接摆 `2026-09-29 02:31` 这种绝对时间是没用的 ——
     * 用户脑子里的问题是"这是我刚才看的那个吗"，而不是"这是几点"。
     * 所以只给相对时间，超过 30 天才退化成日期。
     */
    fun relativeTime(updatedAtSec: Long, nowSec: Long): String {
        if (updatedAtSec <= 0L) return ""
        val d = nowSec - updatedAtSec
        return when {
            d < 0L -> "刚刚"
            d < 60L -> "刚刚"
            d < 3600L -> "${d / 60} 分钟前"
            d < 86_400L -> "${d / 3600} 小时前"
            d < 2_592_000L -> "${d / 86_400} 天前"
            d < 31_536_000L -> "${d / 2_592_000} 个月前"
            else -> "${d / 31_536_000} 年前"
        }
    }
}
