package top.bilitv.data.model

import org.json.JSONObject
import java.net.URI

data class SubtitleTrack(val id: String, val language: String, val label: String, val url: String)
data class SubtitleCue(val fromMs: Long, val toMs: Long, val text: String)

/** 只接受字幕服务的 HTTPS 地址，正文请求不带账号 Cookie，也不跟随跳转。 */
fun subtitleUrl(raw: String): String? {
    val value = raw.trim().let { if (it.startsWith("//")) "https:$it" else it }
    val u = runCatching { URI(value) }.getOrNull() ?: return null
    val host = u.host?.lowercase().orEmpty()
    return value.takeIf {
        u.scheme == "https" && u.userInfo == null && (u.port == -1 || u.port == 443) &&
            (host == "hdslb.com" || host.endsWith(".hdslb.com") || host == "bilibili.com" || host.endsWith(".bilibili.com"))
    }
}

fun parseSubtitleTracks(raw: String): List<SubtitleTrack> {
    val root = JSONObject(raw)
    check(root.optInt("code", -1) == 0) { "字幕信息接口异常（${root.optInt("code", -1)}）" }
    val data = root.optJSONObject("data") ?: error("字幕信息缺少 data")
    val tracks = data.optJSONObject("subtitle")?.optJSONArray("subtitles") ?: return emptyList()
    require(tracks.length() <= 64) { "字幕语言数量超过上限" }
    val parsed = (0 until tracks.length()).mapNotNull { i ->
        val t = tracks.optJSONObject(i) ?: return@mapNotNull null
        val url = subtitleUrl(t.optString("subtitle_url")) ?: return@mapNotNull null
        val lan = t.optString("lan").take(64).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SubtitleTrack(t.optString("id_str").ifBlank { t.optString("id") }, lan,
            t.optString("lan_doc").ifBlank { lan }.take(100), url)
    }.distinctBy { it.id to it.language }
    check(parsed.isNotEmpty() || tracks.length() == 0) { "字幕轨道暂无可用地址，请重新加载" }
    return parsed
}

fun preferredSubtitle(tracks: List<SubtitleTrack>, language: String): SubtitleTrack? =
    tracks.firstOrNull { language.isNotBlank() && it.language.equals(language, true) }
        ?: tracks.firstOrNull { it.language.startsWith("zh", true) }
        ?: tracks.firstOrNull { it.language.startsWith("ai-zh", true) }
        ?: tracks.firstOrNull()

/** 按起点二分，再按累计最大终点回看；快退、跳转和重叠字幕都不依赖旧游标。 */
class SubtitleTimeline(val cues: List<SubtitleCue>) {
    private val maxEnd = LongArray(cues.size).also { ends ->
        cues.indices.forEach { ends[it] = maxOf(cues[it].toMs, if (it == 0) 0 else ends[it - 1]) }
    }
    fun textAt(positionMs: Long): String {
        var low = 0; var high = cues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cues[mid].fromMs <= positionMs) low = mid + 1 else high = mid
        }
        var index = low - 1
        val active = ArrayList<String>(2)
        // ponytail: 同时最多8句/6000字；真实轨道超过此显示量时再设计分屏或滚动。
        while (index >= 0 && maxEnd[index] > positionMs && active.size < 8) {
            val cue = cues[index--]
            if (positionMs < cue.toMs) active.add(cue.text)
        }
        return active.asReversed().distinct().joinToString("\n").take(6_000)
    }
    companion object {
        fun parse(raw: String): SubtitleTimeline {
            val body = JSONObject(raw).optJSONArray("body") ?: error("字幕正文缺少 body")
            require(body.length() <= 20_000) { "字幕条数超过上限" }
            val cues = (0 until body.length()).mapNotNull { i ->
                val c = body.optJSONObject(i) ?: return@mapNotNull null
                val from = c.optDouble("from", Double.NaN); val to = c.optDouble("to", Double.NaN)
                val text = c.optString("content").trim().take(2_000)
                if (!from.isFinite() || !to.isFinite() || from < 0 || to <= from || to > 7 * 24 * 3600 || text.isBlank()) null
                else SubtitleCue((from * 1000).toLong(), (to * 1000).toLong(), text)
            }.sortedBy { it.fromMs }
            return SubtitleTimeline(cues)
        }
    }
}
