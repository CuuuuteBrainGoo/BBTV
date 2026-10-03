package top.bilitv.data.api

import kotlinx.coroutines.CancellationException
import top.bilitv.data.model.FeedItem
import top.bilitv.data.settings.RecommendSource

data class RecommendPage(val items: List<FeedItem>, val source: RecommendSource = RecommendSource.WEB,
    val notice: String? = null, val hasMore: Boolean? = null)

/** One fallback only; cancellation never starts another request. */
internal suspend fun recommendWithFallback(source: RecommendSource,
    request: suspend (RecommendSource) -> List<FeedItem>): RecommendPage = try {
    RecommendPage(request(source), source)
} catch (e: Exception) {
    if (e is CancellationException || source == RecommendSource.WEB) throw e
    RecommendPage(request(RecommendSource.WEB), RecommendSource.WEB, "App 推荐暂不可用，已使用网页推荐")
}
