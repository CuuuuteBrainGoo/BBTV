package top.bilitv.data.danmaku

import org.json.JSONObject

data class DanmakuCloudProfile(val level: Int = 0, val reportWords: List<String> = emptyList(),
    val interactions: List<DanmakuItem> = emptyList())

fun parseDanmakuCloudProfile(data: ByteArray): DanmakuCloudProfile {
    require(data.size <= 1_048_576) { "弹幕元数据超过 1 MiB" }
    var serviceLevel = 0; var serviceOn = false
    var playerLevel = 0; var playerOn = false; var hasPlayer = false
    val reports = ArrayList<String>()
    val interactions = ArrayList<DanmakuItem>()
    Pbf(data).fields(bytes = { field, value ->
        when (field) {
            9 -> if (interactions.size < 256 && value.size <= 16_384) {
                runCatching { parseCommandText(value) }.getOrNull()?.let(interactions::add)
            }
            5 -> Pbf(value).fields(number = { f, v -> if (f == 1) serviceLevel = v.toInt() else if (f == 3) serviceOn = v != 0L })
            10 -> {
                hasPlayer = true
                Pbf(value).fields(number = { f, v -> if (f == 2) playerOn = v != 0L else if (f == 3) playerLevel = v.toInt() })
            }
            11 -> if (reports.size < 64) reports.add(String(value, Charsets.UTF_8).take(256))
        }
    })
    val level = if (hasPlayer) { if (playerOn) playerLevel else 0 } else if (serviceOn) serviceLevel else 0
    return DanmakuCloudProfile(level.coerceIn(0, 10), reports, interactions.sortedBy { it.timeMs })
}

/** CommandDm field4/5/6/9；extra中的图标、URL和指令一律不执行。 */
private fun parseCommandText(data: ByteArray): DanmakuItem? {
    var command = ""; var content = ""; var extra = ""; var time = -1L; var uid = 0L
    Pbf(data).fields(number = { f, v -> if (f == 6) time = v else if (f == 3) uid = v }, bytes = { f, v ->
        when (f) {
            4 -> command = v.toString(Charsets.UTF_8).take(32)
            5 -> content = v.toString(Charsets.UTF_8).take(80).trim()
            9 -> if (v.size <= 4_096) extra = v.toString(Charsets.UTF_8)
        }
    })
    if (time !in 0L..86_400_000L) return null
    val payload = runCatching { JSONObject(extra) }.getOrNull()
    val label = when (command) {
        "#UP#" -> "UP 主"
        "#LINK#" -> "关联视频"
        "#ATTENTION#" -> "UP 主提示"
        else -> "提示"
    }
    if (content.isBlank()) content = when (command) {
        "#LINK#" -> payload?.optString("title").orEmpty().take(80).trim()
        "#ATTENTION#" -> when (payload?.optInt("type", -1)) {
            0 -> "关注 UP 主"; 1 -> "点赞、投币、收藏"; 2 -> "关注 UP 主，点赞、投币、收藏"; else -> ""
        }
        else -> ""
    }
    if (content.isBlank()) return null
    return DanmakuItem(time, 5, 25, 0xFFFFFF, "$label · $content", interaction = true,
        midHash = if (uid > 0) normalizeDmUser("uid:$uid").orEmpty() else "")
}

/** 无规则与规则加载失败必须区分，业务码／结构异常交给界面提示。 */
fun parseDanmakuCloudRules(body: String, reportWords: List<String> = emptyList()): DanmakuRules {
    val root = JSONObject(body)
    check(root.optInt("code", -1) == 0) { if (root.optInt("code") == -101) "账号 Cookie 已失效，请重新扫码登录" else "云屏蔽规则加载失败（${root.optInt("code", -1)}），本地规则仍生效" }
    val data = root.getJSONObject("data")
    check(!data.has("rule") || data.isNull("rule") || data.optJSONArray("rule") != null) { "云屏蔽规则格式已变化" }
    val rules = data.optJSONArray("rule") ?: org.json.JSONArray()
    val words = reportWords.toMutableList(); val regex = ArrayList<String>(); val users = HashSet<String>()
    for (i in 0 until rules.length()) {
        val rule = rules.optJSONObject(i) ?: continue
        val value = rule.optString("filter", rule.optString("filter_content", rule.optString("content"))).trim()
        if (value.isBlank()) continue
        when (rule.optInt("type", -1)) {
            0 -> words.add(value)
            1 -> regex.add(value)
            2 -> {
                val key = if (value.length > 8 && value.all { it.isDigit() }) "uid:$value" else value
                normalizeDmUser(key)?.let(users::add)
            }
        }
    }
    return DanmakuRules(words, regex, users)
}
