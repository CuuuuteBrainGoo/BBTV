package top.bilitv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.bilitv.data.danmaku.DANMAKU_LEVEL_LABELS
import top.bilitv.data.settings.SettingsCatalog
import top.bilitv.data.settings.SettingsCatalog.SettingsItem
import top.bilitv.data.settings.SettingsStore
import top.bilitv.data.settings.SubtitleStyle
import top.bilitv.data.model.SubtitleTrack
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import java.util.regex.Pattern
import kotlin.math.roundToInt

/** 设置页和播放菜单共用同一套整行控件与存储，不再各写一套加减按钮。 */
@Composable
internal fun DanmakuSettingsPage(settings: SettingsStore, modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null, onChanged: () -> Unit = {},
    subtitleTracks: List<SubtitleTrack> = emptyList(), subtitleStatus: String? = null,
    onSubtitleRetry: (() -> Unit)? = null) {
    var revision by remember { mutableIntStateOf(0) }
    val items = remember(revision) {
        SettingsCatalog.itemsIn(SettingsCatalog.SettingsCategory.DANMAKU, true).toMutableList().apply {
            add(indexOf(SettingsItem.SUBTITLE_LANGUAGE), SettingsItem.SUBTITLE_ON)
        }
    }
    items.forEachIndexed { index, item ->
        if (item == SettingsItem.DANMAKU_ON || item == SettingsItem.SUBTITLE_ON) {
            Text(if (item == SettingsItem.SUBTITLE_ON) "字幕" else "弹幕", color = AppTheme.current.primary,
                modifier = Modifier.padding(vertical = 8.dp))
        }
        DanmakuSettingItem(item, settings, revision,
            modifier.then(if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
            subtitleTracks, subtitleStatus) {
            revision++; onChanged()
        }
        if (item == SettingsItem.SUBTITLE_ON && subtitleStatus != null && onSubtitleRetry != null) {
            SettingRow("字幕来源", subtitleStatus, "重新加载", modifier) { onSubtitleRetry() }
        }
    }
}

@Composable
internal fun DanmakuSettingItem(item: SettingsItem, s: SettingsStore, revision: Int = 0, modifier: Modifier = Modifier,
    subtitleTracks: List<SubtitleTrack> = emptyList(), subtitleStatus: String? = null, changed: () -> Unit) {
    // SharedPreferences 本身不可观察；让每一行在页内修改／重置后重新读取，而不重建焦点节点。
    val filter = remember(revision) { s.danmakuFilter }
    val subtitle = s.subtitleStyle
    fun commit(action: () -> Unit) { action(); changed() }
    when (item) {
        SettingsItem.DANMAKU_ON -> ToggleRow("弹幕开关", null, s.danmakuEnabled, { commit { s.danmakuEnabled = it } }, modifier)
        SettingsItem.DANMAKU_ALPHA -> ChoiceRow("不透明度", null, (1..10).map { it / 10f }, s.danmakuAlpha,
            { "${(it * 100).roundToInt()}%" }, { commit { s.danmakuAlpha = it } }, modifier)
        SettingsItem.DANMAKU_SCALE -> ChoiceRow("字体大小", "3 为原始字号", (1..8).toList(),
            ((s.danmakuScale - .6f) / .2f).roundToInt().plus(1).coerceIn(1, 8), { "$it" },
            { commit { s.danmakuScale = .6f + (it - 1) * .2f } }, modifier)
        SettingsItem.DANMAKU_AREA -> ChoiceRow("占屏比", "从顶部向下分配；缩小区域会减少不重叠弹幕数量", (1..5).toList(), s.danmakuArea,
            { if (it == 5) "全屏" else "$it/5" }, { commit { s.danmakuArea = it; s.danmakuMaxLines = 0 } }, modifier)
        SettingsItem.DANMAKU_OVERLAP -> ToggleRow("允许重叠", "关闭时按区域安排空闲轨道，放不下的弹幕不显示", s.danmakuOverlap,
            { commit { s.danmakuOverlap = it } }, modifier)
        SettingsItem.DANMAKU_SPEED -> {
            val speeds = listOf(20_000L, 12_000L, 8_000L, 6_000L, 4_000L)
            val labels = listOf("极慢", "慢", "适中", "快", "极快")
            ChoiceRow("速度", "恒速横移，不随文本长度改变", (speeds + s.danmakuDurationMs).distinct(), s.danmakuDurationMs,
                { v -> speeds.indexOf(v).takeIf { it >= 0 }?.let { labels[it] } ?: "${v / 1000f} 秒" },
                { commit { s.danmakuDurationMs = it } }, modifier)
        }
        SettingsItem.DANMAKU_SCROLL -> ToggleRow("允许滚动弹幕", null, filter.allowScroll,
            { commit { s.danmakuFilter = filter.copy(allowScroll = it) } }, modifier)
        SettingsItem.DANMAKU_REVERSE -> ToggleRow("允许逆向弹幕", null, filter.allowReverse,
            { commit { s.danmakuFilter = filter.copy(allowReverse = it) } }, modifier)
        SettingsItem.DANMAKU_TOP -> ToggleRow("允许顶部悬停弹幕", null, filter.allowTop,
            { commit { s.danmakuFilter = filter.copy(allowTop = it) } }, modifier)
        SettingsItem.DANMAKU_BOTTOM -> ToggleRow("允许底部悬停弹幕", null, filter.allowBottom,
            { commit { s.danmakuFilter = filter.copy(allowBottom = it) } }, modifier)
        SettingsItem.DANMAKU_COLOR -> ToggleRow("允许彩色弹幕", null, filter.allowColor,
            { commit { s.danmakuFilter = filter.copy(allowColor = it) } }, modifier)
        SettingsItem.DANMAKU_ADVANCED -> ToggleRow("允许高级弹幕", "动画文字；不执行网络脚本", filter.allowAdvanced,
            { commit { s.danmakuFilter = filter.copy(allowAdvanced = it) } }, modifier)
        SettingsItem.DANMAKU_MERGE -> ToggleRow("去重合并", "屏上相同内容并为一条，显示 ×次数", s.danmakuMerge,
            { commit { s.danmakuMerge = it } }, modifier)
        SettingsItem.DANMAKU_REPEAT -> ToggleRow("重复弹幕隐藏", "5 秒内第三次及后续相同内容不显示", s.danmakuHideRepeated,
            { commit { s.danmakuHideRepeated = it } }, modifier)
        SettingsItem.DANMAKU_LEVEL -> ChoiceRow("弹幕屏蔽等级", "按服务器权重筛选；精选只留已评分的高权重弹幕",
            (0..5).toList(), filter.level, { DANMAKU_LEVEL_LABELS[it] },
            { commit { s.danmakuFilter = filter.copy(level = it) } }, modifier)
        SettingsItem.DANMAKU_CLOUD -> ToggleRow("弹幕云屏蔽", "读取账号屏蔽规则；小电视扫码取得的 Cookie 也可使用", filter.cloud,
            { commit { s.danmakuFilter = filter.copy(cloud = it) } }, modifier)
        SettingsItem.DANMAKU_INTERACTION -> ToggleRow("互动弹幕", "只显示文字；十秒最多一条，同屏最多一条；不提供点击操作", filter.allowInteraction,
            { commit { s.danmakuFilter = filter.copy(allowInteraction = it) } }, modifier)
        SettingsItem.DANMAKU_OUTLINE -> ChoiceRow("弹幕描边宽度", "文字边框粗细", listOf(1.2f,1.6f,2f,2.4f,3f,3.6f,4.2f,5f), s.danmakuOutline,
            { "$it" }, { commit { s.danmakuOutline = it } }, modifier)
        SettingsItem.DANMAKU_OUTLINE_ALPHA -> ChoiceRow("弹幕描边最小 Alpha", "越大描边越深", listOf(120,130,140,150,160,165,170,180,190,200,210,220),
            s.danmakuOutlineAlpha, { "$it" }, { commit { s.danmakuOutlineAlpha = it } }, modifier)
        SettingsItem.DANMAKU_TRACK -> ChoiceRow("弹幕轨道高度倍率", "轨道高度 = 字号 × 倍率", (0..6).map { 1f + it * .2f },
            s.danmakuTrackHeight, { String.format(java.util.Locale.US, "%.1f", it) }, { commit { s.danmakuTrackHeight = it } }, modifier)
        SettingsItem.DANMAKU_LINES -> ChoiceRow("弹幕显示行数", "额外行数上限，与占屏比同时生效", listOf(0) + (2..20 step 2), s.danmakuMaxLines,
            { if (it == 0) "不限" else "$it" }, { commit { s.danmakuMaxLines = it } }, modifier)
        SettingsItem.DANMAKU_KEYWORDS -> RuleEditor("屏蔽词", s.danmakuKeywords, 128, modifier) { commit { s.danmakuKeywords = it } }
        SettingsItem.DANMAKU_REGEX -> RuleEditor("屏蔽正则", s.danmakuRegexes, 32, modifier, regex = true) { commit { s.danmakuRegexes = it } }
        SettingsItem.DANMAKU_USERS -> RuleEditor("屏蔽用户", s.danmakuBlockedUsers.toList(), 512, modifier, users = true) {
            commit { s.danmakuBlockedUsers = it.toSet() }
        }
        SettingsItem.SUBTITLE_ON -> ToggleRow("字幕开关", "使用视频提供的外挂字幕；不影响视频内嵌文字", s.subtitleEnabled,
            { commit { s.subtitleEnabled = it } }, modifier)
        SettingsItem.SUBTITLE_LANGUAGE -> ChoiceRow("字幕语言", subtitleStatus ?: "语言列表在播放时从当前视频读取",
            listOf("") + subtitleTracks.map { it.language }.distinct(), s.subtitleLanguage,
            { lan -> if (lan.isEmpty()) "自动" else subtitleTracks.firstOrNull { it.language == lan }?.label ?: lan },
            { commit { s.subtitleLanguage = it } }, modifier)
        SettingsItem.SUBTITLE_FONT -> ChoiceRow("字幕字体大小", null, (0..4).toList(), subtitle.font,
            { SubtitleStyle.FONTS[it] }, { commit { s.subtitleStyle = subtitle.copy(font = it) } }, modifier)
        SettingsItem.SUBTITLE_COLOR -> ChoiceRow("字幕颜色", null, (0..7).toList(), subtitle.color,
            { SubtitleStyle.COLORS[it] }, { commit { s.subtitleStyle = subtitle.copy(color = it) } }, modifier)
        SettingsItem.SUBTITLE_POSITION -> {
            ChoiceRow("字幕位置", null, (0..6).toList(), subtitle.position, { SubtitleStyle.POSITIONS[it] },
                { commit { s.subtitleStyle = subtitle.copy(position = it) } }, modifier)
            if (subtitle.position == 6) {
                ChoiceRow("字幕水平位置", "左 0% → 右 100%，自动保留边距", (5..95 step 5).toList(), subtitle.x,
                    { "$it%" }, { commit { s.subtitleStyle = subtitle.copy(x = it) } }, modifier)
                ChoiceRow("字幕垂直位置", "上 0% → 下 100%，自动保留边距", (5..95 step 5).toList(), subtitle.y,
                    { "$it%" }, { commit { s.subtitleStyle = subtitle.copy(y = it) } }, modifier)
            }
        }
        SettingsItem.SUBTITLE_BACKGROUND -> ChoiceRow("字幕背景不透明度", "0% 为无背景", (0..100 step 10).toList(), subtitle.background,
            { "$it%" }, { commit { s.subtitleStyle = subtitle.copy(background = it) } }, modifier)
        SettingsItem.SUBTITLE_FADE -> ToggleRow("字幕淡入淡出", null, subtitle.fade,
            { commit { s.subtitleStyle = subtitle.copy(fade = it) } }, modifier)
        SettingsItem.DANMAKU_RESET -> SettingRow("重置本页设置", "只重置弹幕和字幕，保留其他设置与账号", "重置", modifier) {
            commit { s.resetDanmakuPage() }
        }
        else -> error("${item.name} 不是弹幕／字幕设置")
    }
}

@Composable
private fun RuleEditor(title: String, rules: List<String>, max: Int, modifier: Modifier,
    regex: Boolean = false, users: Boolean = false, save: (List<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val theme = AppTheme.current
    SettingRow(title, if (users) "填写 uid:用户ID 或匿名十六进制标识，一行一条" else "一行一条，最多 $max 条", "${rules.size} 条", modifier) { open = true }
    if (open) {
        var text by remember { mutableStateOf(rules.joinToString("\n")) }
        var error by remember { mutableStateOf<String?>(null) }
        val first = remember { FocusRequester() }
        Dialog(onDismissRequest = { open = false }) {
            Column(Modifier.fillMaxWidth().background(theme.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, color = theme.textPrimary)
                BasicTextField(text, { text = it.take(34_000); error = null }, textStyle = TextStyle(color = theme.textPrimary, fontSize = AppType.Body2),
                    modifier = Modifier.fillMaxWidth().height(170.dp).background(theme.surfaceHigh).padding(12.dp).focusRequester(first))
                error?.let { Text(it, color = theme.primary) }
                TvCard(onClick = {
                    val values = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    try {
                        require(values.size <= max && values.all { it.length <= 256 }) { "最多 $max 条，每条最多 256 字" }
                        if (regex) values.forEach { Pattern.compile(it.removeSurrounding("/")) }
                        val normalized = if (users) values.map { top.bilitv.data.danmaku.normalizeDmUser(it) ?: error("用户标识格式无效") } else values
                        save(normalized); open = false
                    } catch (e: Exception) { error = if (e is java.util.regex.PatternSyntaxException) "正则格式错误，请修改后保存" else e.message ?: "规则无效" }
                }, focusedScale = 1f, contentDescription = "保存$title") { Text("保存", color = theme.primary, modifier = Modifier.padding(12.dp)) }
            }
            RequestFocusOnAppear(first, true)
        }
    }
}
