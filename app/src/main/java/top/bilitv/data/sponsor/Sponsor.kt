package top.bilitv.data.sponsor

import org.json.JSONArray
import org.json.JSONObject

/**
 * SponsorBlock 片段类别。
 *
 * 对齐 bsbsb.top（BilibiliSponsorBlock）的类别定义；
 * 未知类别统一归入 [OTHER]（对应服务端的 `clip`），历史别名 `padding` 归一为 [FILLER]。
 */
enum class SponsorCategory(
    val id: String,
    val label: String,
    /** 首次启用时是否默认勾选自动跳过 */
    val defaultSkip: Boolean,
) {
    SPONSOR("sponsor", "赞助/恰饭", true),
    SELF_PROMO("selfpromo", "推广", true),
    EXCLUSIVE_ACCESS("exclusive_access", "品牌合作", true),
    INTRO("intro", "片头", true),
    OUTRO("outro", "片尾", true),
    INTERACTION("interaction", "三连提醒", false),
    PREVIEW("preview", "预览/回顾", false),
    FILLER("filler", "填充内容", false),
    MUSIC_OFFTOPIC("music_offtopic", "非音乐", false),
    POI_HIGHLIGHT("poi_highlight", "精彩时刻", false),
    OTHER("clip", "其他", false);

    companion object {
        private val BY_ID = entries.associateBy { it.id }

        fun from(raw: String?): SponsorCategory {
            val k = raw?.trim()?.lowercase().orEmpty()
            return when (k) {
                "" -> OTHER
                "padding" -> FILLER
                else -> BY_ID[k] ?: OTHER
            }
        }

        /** 默认自动跳过的类别 */
        val DEFAULT_SKIP: Set<SponsorCategory> = entries.filter { it.defaultSkip }.toSet()
    }
}

/** 一条标记片段 */
data class SponsorSegment(
    val startMs: Long,
    val endMs: Long,
    val category: SponsorCategory,
    val actionType: String,
    val uuid: String?,
    val cid: Long?,
) {
    /** POI 是"点"不是"区间"（允许 end < start），本阶段只在进度条上画标记，不参与跳过 */
    val isPoi: Boolean
        get() = actionType.equals("poi", ignoreCase = true) ||
                category == SponsorCategory.POI_HIGHLIGHT

    /** 稳定的去重标识 */
    val id: String
        get() = uuid?.takeIf { it.isNotBlank() } ?: "sb:${category.id}:$startMs-$endMs"
}

// ------------------------------------------------------------------ 解析

/**
 * 解析 `/api/skipSegments` 的返回（**数组**形态，非哈希查询）。
 *
 * 注意：无标注的视频返回 404，此处应传入空串，返回空列表。
 */
fun parseSponsorSegments(json: String): List<SponsorSegment> {
    val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    return segmentsOf(arr)
}

/**
 * 解析哈希前缀查询的返回：`[{ videoID, segments: [...] }, ...]`。
 * 因为前缀会命中多个视频，**必须**再用完整 videoID 过滤。
 */
fun parseSponsorHashed(json: String, bvid: String): List<SponsorSegment> {
    val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("videoID") != bvid) continue
        return segmentsOf(o.optJSONArray("segments") ?: return emptyList())
    }
    return emptyList()
}

private fun segmentsOf(arr: JSONArray): List<SponsorSegment> {
    val out = ArrayList<SponsorSegment>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val range = o.optJSONArray("segment") ?: continue
        if (range.length() < 2) continue
        val startSec = range.optDouble(0, Double.NaN)
        val endSec = range.optDouble(1, Double.NaN)
        if (!startSec.isFinite() || !endSec.isFinite()) continue

        val seg = SponsorSegment(
            startMs = (startSec * 1000.0).toLong().coerceAtLeast(0L),
            endMs = (endSec * 1000.0).toLong().coerceAtLeast(0L),
            category = SponsorCategory.from(o.optString("category")),
            actionType = o.optString("actionType", "skip"),
            uuid = o.optString("UUID").takeIf { it.isNotBlank() },
            cid = o.optString("cid").trim().toLongOrNull(),
        )
        // 非 POI 必须有有效区间
        if (!seg.isPoi && seg.endMs <= seg.startMs) continue
        out.add(seg)
    }
    return out
}

/**
 * 按 cid 过滤（学自 blbl 的防串P设计）。
 *
 * 片段可能标的是整条视频（cid 为空）或某个分P。规则：
 *  - 有 cid 精确匹配 → 只取匹配的
 *  - 所有片段的 cid 都为空 → 视为适用于全部
 *  - 只存在一种 cid → 视为适用于全部
 *  - **存在多种 cid 且都不匹配 → 返回空**（宁可不跳，也不能跳错分P）
 */
fun pickSegmentsForCid(segments: List<SponsorSegment>, cid: Long): List<SponsorSegment> {
    val exact = segments.filter { it.cid == cid }
    if (exact.isNotEmpty()) return exact

    val known = segments.mapNotNull { it.cid }.toSet()
    return when (known.size) {
        0, 1 -> segments
        else -> emptyList()
    }
}

// ------------------------------------------------------------------ 调度

/**
 * 广告跳过调度器 —— **纯逻辑，无 Android 依赖**。
 *
 * 时序对齐 blbl 的实测实现：
 *  - 提前 [lookAheadMs] 进入"候选"（播放位置采样有间隔，必须留提前量）
 *  - 命中后延迟 [confirmDelayMs] 才真正跳，期间 UI 显示提示，用户可撤销
 *  - 跳过的片段记入 handled，不重复跳；**用户取消的同样记入**（本次不再自动跳）
 *  - 跳转目标 clamp 到 `duration - 500ms`，避免跳到末尾直接播完
 *  - 用户主动拖动进度条后应调用 [onUserSeek]，丢弃当前倒计时（治"想回看又被踢走"）
 */
class SkipPlanner(
    private val lookAheadMs: Long = 1_000L,
    private val confirmDelayMs: Long = 2_000L,
) {
    private var segments: List<SponsorSegment> = emptyList()
    private val handled = HashSet<String>()
    private var pending: Pending? = null

    private data class Pending(val segment: SponsorSegment, val dueAtMs: Long)

    /** 当前正在倒计时的片段（UI 用它显示"将要跳过…按返回取消"） */
    val pendingSegment: SponsorSegment? get() = pending?.segment

    /** 应用片段与类别勾选（POI 不参与跳过，仅展示） */
    fun setSegments(list: List<SponsorSegment>, enabled: Set<SponsorCategory>) {
        segments = list
            .filter { !it.isPoi && it.category in enabled }
            .sortedBy { it.startMs }
        pending = null
    }

    /** 用户主动拖动进度条 → 丢弃倒计时，但保留 handled（避免来回跳） */
    fun onUserSeek() {
        pending = null
    }

    /** 切换视频 → 彻底重置 */
    fun reset() {
        segments = emptyList()
        handled.clear()
        pending = null
    }

    /** 用户取消本次跳过（按返回键）→ 该片段本次不再自动跳 */
    fun cancelPending() {
        pending?.let { handled.add(it.segment.id) }
        pending = null
    }

    sealed interface Decision {
        /** 无需动作 */
        data object None : Decision

        /** 已排定跳过，进入倒计时（UI 应弹提示） */
        data class Armed(val segment: SponsorSegment) : Decision

        /** 执行跳转 */
        data class Skip(val toMs: Long, val segment: SponsorSegment) : Decision
    }

    /**
     * 播放位置更新时调用。
     *
     * @param posMs      当前播放位置
     * @param durationMs 总时长（用于 clamp 目标点）
     * @param nowMs      单调时钟（调用方传 `SystemClock.elapsedRealtime()`）
     */
    fun onPosition(posMs: Long, durationMs: Long, nowMs: Long): Decision {
        pending?.let { p ->
            if (p.segment.id in handled) {
                pending = null
                return Decision.None
            }
            // 已经越过片段末尾 → 放弃（例如用户手动跳过了）
            if (posMs >= p.segment.endMs) {
                pending = null
                return Decision.None
            }
            if (posMs < p.segment.startMs) return Decision.None
            if (nowMs < p.dueAtMs) return Decision.None

            val target = p.segment.endMs.coerceIn(0L, (durationMs - 500L).coerceAtLeast(0L))
            handled.add(p.segment.id)
            pending = null
            return Decision.Skip(target, p.segment)
        }

        if (segments.isEmpty()) return Decision.None
        val windowEnd = posMs + lookAheadMs
        val candidate = segments.firstOrNull { seg ->
            seg.id !in handled &&
                    (posMs >= seg.startMs && posMs < seg.endMs ||
                            seg.startMs in posMs..windowEnd)
        } ?: return Decision.None

        pending = Pending(candidate, nowMs + confirmDelayMs)
        return Decision.Armed(candidate)
    }
}
