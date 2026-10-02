package top.bilitv.data.danmaku

import java.util.Locale
import java.util.regex.Pattern

/** 六档本地阈值；名称沿用 BT，数值明确为 BBTV 的映射。 */
val DANMAKU_LEVEL_LABELS = listOf("关闭", "极轻", "轻微", "标准", "严格", "精选")
private val LEVEL_WEIGHTS = listOf(0, 1, 3, 5, 7, 9)

fun normalizeDmUser(raw: String): String? {
    val value = raw.trim().lowercase(Locale.US)
    if (value.startsWith("uid:")) {
        val uid = value.removePrefix("uid:").toLongOrNull()?.takeIf { it > 0 } ?: return null
        return java.util.zip.CRC32().apply { update(uid.toString().toByteArray(Charsets.UTF_8)) }.value.toString(16).padStart(8, '0')
    }
    return value.takeIf { it.length in 1..8 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }?.padStart(8, '0')
}

data class DanmakuFilterOptions(
    val level: Int = 0,
    val allowScroll: Boolean = true,
    val allowReverse: Boolean = true,
    val allowTop: Boolean = true,
    val allowBottom: Boolean = true,
    val allowColor: Boolean = true,
    val allowAdvanced: Boolean = false,
    val cloud: Boolean = false,
    val hideRepeated: Boolean = false,
    val allowInteraction: Boolean = true,
)

/** 规则在加载／修改时编译一次，绝不在绘制帧中做网络、编译或全文筛选。 */
class DanmakuRules(
    keywords: List<String> = emptyList(),
    regexes: List<String> = emptyList(),
    users: Set<String> = emptySet(),
) {
    // ponytail: O(条目×规则) 一次性后台筛选；账号规则非常多且实际耗时明显时再建索引。
    private val words = keywords.filter { it.isNotBlank() }.distinct()
    private val patterns = regexes.distinct().mapNotNull { raw ->
        if (raw.length > 256) null else runCatching { Pattern.compile(raw.removeSurrounding("/")) }.getOrNull()
    }
    private val rejected = java.util.concurrent.ConcurrentHashMap.newKeySet<Pattern>()
    private val invalidRegexCount = regexes.distinct().size - patterns.size
    val rejectedRegexes: Int get() = invalidRegexCount + rejected.size
    private val hashes = users.map { it.trim().lowercase(Locale.US).padStart(8, '0') }.toSet()
    fun blocks(item: DanmakuItem): Boolean {
        if (item.midHash.isNotBlank() && item.midHash.lowercase(Locale.US).padStart(8, '0') in hashes) return true
        val content = item.advanced?.text ?: item.content
        if (words.any { content.contains(it) }) return true
        val text = BoundedRegexText(content.take(4096))
        return patterns.any { pattern ->
            if (pattern in rejected) false else {
                text.reset()
                try { pattern.matcher(text).find() }
                catch (_: RegexBudgetExceeded) { rejected.add(pattern); false }
                catch (_: StackOverflowError) { rejected.add(pattern); false }
            }
        }
    }
}

/** Java 正则回溯必须可中断：对实际字符访问计数，耗尽预算拒绝该次匹配。 */
private class RegexBudgetExceeded : RuntimeException(null, null, false, false)
private class BoundedRegexText(private val text: String, private val budget: IntArray = intArrayOf(32_768)) : CharSequence {
    fun reset() { budget[0] = 32_768 }
    override val length: Int get() = text.length
    override fun get(index: Int): Char {
        if (--budget[0] < 0) throw RegexBudgetExceeded()
        return text[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        BoundedRegexText(text.substring(startIndex, endIndex), budget)
    override fun toString(): String = text
}

fun filterDanmaku(
    items: List<DanmakuItem>, options: DanmakuFilterOptions,
    cloudLevel: Int = 0, cloudRules: DanmakuRules = DanmakuRules(),
    localRules: DanmakuRules = DanmakuRules(),
): List<DanmakuItem> {
    val level = options.level.coerceIn(0, 5)
    val threshold = maxOf(LEVEL_WEIGHTS[level], if (options.cloud) cloudLevel.coerceIn(0, 10) else 0)
    val recent = HashMap<String, java.util.ArrayDeque<Long>>()
    var lastInteraction = -10_000L
    val interactionTimes = HashMap<String, Long>()
    // ponytail: CommandDm最多256条；后台排序/限流，屏上由现有轨道再限制为一条。
    val ordered = if (items.any { it.interaction }) items.sortedBy { it.timeMs } else items
    return ordered.filter { item ->
        val typeAllowed = if (item.interaction) options.allowInteraction else when (item.mode) {
            1, 2, 3 -> options.allowScroll
            6 -> options.allowScroll && options.allowReverse
            4 -> options.allowBottom
            5 -> options.allowTop
            7 -> options.allowAdvanced && item.advanced != null
            else -> false // 脚本弹幕不能执行来自网络的代码。
        }
        val times = if (options.hideRepeated) recent.getOrPut(item.content) { java.util.ArrayDeque() } else null
        while (times?.peekFirst()?.let { it < item.timeMs - 5_000L } == true) times.removeFirst()
        times?.addLast(item.timeMs)
        val allowed = (times == null || times.size < 3) && typeAllowed && (options.allowColor || item.color == 0 || item.color == 0xFFFFFF) &&
            (item.interaction || item.weight == 0 && level < 5 || item.weight >= threshold) &&
            !localRules.blocks(item) && (!options.cloud || !cloudRules.blocks(item))
        if (!allowed || !item.interaction) allowed else {
            val duplicate = interactionTimes[item.content]?.let { item.timeMs - it < 60_000L } == true
            if (duplicate || item.timeMs - lastInteraction < 10_000L) false else {
                lastInteraction = item.timeMs; interactionTimes[item.content] = item.timeMs; true
            }
        }
    }
}
