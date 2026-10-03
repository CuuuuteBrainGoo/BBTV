package top.bilitv.data.api

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.bilitv.data.model.PlayInfo
import top.bilitv.data.settings.VideoApiSource
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Existing pool/dispatcher, no cookies, no web header interceptor on this protocol. */
internal class AppGrpcVideo(client: OkHttpClient) {
    private val grpcClient = client.newBuilder().apply { interceptors().clear(); networkInterceptors().clear() }
        .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .callTimeout(9, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(9, TimeUnit.SECONDS).build()

    suspend fun play(aid: Long, cid: Long, bvid: String, epId: Long, preferHevc: Boolean,
        accessKey: String?, buvid: String?): PlayInfo {
        val bytes = read(if (epId > 0) AppGrpcCodec.PGC else AppGrpcCodec.UGC,
            AppGrpcCodec.request(aid, cid, bvid, epId, preferHevc), accessKey, buvid)
        return withContext(Dispatchers.Default) { AppGrpcCodec.playInfo(bytes, epId > 0) }
    }

    suspend fun detail(bvid: String, accessKey: String?, buvid: String?): top.bilitv.data.model.VideoDetail {
        val bytes = read(AppGrpcView.PATH, AppGrpcView.request(bvid), accessKey, buvid)
        return withContext(Dispatchers.Default) { AppGrpcView.detail(bytes, bvid) }
    }

    private suspend fun read(path: String, payload: ByteArray, accessKey: String?, buvid: String?): ByteArray = withContext(Dispatchers.IO) {
        val model = Build.MODEL.orEmpty()
        fun binary(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)
        val request = Request.Builder().url("https://grpc.biliapi.net$path")
            .post(payload.toRequestBody("application/grpc".toMediaType()))
            .header("te", "trailers").header("grpc-timeout", "8S").header("grpc-accept-encoding", "identity,gzip")
            .header("User-Agent", "grpc-java-okhttp/1.68.1")
            .header("x-bili-metadata-bin", binary(AppGrpcCodec.metadata(accessKey.orEmpty(), buvid.orEmpty(), model)))
            .header("x-bili-device-bin", binary(AppGrpcCodec.device(buvid.orEmpty(), model, Build.BRAND.orEmpty(), Build.VERSION.RELEASE.orEmpty())))
            .header("x-bili-locale-bin", binary(AppGrpcCodec.Message().text(4, "Asia/Shanghai").build()))
            .apply { if (!accessKey.isNullOrBlank()) header("authorization", "identify_v1 $accessKey") }.build()
        val call = grpcClient.newCall(request)
        try {
            call.readCancellable { resp ->
                if (!resp.isSuccessful || resp.protocol != Protocol.HTTP_2 ||
                    !resp.header("content-type").orEmpty().startsWith("application/grpc")) throw IOException("App gRPC 传输失败")
                val source = resp.body?.source() ?: throw IOException("App gRPC 没有响应")
                val body = okio.Buffer()
                while (body.size <= AppGrpcCodec.MAX_BYTES + 5 &&
                    source.read(body, minOf(8192L, AppGrpcCodec.MAX_BYTES + 6L - body.size)) != -1L) { }
                require(body.size <= AppGrpcCodec.MAX_BYTES + 5) { "App gRPC 响应过大" }
                // Status lives in trailers (or the initial headers for trailers-only errors).
                val status = resp.trailers()["grpc-status"] ?: resp.header("grpc-status")
                if (status != "0") throw IOException("App gRPC 状态异常（${status?.toIntOrNull() ?: -1}）")
                AppGrpcCodec.unframe(body.readByteArray(), resp.header("grpc-encoding"))
            }
        } finally { call.cancel() }
    }
}

/** Cancellation and a failed Web request must never restart the App request. */
internal suspend fun playbackWithFallback(source: VideoApiSource, accepts: (PlayInfo) -> Boolean = { true },
    request: suspend (VideoApiSource) -> PlayInfo?): PlayInfo? {
    if (source == VideoApiSource.WEB) return request(source)
    try {
        request(source)?.takeIf { it.videos.isNotEmpty() && accepts(it) }?.let { return it.copy(source = source) }
    } catch (e: Exception) { if (e is CancellationException) throw e }
    return request(VideoApiSource.WEB)?.copy(notice = "App 取流暂不可用，已使用网页接口")
}
