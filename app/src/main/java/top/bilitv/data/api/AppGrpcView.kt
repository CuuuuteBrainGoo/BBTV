package top.bilitv.data.api

import kotlinx.coroutines.CancellationException
import top.bilitv.data.model.VideoDetail
import top.bilitv.data.model.VideoPage
import top.bilitv.data.model.VideoCollectionEpisode
import top.bilitv.data.settings.VideoApiSource
import java.io.IOException

/** Read-only View/View: archive/v1 Arc/Page and view/v1 ViewReply/UgcSeason protos.
 * https://github.com/bilibili-plugins/bilibili-API-collect/tree/master/grpc_api/bilibili/app
 * New ChargingPlus=68 / RejectPage=69 verified against grpc_apis.jar constants.
 */
internal object AppGrpcView {
    const val PATH = "/bilibili.app.view.v1.View/View"

    fun request(bvid: String): ByteArray {
        require(bvid.length == 12 && bvid.startsWith("BV") && bvid.all(Char::isLetterOrDigit))
        return AppGrpcCodec.frame(AppGrpcCodec.Message().text(2, bvid).build())
    }

    fun detail(payload: ByteArray, requestedBvid: String): VideoDetail {
        require(payload.size <= AppGrpcCodec.MAX_BYTES)
        val root = AppGrpcCodec.Fields(payload)
        if (root.number(28) != 0L || root.bytes(69) != null) throw IOException("App 视频详情暂不可用")
        val arc = root.child(1) ?: throw IOException("App 缺少视频详情")
        val rights = arc.child(21)
        // ChargingPlus.pass is an entitlement, not proof of exclusive-video identity or preview length.
        // Preserve existing Web charging metadata rather than guess it from payment buttons/flags.
        if (root.bytes(68) != null || rights?.number(9) == 1L || rights?.number(13) == 1L)
            throw IOException("付费详情需要网页字段")
        if (arc.number(11) != 0L || arc.text(19).isNotBlank()) throw IOException("App 返回非普通视频详情")
        val bvid = root.text(14)
        val aid = arc.number(1)
        check(bvid == requestedBvid && aid > 0 && arc.text(7).isNotBlank()) { "App 视频身份不匹配" }
        val pages = root.children(2).map { item ->
            val page = item.child(1) ?: throw IOException("App 分P缺少信息")
            check(page.number(1) > 0 && page.number(2) in 1..Int.MAX_VALUE.toLong() &&
                page.number(5) in 0..Int.MAX_VALUE.toLong()) { "App 分P信息异常" }
            VideoPage(page.number(1), page.number(2).toInt(), page.text(4), page.number(5).toInt())
        }.distinctBy { it.cid }
        val count = arc.number(2)
        check(pages.isNotEmpty() && (count <= 0 || count == pages.size.toLong())) { "App 分P列表不完整" }
        val season = root.child(24)
        check(arc.number(29) <= 0 || season != null) { "App 合集信息缺失" }
        val collection = season?.children(5).orEmpty().flatMap { section ->
            section.children(4).mapNotNull { episode ->
                val bv = episode.text(9)
                if (bv.length != 12 || !bv.startsWith("BV") || episode.number(3) <= 0) return@mapNotNull null
                VideoCollectionEpisode(bv, episode.number(3), episode.text(4), episode.text(5))
            }
        }.distinctBy { it.bvid }
        if ((season?.number(13) ?: 0) > collection.size) throw IOException("App 合集列表不完整")
        val author = arc.child(22)
        val stat = arc.child(23)
        val duration = arc.number(16)
        check(duration in 0..Int.MAX_VALUE.toLong()) { "App 视频时长异常" }
        return VideoDetail(bvid, aid, pages.first().cid, arc.text(7), arc.text(6), arc.text(10), duration.toInt(),
            author?.text(2).orEmpty(), author?.number(1) ?: 0,
            stat?.number(2)?.coerceAtLeast(0) ?: 0, stat?.number(3)?.coerceAtLeast(0) ?: 0, pages,
            copyright = arc.number(5).toInt(), collectionTitle = season?.text(2).orEmpty(), collection = collection)
    }
}

/** One App attempt, then one Web attempt; cancellation never initiates a fallback. */
internal suspend fun detailWithFallback(source: VideoApiSource, bvid: String,
    request: suspend (VideoApiSource) -> VideoDetail?): VideoDetail? {
    if (source == VideoApiSource.WEB) return request(source)?.takeIf { it.bvid == bvid }
    try { request(source)?.takeIf { it.bvid == bvid }?.let { return it } }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { }
    return request(VideoApiSource.WEB)?.takeIf { it.bvid == bvid }
}
