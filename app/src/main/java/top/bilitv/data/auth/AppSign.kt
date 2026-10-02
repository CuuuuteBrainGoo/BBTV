package top.bilitv.data.auth

/**
 * B 站 App / TV 接口签名（appkey + appsec）。
 * 用于 TV 扫码登录、部分 App 接口：参数排序 -> 拼接 -> 尾部追加 appsec -> MD5。
 */
object AppSign {

    fun sign(
        params: Map<String, String>,
        appkey: String,
        appsec: String
    ): Map<String, String> {
        val withKey = params.toMutableMap().apply { put("appkey", appkey) }
        val query = withKey.keys.sorted().joinToString("&") { k ->
            "${k}=${Wbi.encode(withKey[k].orEmpty())}"
        }
        withKey["sign"] = Wbi.md5(query + appsec)
        return withKey
    }
}
