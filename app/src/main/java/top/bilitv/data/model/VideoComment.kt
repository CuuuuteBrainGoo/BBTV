package top.bilitv.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class VideoComment(
    val id: Long, val author: String, val message: String, val time: String,
    val likes: Long, val replyCount: Int, val pinned: Boolean = false,
)

data class CommentPage(
    val items: List<VideoComment>, val total: Long, val nextOffset: String? = null,
) { val hasMore get() = nextOffset != null }

/** Only read APIs: type=1 oid=avid, never cid/epId. Cursor offsets remain opaque. */
fun commentPagination(offset: String): String = JSONObject().put("offset", offset).toString()

private fun commentData(raw: String): JSONObject {
    val json = JSONObject(raw)
    val code = json.opt("code") as? Number ?: throw IOException("评论响应不完整")
    if (code.toDouble() != 0.0) throw IOException(when (code.toInt()) {
        12002 -> "当前评论区已关闭"
        -101 -> "当前评论需要登录后阅读"
        else -> "评论暂时加载失败，请重试"
    })
    return json.optJSONObject("data") ?: throw IOException("评论响应不完整")
}

private fun comment(o: JSONObject, pinned: Boolean = false): VideoComment? {
    val id = o.optLong("rpid")
    if (id <= 0 || o.optBoolean("invisible", false)) return null
    val member = o.optJSONObject("member")
    return VideoComment(id, member?.optString("uname").orEmpty().ifBlank { "用户" },
        o.optJSONObject("content")?.optString("message").orEmpty(),
        o.optJSONObject("reply_control")?.optString("time_desc").orEmpty(),
        o.optLong("like").coerceAtLeast(0), o.optInt("rcount", o.optInt("count")).coerceAtLeast(0), pinned)
}

private fun comments(a: JSONArray?, pinned: Boolean = false) =
    (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it)?.let { o -> comment(o, pinned) } }

fun parseCommentPage(raw: String): CommentPage {
    val d = commentData(raw)
    val cursor = d.optJSONObject("cursor") ?: throw IOException("评论分页信息缺失")
    val next = cursor.optJSONObject("pagination_reply")?.optString("next_offset")?.takeIf { it.isNotBlank() }
    val ended = cursor.opt("is_end") as? Boolean ?: throw IOException("评论分页信息缺失")
    if (!ended && next == null) throw IOException("评论下一页信息缺失，请重试")
    val pins = comments(d.optJSONArray("top_replies"), pinned = true) +
        listOfNotNull(d.optJSONObject("upper")?.optJSONObject("top")?.let { comment(it, pinned = true) })
    return CommentPage((pins + comments(d.optJSONArray("replies"))).distinctBy { it.id },
        cursor.optLong("all_count").coerceAtLeast(0), if (ended) null else next)
}
