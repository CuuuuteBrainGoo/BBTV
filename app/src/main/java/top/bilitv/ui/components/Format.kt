package top.bilitv.ui.components

import java.util.Locale
import top.bilitv.data.model.DynamicItem
import top.bilitv.data.model.FeedItem

/**
 * 图片地址补协议。
 *
 * B 站接口返回的封面大多是 `http://i0.hdslb.com/...` 或 `//i0.hdslb.com/...`，
 * 而 Android 9（API 28）起默认**禁止明文 HTTP**。B 站 CDN 同样支持 HTTPS，
 * 所以直接升级协议，比开 `usesCleartextTraffic` 这种全局放行安全得多。
 */
fun String.fixedScheme(): String = when {
    startsWith("//") -> "https:$this"
    startsWith("http://") -> "https://${substring(7)}"
    else -> this
}

/** 播放量/弹幕数：10 万、1.2 亿 */
fun formatCount(value: Long): String = when {
    value >= 100_000_000L -> String.format(Locale.CHINA, "%.1f亿", value / 100_000_000.0)
    value >= 10_000L -> String.format(Locale.CHINA, "%.1f万", value / 10_000.0)
    else -> value.toString()
}

/**
 * 字节数 → 人话，给设置页显示缓存占用。
 *
 * 用 **1024 进制**并且单位写 `KB/MB/GB` —— 和用户在系统设置里看到的数字口径一致。
 * 写成 1000 进制的话，同一份缓存在两处显示的数字会不一样，用户会以为哪个在骗他。
 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/** 秒 → mm:ss / h:mm:ss */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "--:--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.CHINA, "%02d:%02d", m, s)
}

/**
 * 投稿时间 → 人看得懂的一小段字。
 *
 * 和 BT 那行「UP主 · 昨天 14:15」对齐。做成**纯函数**（`nowSec` 可传），
 * 否则一写就变成 `System.currentTimeMillis()` 焊死在实现里，没法单测。
 *
 * @param pubSec 接口给的 `pubdate`（Unix 秒）。<=0 表示没有，返回空串
 */
fun formatPubDate(pubSec: Long, nowSec: Long = System.currentTimeMillis() / 1000L): String {
    if (pubSec <= 0L) return ""
    val diff = nowSec - pubSec
    if (diff < 0L) return ""                       // 时钟不同步就往回退，别显示「-3小时前」
    if (diff < 60L) return "刚刚"
    if (diff < 3600L) return "${diff / 60}分钟前"
    if (diff < 86_400L) return "${diff / 3600}小时前"
    if (diff < 172_800L) return "昨天"

    val zone = java.util.TimeZone.getDefault()
    val p = java.util.Calendar.getInstance(zone).apply { timeInMillis = pubSec * 1000L }
    val n = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowSec * 1000L }
    return if (p.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR)) {
        "${p.get(java.util.Calendar.MONTH) + 1}月${p.get(java.util.Calendar.DAY_OF_MONTH)}日"
    } else {
        "${p.get(java.util.Calendar.YEAR)}年${p.get(java.util.Calendar.MONTH) + 1}月" +
                "${p.get(java.util.Calendar.DAY_OF_MONTH)}日"
    }
}

/**
 * 卡片 / 主视觉标题下面那一行元信息，如 `燃茶哥哥在此 · 368.1万播放 · 04:42`。
 *
 * 和 [dynamicStatLine] 同一条规矩：**取不到的项，一个字都不出现**。
 * UP 主名为空就不画那一段，播放量取不到（`<= 0`）也不画 —— 「0 播放」是句假话，
 * 比空着更糟（`docs/19` §2 的反思）。三段全缺返回**空串**，调用方据此整行不画。
 *
 * 做成纯函数是为了能单测；主视觉和（后续的）卡片共用同一份拼法，不会两处写歪。
 */
fun feedMetaLine(item: FeedItem): String = buildList {
    if (item.ownerName.isNotBlank()) add(item.ownerName)
    if (item.viewCount > 0L) add("${formatCount(item.viewCount)}播放")
    if (item.durationSec > 0) add(formatDuration(item.durationSec))
}.joinToString(" · ")

/**
 * 动态卡片底部那一行数字，如 `播放 12.3万 · 弹幕 46 · 点赞 1.2千`。
 *
 * ## ★ 核心规则：**取不到的项，一个字都不出现**
 *
 * [DynamicItem] 里那五个计数都是**可空**的，`null` 的含义是"接口压根没给这个字段"。
 * 这时**不能写 0** —— 「0 播放」是句假话，比空着更糟：用户会以为这个视频没人看过。
 *
 * 这正是 2026-09-29 直播页踩过的那个坑（`optInt(...,0)` 的兜底值被当结论用，
 * 30 张卡片 30 句谎话，见 `docs/19` §2）。区别是那次的错在调用点，
 * 这次把它收进一个**纯函数**里，于是它有了单测。
 *
 * 五个全都没给 → 返回**空串**，界面据此整行不画（不是画一行空白）。
 */
fun dynamicStatLine(item: DynamicItem): String {
    val parts = buildList {
        item.play?.let { add("播放 ${formatCount(it)}") }
        item.danmaku?.let { add("弹幕 ${formatCount(it)}") }
        item.like?.let { add("点赞 ${formatCount(it)}") }
        item.comment?.let { add("评论 ${formatCount(it)}") }
        item.forward?.let { add("转发 ${formatCount(it)}") }
    }
    return parts.joinToString("   ·   ")
}

/**
 * 动态卡片作者那一行的后缀，如 `投稿了视频 · 3小时前`。
 *
 * 两个来源都可能缺（接口没给 `pub_action` / 没给 `pub_ts`），缺谁就少谁，
 * 两个都缺返回空串 —— 界面就不画这一段，而不是留一个孤零零的 `·`。
 *
 * `nowSec` 可由调用方注入，好让单测不依赖真实时钟。
 */
fun dynamicAuthorSuffix(item: DynamicItem, nowSec: Long = System.currentTimeMillis() / 1000L): String {
    val whenText = item.pubTs?.let { formatPubDate(it, nowSec) }.orEmpty()
    return listOf(item.action, whenText)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}
