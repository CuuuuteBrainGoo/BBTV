package top.bilitv.player

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.bilitv.data.model.FavResourcePage
import top.bilitv.data.model.FeedItem

/** 收藏播放列表的已加载窗口；未识别当前视频时绝不从第一条误播。 */
internal class PlaylistQueue {
    var folderId = 0L
        private set
    var page = 0
        private set
    var hasMore = false
        private set
    var truncated = false
        private set
    private var items: List<FeedItem> = emptyList()

    fun record(id: Long, page: Int, result: FavResourcePage) {
        require(id > 0 && page > 0)
        check(page == 1 || (folderId == id && page == this.page + 1))
        val merged = ((if (page == 1) emptyList() else items) + result.items).distinctBy { it.bvid }
        truncated = (page != 1 && truncated) || merged.size > 512
        items = merged.takeLast(512)
        folderId = id; this.page = page; hasMore = result.hasMore
    }

    fun contains(bvid: String) = items.any { it.bvid == bvid }
    fun adjacent(bvid: String, step: Int): FeedItem? = NextEpisode.adjacent(items, step) { it.bvid == bvid }

    suspend fun next(bvid: String, fetch: suspend (Int) -> FavResourcePage): FeedItem? {
        adjacent(bvid, 1)?.let { return it }
        if (!contains(bvid)) return null
        // ponytail: 一次最多跨三页失效稿件；更多空页由下一次操作继续，避免无限请求。
        repeat(3) {
            if (!hasMore) return null
            val requested = page + 1
            val result = fetch(requested)
            currentCoroutineContext().ensureActive()
            record(folderId, requested, result)
            adjacent(bvid, 1)?.let { return it }
        }
        return null
    }
}
