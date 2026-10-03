package top.bilitv.data.api

import okio.Buffer
import top.bilitv.data.danmaku.Pbf
import top.bilitv.data.model.DashStream
import top.bilitv.data.model.PlayInfo
import java.io.IOException
import java.util.zip.GZIPInputStream

/** Unary playback only. HTTP/2 is supplied by existing OkHttp; no generated protocol runtime.
 * Field numbers: bilibili playershared, playerunite/v1 and pgc/gateway/player/v2 protos.
 * https://github.com/bilibili-plugins/bilibili-API-collect/tree/master/grpc_api
 * New watch_time_length: BiliRoamingX integrations/dummy/libs/grpc_apis.jar field constants.
 * Framing: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md
 */
internal object AppGrpcCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val UGC = "/bilibili.app.playerunite.v1.Player/PlayViewUnite"
    const val PGC = "/bilibili.pgc.gateway.player.v2.PlayURL/PlayView"
    const val BUILD = 7380300L

    internal class Message {
        private val buffer = Buffer()
        private fun varint(value: Long) {
            var v = value
            while (v and -128L != 0L) { buffer.writeByte((v and 127).toInt() or 128); v = v ushr 7 }
            buffer.writeByte(v.toInt())
        }
        fun number(field: Int, value: Long) = apply { varint((field shl 3).toLong()); varint(value) }
        fun bytes(field: Int, value: ByteArray) = apply {
            varint(((field shl 3) or 2).toLong()); varint(value.size.toLong()); buffer.write(value)
        }
        fun text(field: Int, value: String) = apply { if (value.isNotBlank()) bytes(field, value.toByteArray()) }
        fun build(): ByteArray = buffer.readByteArray()
    }

    fun request(aid: Long, cid: Long, bvid: String, epId: Long, preferHevc: Boolean): ByteArray {
        require(cid > 0 && (epId > 0 || aid > 0))
        val vod = Message().number(1, if (epId > 0) epId else aid).number(2, cid)
            .number(3, 127).number(5, 4048).number(7, 2).number(8, 1)
            .number(if (epId > 0) 12 else 9, if (preferHevc) 2 else 1)
        val payload = if (epId > 0) vod.number(15, 1).build()
            else Message().bytes(1, vod.number(10, 1).build()).text(5, bvid).build()
        return frame(payload)
    }

    fun metadata(accessKey: String, buvid: String, model: String): ByteArray = Message()
        .text(1, accessKey).text(2, "android_hd").text(3, model).number(4, BUILD)
        .text(5, "master").text(6, buvid).text(7, "android").build()

    fun device(buvid: String, model: String, brand: String, os: String): ByteArray = Message()
        .number(1, 1).number(2, BUILD).text(3, buvid).text(4, "android_hd").text(5, "android")
        .text(6, model).text(7, "master").text(8, brand).text(9, model).text(10, os).text(13, "7.38.0").build()

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_BYTES)
        return Buffer().writeByte(0).writeInt(payload.size).write(payload).readByteArray()
    }

    fun unframe(body: ByteArray, encoding: String? = null): ByteArray {
        require(body.size in 5..MAX_BYTES + 5) { "gRPC 响应长度异常" }
        val buffer = Buffer().write(body)
        val compressed = buffer.readByte().toInt()
        val length = buffer.readInt().toLong() and 0xffffffffL
        require(length == buffer.size && length <= MAX_BYTES) { "gRPC 帧长度异常" }
        val bytes = buffer.readByteArray()
        if (compressed == 0) return bytes
        require(compressed == 1 && encoding == "gzip") { "gRPC 压缩格式不支持" }
        return GZIPInputStream(bytes.inputStream()).use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                require(out.size() + count <= MAX_BYTES) { "gRPC 解压超过大小限制" }
                out.write(chunk, 0, count)
            }
            out.toByteArray()
        }
    }

    internal class Fields(bytes: ByteArray) {
        private val numbers = mutableMapOf<Int, Long>()
        private val values = mutableMapOf<Int, MutableList<ByteArray>>()
        init {
            Pbf(bytes).fields(number = { f, n -> numbers[f] = n }, bytes = { f, b ->
                val entries = values.getOrPut(f) { mutableListOf() }
                require(entries.size < 512) { "gRPC 重复字段过多" }; entries.add(b)
            })
        }
        fun number(field: Int): Long = numbers[field] ?: 0
        fun bytes(field: Int): ByteArray? = values[field]?.firstOrNull()
        fun children(field: Int): List<Fields> = values[field].orEmpty().map(::Fields)
        fun text(field: Int): String = bytes(field)?.decodeToString().orEmpty()
        fun texts(field: Int): List<String> = values[field].orEmpty().map { it.decodeToString() }
        fun child(field: Int): Fields? = bytes(field)?.let(::Fields)
    }

    fun playInfo(payload: ByteArray, pgc: Boolean): PlayInfo {
        require(payload.size <= MAX_BYTES)
        val root = Fields(payload)
        val business = root.child(if (pgc) 3 else 6)
        if (business?.number(if (pgc) 14 else 4)?.let { it != 0L } == true)
            throw IOException("App 接口返回 DRM 视频")
        val vod = root.child(1) ?: throw IOException("App 接口没有播放信息")
        val labels = mutableMapOf<Int, String>()
        val videos = vod.children(5).mapNotNull { stream ->
            val info = stream.child(1) ?: return@mapNotNull null
            val dash = stream.child(2) ?: return@mapNotNull null
            val quality = info.number(1).toInt()
            // A quality label or entitlement flag alone never creates a playable option.
            if (quality <= 0 || info.number(4) != 0L || dash.text(12).isNotBlank()) return@mapNotNull null
            val codec = when (dash.number(4).toInt()) { 7 -> "avc1"; 12 -> "hev1"; 13 -> "av01"; else -> return@mapNotNull null }
            val url = mediaUrl(dash.text(1)) ?: return@mapNotNull null
            val label = info.text(11).ifBlank { info.text(12) }.ifBlank { info.text(3) }
            if (label.isNotBlank()) labels[quality] = label
            DashStream(quality, codec, dash.number(3), dash.number(10).toInt(), dash.number(11).toInt(),
                url, dash.texts(2).mapNotNull(::mediaUrl))
        }.distinctBy { Triple(it.qualityId, it.codecs, it.baseUrl) }
        if (videos.isEmpty()) throw IOException("App 接口未提供可播放视频流")
        val audios = buildList {
            addAll(vod.children(6))
            vod.child(7)?.children(2)?.let(::addAll)
            if (!pgc) vod.child(9)?.child(2)?.let(::add)
        }.mapNotNull { dash ->
            val id = dash.number(1).toInt()
            if (dash.text(9).isNotBlank()) return@mapNotNull null
            val codec = when (id) {
                30216, 30232, 30280 -> "mp4a.40.2"
                30250 -> "ec-3"
                30251 -> "flac"
                else -> return@mapNotNull null
            }
            val url = mediaUrl(dash.text(2)) ?: return@mapNotNull null
            DashStream(id, codec, dash.number(4), 0, 0, url, dash.texts(3).mapNotNull(::mediaUrl))
        }.distinctBy { Triple(it.qualityId, it.codecs, it.baseUrl) }
        val preview = business?.number(if (pgc) 1 else 9) == 1L
        val limit = if (preview) business?.number(if (pgc) 22 else 10)?.takeIf { it > 0 } else null
        return top.bilitv.data.model.PlaybackPreview.requireBound(
            PlayInfo(vod.number(3), videos, audios, labels, isPreview = preview, previewLimitMs = limit,
                officialClips = if (!pgc) emptyList() else business?.children(6).orEmpty().mapNotNull { clip ->
                    val intro = when (clip.number(4)) { 1L -> true; 2L -> false; else -> return@mapNotNull null }
                    top.bilitv.data.model.OfficialClip.fromSeconds(clip.number(2).toDouble(),
                        clip.number(3).toDouble(), intro, vod.number(3))
                }.distinct()))
    }

    private fun mediaUrl(value: String): String? = value.takeIf {
        (it.startsWith("https://") || it.startsWith("http://")) && it.none(Char::isISOControl)
    }
}
