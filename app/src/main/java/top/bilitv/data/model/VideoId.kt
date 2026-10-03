package top.bilitv.data.model

/** Modern 51-bit AV/BV encoding; source algorithm (WTFPL):
 * https://github.com/Quintony/Ailiaili-api/blob/main/bilibili_api/utils/aid_bvid_transformer.py
 * Confirmed against /view for a real App recommendation (117342684187646).
 */
internal fun bvidOfAid(aid: Long): String? {
    if (aid <= 0 || aid >= (1L shl 51)) return null
    val alphabet = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
    val out = "BV1000000000".toCharArray()
    var value = (aid or (1L shl 51)) xor 23442827791579L
    var index = out.lastIndex
    while (value > 0) { out[index--] = alphabet[(value % 58).toInt()]; value /= 58 }
    out[3] = out[9].also { out[9] = out[3] }
    out[4] = out[7].also { out[7] = out[4] }
    return String(out)
}
