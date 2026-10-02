package top.bilitv.data.auth

import java.security.MessageDigest

/**
 * B 站 Web 接口签名（WBI）。
 *
 * 算法来源：bilibili-API-collect docs/misc/sign/wbi.md
 * 关键点：
 *  - img_key / sub_key 每日轮换，从 /x/web-interface/nav 获取，本地缓存约 12 小时
 *  - mixin_key 由固定 64 位置换表重排后取前 32 位
 *  - 参数按 key 排序、过滤 !'()*、按 encodeURIComponent 规则编码（十六进制大写、空格为 %20）
 *  - 编码错一个字符就会得到 -412
 */
object Wbi {

    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    )

    private const val HEX = "0123456789ABCDEF"

    /** img_key + sub_key -> 32 位 mixin_key */
    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        return MIXIN_KEY_ENC_TAB.joinToString("") { raw[it].toString() }.take(32)
    }

    /** 由 nav 接口返回的 img_url / sub_url 文件名提取 key */
    fun keyFromUrl(url: String): String =
        url.substringAfterLast('/').substringBefore('.')

    /**
     * 给参数加上 w_rid 与 wts。
     * @param wts 时间戳（秒）。测试时可固定传入。
     */
    fun sign(
        params: Map<String, String>,
        imgKey: String,
        subKey: String,
        wts: Long = System.currentTimeMillis() / 1000
    ): Map<String, String> {
        val mixin = mixinKey(imgKey, subKey)
        val withTs = params.toMutableMap().apply { put("wts", wts.toString()) }
        val query = withTs.keys.sorted().joinToString("&") { k ->
            "${encode(k)}=${encode(clean(withTs[k].orEmpty()))}"
        }
        val wRid = md5(query + mixin)
        withTs["w_rid"] = wRid
        return withTs
    }

    private fun clean(value: String): String = value.replace(Regex("[!'()*]"), "")

    /** encodeURIComponent 等价实现：十六进制大写、空格为 %20 */
    fun encode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            val reserved = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'
            if (reserved) {
                out.append(c)
            } else {
                out.append('%').append(HEX[(byte.toInt() and 0xFF) shr 4]).append(HEX[byte.toInt() and 0x0F])
            }
        }
        return out.toString()
    }

    fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
