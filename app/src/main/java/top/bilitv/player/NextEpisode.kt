package top.bilitv.player

import top.bilitv.data.model.PgcEpisode
import top.bilitv.data.model.VideoPage

/**
 * 自动连播：从列表里挑"下一个"。
 *
 * ## 为什么单独抽出来（而不是写在 ViewModel 里）
 *
 * 这段全是**边界判断**，而且每一条错了都会"静默播错"：
 * - 最后一集返回 null → 不播；返回第一集 → **无限循环重播整部剧**
 * - `indexOfFirst` 找不到当前项会返回 `-1`，`list[-1 + 1]` 恰好是 `list[0]`
 *   → 又是从头开始重播，而且**看起来完全正常**（它有在播啊）
 * - PGC 用 `cid` 匹配 → 花絮/PV 的 cid 可能与正片不同、epId 却唯一，
 *   结果"下一集"跳到别的条目
 *
 * 这些都不是 `null` 检查能挡住的，必须钉在单测里。参考同目录的
 * [CdnOrder] / [PlayTolerance]（也是纯逻辑 + 单测）。
 *
 * ## 两条铁律
 * 1. **只在列表里前后走一格**，到头返回 `null`（**绝不回绕**）
 * 2. **当前项认不出来就返回 `null`**（宁可停在原地，不要跳到错误的集）
 */
object NextEpisode {

    /**
     * PGC（番剧/影视）：按 **epId** 匹配。
     *
     * 为什么不用 cid：epId 是"这一集"的全局唯一编号，cid 是弹幕段编号 ——
     * 同一部剧里花絮、PV、正片可能有各自的 cid，而**同一个 epId 一定只对应一集**。
     * 用 cid 匹配在遇到"两集共用 cid"时会挑错。
     *
     * @return 下一集；已是最后一集 / 列表为空 / 当前项不在列表里 → `null`
     */
    fun nextPgc(episodes: List<PgcEpisode>, currentEpId: Long, step: Int = 1): PgcEpisode? {
        if (episodes.isEmpty() || currentEpId <= 0L) return null
        return adjacent(episodes, step) { it.epId == currentEpId }
    }

    /**
     * UGC（普通视频的多 P）：按 **cid** 匹配。
     *
     * 多 P 里 cid 是唯一的（每一 P 一段单独视频），用 cid 而不是 index ——
     * index 是"第几 P"的显示序号，理论上可能重复或不连续（有些稿件会跳号）。
     *
     * @return 下一 P；已是最后一 P / 列表为空 / 当前项不在列表里 → `null`
     */
    fun nextPage(pages: List<VideoPage>, currentCid: Long, step: Int = 1): VideoPage? {
        if (pages.isEmpty() || currentCid <= 0L) return null
        return adjacent(pages, step) { it.cid == currentCid }
    }

    fun <T> adjacent(items: List<T>, step: Int, current: (T) -> Boolean): T? {
        if (step != -1 && step != 1) return null
        val index = items.indexOfFirst(current)
        if (index < 0) return null
        return items.getOrNull(index + step)
    }
}
