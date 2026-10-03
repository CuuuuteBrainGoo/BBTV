package top.bilitv.data.model

import org.json.JSONObject
import java.io.IOException

/** Support for a preview is not proof that the returned stream is a preview or a full entitlement. */
fun explicitPreviewState(raw: String): Boolean? {
    val root = JSONObject(raw)
    require(root.optInt("code", -1) == 0) { "试看信息请求失败" }
    val data = root.optJSONObject("data") ?: root.optJSONObject("result") ?: error("试看信息缺少数据")
    val objects = listOfNotNull(data, data.optJSONObject("video_info"))
    val values = objects.flatMap { obj -> listOf("is_preview", "is_ugc_pay_preview").mapNotNull { key ->
        when (obj.opt(key)) {
            true, 1 -> true
            false, 0 -> false
            else -> null
        }
    } }
    return if (true in values) true else values.firstOrNull()
}

object PlaybackPreview {
    fun requireBound(play: PlayInfo): PlayInfo {
        if (play.isPreview && play.previewLimitMs?.let { it > 0 && it <= Long.MAX_VALUE / 1000 } != true)
            throw IOException("接口未提供明确的试看时限")
        return play
    }

    /** Even the media file may contain the whole film; the explicit server limit takes priority. */
    fun duration(isPreview: Boolean, mediaMs: Long, hintMs: Long, limitMs: Long?): Long {
        val media = mediaMs.takeIf { it > 0 }
        if (!isPreview) return media ?: hintMs.coerceAtLeast(0)
        val limit = limitMs?.takeIf { it > 0 } ?: return 0
        return minOf(media ?: limit, limit)
    }
}
