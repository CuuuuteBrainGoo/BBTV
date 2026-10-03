package top.bilitv.data.sponsor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import top.bilitv.data.api.readCancellable

/**
 * SponsorBlock（bsbsb.top）片段查询。
 *
 * 设计要点，全部来自对常用客户端的实测：
 *  - **哈希前缀查询**：只把 `sha256(bvid)` 的前 4 位发给服务器，服务器无法知道你具体在看哪个视频；
 *    代价是前缀会命中多个视频，**必须本地按完整 bvid 再过滤**（[parseSponsorHashed]）。
 *  - **主备服务器**：只在网络层异常（[IOException]）时才切备用；其他错误不重试（学自 blbl）。
 *  - **两级过滤**：返回后经 [pickSegmentsForCid] 处理，防止多分P 串用错误片段。
 *  - **不携带任何 B 站凭证**：本类使用独立的 OkHttpClient，绝不复用 BiliApi 的 CookieJar。
 *  - **404 是常态**（绝大多数视频没有社区标注）：一律静默返回空列表，绝不抛给界面层。
 *
 * ⚠️ 许可边界：bsbsb.top 的服务端与扩展是 GPL-3.0。本项目**只调用其公开 HTTP 接口**（数据服务），
 * 不复制其代码；跳过调度逻辑为通用算法，自行实现（见 [SkipPlanner]）。
 */
class SponsorBlockApi(
    private val client: OkHttpClient,
    private val bases: List<String> = DEFAULT_BASES,
) {

    /**
     * 非取消失败静默返回空列表；取消关闭实际请求，不尝试备用服务器。
     */
    suspend fun fetchSegments(bvid: String, cid: Long): List<SponsorSegment> =
        withContext(Dispatchers.IO) {
            if (bvid.isBlank()) return@withContext emptyList()
            try {
                val prefix = sha256Hex(bvid).take(HASH_PREFIX_LEN)
                val raw = requestFirstAvailable("/api/skipSegments/$prefix")
                if (raw.isNullOrBlank()) return@withContext emptyList()
                withContext(Dispatchers.Default) { pickSegmentsForCid(parseSponsorHashed(raw, bvid), cid) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
        }

    /**
     * 依次尝试各服务器。返回 null 表示"确定没有数据"（404）；
     * 全部失败则抛 [IOException]，由调用方统一吞掉。
     */
    private suspend fun requestFirstAvailable(path: String): String? {
        var lastError: Throwable? = null
        for (base in bases) {
            currentCoroutineContext().ensureActive()
            try {
                val req = Request.Builder()
                    .url(base + path)
                    .header("Origin", CLIENT_ORIGIN)
                    .header("Referer", CLIENT_ORIGIN)
                    .header("X-Ext-Version", CLIENT_TAG)
                    .header("Accept", "application/json")
                    .build()
                return client.newCall(req).readCancellable { resp ->
                    when {
                        // 404 = 该视频没有被标注，属正常情况
                        resp.code == 404 -> null
                        resp.isSuccessful -> resp.body?.string().orEmpty()
                        else -> throw IOException("HTTP ${resp.code}")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: IOException) { lastError = e }
        }
        throw IOException("SponsorBlock 所有服务器均不可达", lastError)
    }

    companion object {
        /** 主 + 备（备用用另一域名/IP，对齐 chinasoul.bt 与 blbl 的双通道做法） */
        val DEFAULT_BASES = listOf("https://bsbsb.top", "https://bsbsb.xyz")

        private const val HASH_PREFIX_LEN = 4
        private const val CLIENT_TAG = "top.bilitv/0.1.0"
        private const val CLIENT_ORIGIN = "https://github.com/"

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
