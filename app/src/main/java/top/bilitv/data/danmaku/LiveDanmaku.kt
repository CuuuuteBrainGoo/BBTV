package top.bilitv.data.danmaku

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream

internal fun livePacket(operation: Int, body: ByteArray, version: Int = 0): ByteArray =
    ByteBuffer.allocate(16 + body.size).putInt(16 + body.size).putShort(16).putShort(version.toShort())
        .putInt(operation).putInt(1).put(body).array()

/** 网络帧、压缩展开与嵌套都限量；只读取聊天，不实现发送弹幕。 */
internal fun livePackets(data: ByteArray, depth: Int = 0): List<Pair<Int, ByteArray>> {
    if (data.size > 262144 || depth > 2) return emptyList()
    val result = ArrayList<Pair<Int, ByteArray>>()
    var offset = 0
    while (offset + 16 <= data.size && result.size < 512) {
        val buf = ByteBuffer.wrap(data, offset, data.size - offset)
        val size = buf.int; val header = buf.short.toInt() and 65535
        val version = buf.short.toInt() and 65535; val operation = buf.int
        if (header < 16 || size < header || size > data.size - offset) break
        val body = data.copyOfRange(offset + header, offset + size)
        if (version == 2) {
            val expanded = InflaterInputStream(body.inputStream()).use { input ->
                val bounded = ByteArray(262145)
                var n = 0
                while (n < bounded.size) {
                    val count = input.read(bounded, n, bounded.size - n)
                    if (count <= 0) break
                    n += count
                }
                bounded.copyOf(n)
            }
            if (expanded.size <= 262144) result.addAll(livePackets(expanded, depth + 1).take(512 - result.size))
        } else if (version == 0 || version == 1) result.add(operation to body)
        offset += size
    }
    return result
}

internal fun liveMessage(body: ByteArray): DanmakuItem? {
    val obj = JSONObject(String(body, Charsets.UTF_8))
    if (!obj.optString("cmd").startsWith("DANMU_MSG")) return null
    val info = obj.optJSONArray("info") ?: return null
    val content = info.optString(1).take(256)
    if (content.isBlank()) return null
    val meta = info.optJSONArray(0)
    return DanmakuItem(0, (meta?.optInt(1, 1) ?: 1).coerceIn(1, 6),
        (meta?.optInt(2, 25) ?: 25).coerceIn(18, 36), (meta?.optInt(3, 0xFFFFFF) ?: 0xFFFFFF) and 0xFFFFFF,
        content, midHash = meta?.optString(7).orEmpty())
}

internal suspend fun receiveLiveDanmaku(client: OkHttpClient, url: String, auth: JSONObject,
    onBatch: (List<DanmakuItem>) -> Unit) = coroutineScope {
    val messages = Channel<List<DanmakuItem>>(16, BufferOverflow.DROP_OLDEST)
    var heartbeat: kotlinx.coroutines.Job? = null
    val socket = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        .newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(livePacket(7, auth.toString().toByteArray()).toByteString())
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val packets = livePackets(bytes.toByteArray())
                    for ((operation, body) in packets) if (operation == 8) {
                        check(JSONObject(String(body)).optInt("code", -1) == 0) { "直播弹幕鉴权失败" }
                        if (heartbeat == null) heartbeat = launch {
                            while (true) { webSocket.send(livePacket(2, "[object Object]".toByteArray()).toByteString()); delay(30000) }
                        }
                    }
                    val batch = packets.filter { it.first == 5 }.mapNotNull { runCatching { liveMessage(it.second) }.getOrNull() }
                    if (batch.isNotEmpty()) messages.trySend(batch)
                } catch (e: Exception) { messages.close(e) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { messages.close(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close() }
        })
    try {
        while (true) {
            val batch = messages.receive().toMutableList()
            delay(250) // 合批更新屏幕，避免直播高峰逐条触发重组。
            while (batch.size < 512) batch.addAll(messages.tryReceive().getOrNull() ?: break)
            onBatch(batch.takeLast(512))
        }
    } finally { heartbeat?.cancel(); socket.cancel(); messages.cancel() }
}
