package top.bilitv.data.model

import org.json.JSONObject

data class VideoRelation(val liked: Boolean, val coins: Int, val favorited: Boolean)

/** 账号写操作须有明确 code=0；缺字段、业务失败和空响应都不能算成功。 */
fun requireActionResponse(json: String): JSONObject {
    val root = JSONObject(json)
    val code = (root.opt("code") as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt() ?: Int.MIN_VALUE
    check(code == 0) {
        when (code) {
            -101 -> "登录已过期，请重新扫码登录"
            -111 -> "登录凭证不完整，请重新扫码登录"
            Int.MIN_VALUE -> "接口响应不完整，操作结果未知，请刷新状态后再试"
            else -> root.optString("message").takeIf { it.isNotBlank() && it != "0" }
                ?.take(160) ?: "操作失败（$code）"
        }
    }
    return root
}

fun parseVideoRelation(json: String): VideoRelation {
    val data = requireActionResponse(json).getJSONObject("data")
    fun flag(key: String): Boolean = when (val value = data.get(key)) {
        is Boolean -> value
        is Number -> when (value.toDouble()) { 0.0 -> false; 1.0 -> true; else -> error("互动状态异常") }
        else -> error("互动状态缺失，请刷新后重试")
    }
    val number = data.get("coin") as? Number ?: error("已投币状态缺失，请刷新后重试")
    val coins = number.toInt()
    check(coins in 0..2 && number.toDouble() == coins.toDouble()) { "已投币状态异常，请刷新后重试" }
    return VideoRelation(flag("like"), coins, flag("favorite"))
}

/** 原创最多两枚，转载最多一枚；每次根据服务端已投数计算剩余额度。 */
fun coinsToAdd(already: Int, copyright: Int): Int {
    require(already in 0..2)
    val limit = when (copyright) { 1 -> 2; 2 -> 1; else -> error("无法确定视频投币上限，请稍后重试") }
    return (limit - already).coerceAtLeast(0)
}
