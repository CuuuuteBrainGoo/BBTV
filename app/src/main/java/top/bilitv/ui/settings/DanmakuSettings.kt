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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import top.bilitv.R
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
            Text(if (item == SettingsItem.SUBTITLE_ON) stringResource(R.string.title_subtitles) else stringResource(R.string.title_danmaku), color = AppTheme.current.primary,
                modifier = Modifier.padding(vertical = 8.dp))
        }
        DanmakuSettingItem(item, settings, revision,
            modifier.then(if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
            subtitleTracks, subtitleStatus) {
            revision++; onChanged()
        }
        if (item == SettingsItem.SUBTITLE_ON && subtitleStatus != null && onSubtitleRetry != null) {
            SettingRow(stringResource(R.string.settings_subtitle_source), subtitleStatus, stringResource(R.string.action_reload), modifier) { onSubtitleRetry() }
        }
    }
}

@Composable
internal fun DanmakuSettingItem(item: SettingsItem, s: SettingsStore, revision: Int = 0, modifier: Modifier = Modifier,
    subtitleTracks: List<SubtitleTrack> = emptyList(), subtitleStatus: String? = null, changed: () -> Unit) {
    // SharedPreferences 本身不可观察；让每一行在页内修改／重置后重新读取，而不重建焦点节点。
    val context = LocalContext.current
    val filter = remember(revision) { s.danmakuFilter }
    val subtitle = s.subtitleStyle
    fun commit(action: () -> Unit) { action(); changed() }
    when (item) {
        SettingsItem.DANMAKU_ON -> ToggleRow(stringResource(R.string.settings_danmaku_toggle), null, s.danmakuEnabled, { commit { s.danmakuEnabled = it } }, modifier)
        SettingsItem.DANMAKU_ALPHA -> ChoiceRow(stringResource(R.string.settings_danmaku_alpha), null, (1..10).map { it / 10f }, s.danmakuAlpha,
            { "${(it * 100).roundToInt()}%" }, { commit { s.danmakuAlpha = it } }, modifier)
        SettingsItem.DANMAKU_SCALE -> ChoiceRow(stringResource(R.string.settings_danmaku_font), stringResource(R.string.settings_danmaku_font_hint), (1..8).toList(),
            ((s.danmakuScale - .6f) / .2f).roundToInt().plus(1).coerceIn(1, 8), { "$it" },
            { commit { s.danmakuScale = .6f + (it - 1) * .2f } }, modifier)
        SettingsItem.DANMAKU_AREA -> ChoiceRow(stringResource(R.string.settings_danmaku_area), stringResource(R.string.settings_danmaku_area_hint), (1..5).toList(), s.danmakuArea,
            { if (it == 5) context.getString(R.string.value_fullscreen) else "$it/5" }, { commit { s.danmakuArea = it } }, modifier)
        SettingsItem.DANMAKU_OVERLAP -> ToggleRow(stringResource(R.string.settings_danmaku_overlap), stringResource(R.string.settings_danmaku_overlap_hint), s.danmakuOverlap,
            { commit { s.danmakuOverlap = it } }, modifier)
        SettingsItem.DANMAKU_SPEED -> {
            val speeds = listOf(20_000L, 12_000L, 8_000L, 6_000L, 4_000L)
            val labels = listOf(R.string.speed_very_slow, R.string.speed_slow, R.string.speed_medium, R.string.speed_fast, R.string.speed_very_fast).map { context.getString(it) }
            ChoiceRow(stringResource(R.string.settings_danmaku_speed), stringResource(R.string.settings_danmaku_speed_hint), (speeds + s.danmakuDurationMs).distinct(), s.danmakuDurationMs,
                { v -> speeds.indexOf(v).takeIf { it >= 0 }?.let { labels[it] } ?: context.getString(R.string.value_duration_seconds, v / 1000f) },
                { commit { s.danmakuDurationMs = it } }, modifier)
        }
        SettingsItem.DANMAKU_SCROLL -> ToggleRow(stringResource(R.string.settings_danmaku_scroll), null, filter.allowScroll,
            { commit { s.danmakuFilter = filter.copy(allowScroll = it) } }, modifier)
        SettingsItem.DANMAKU_REVERSE -> ToggleRow(stringResource(R.string.settings_danmaku_reverse), null, filter.allowReverse,
            { commit { s.danmakuFilter = filter.copy(allowReverse = it) } }, modifier)
        SettingsItem.DANMAKU_TOP -> ToggleRow(stringResource(R.string.settings_danmaku_top), null, filter.allowTop,
            { commit { s.danmakuFilter = filter.copy(allowTop = it) } }, modifier)
        SettingsItem.DANMAKU_BOTTOM -> ToggleRow(stringResource(R.string.settings_danmaku_bottom), null, filter.allowBottom,
            { commit { s.danmakuFilter = filter.copy(allowBottom = it) } }, modifier)
        SettingsItem.DANMAKU_COLOR -> ToggleRow(stringResource(R.string.settings_danmaku_color), null, filter.allowColor,
            { commit { s.danmakuFilter = filter.copy(allowColor = it) } }, modifier)
        SettingsItem.DANMAKU_ADVANCED -> ToggleRow(stringResource(R.string.settings_danmaku_advanced), stringResource(R.string.settings_danmaku_advanced_hint), filter.allowAdvanced,
            { commit { s.danmakuFilter = filter.copy(allowAdvanced = it) } }, modifier)
        SettingsItem.DANMAKU_MERGE -> ToggleRow(stringResource(R.string.settings_danmaku_merge), stringResource(R.string.settings_danmaku_merge_hint), s.danmakuMerge,
            { commit { s.danmakuMerge = it } }, modifier)
        SettingsItem.DANMAKU_REPEAT -> ToggleRow(stringResource(R.string.settings_danmaku_repeat), stringResource(R.string.settings_danmaku_repeat_hint), s.danmakuHideRepeated,
            { commit { s.danmakuHideRepeated = it } }, modifier)
        SettingsItem.DANMAKU_LEVEL -> ChoiceRow(stringResource(R.string.settings_danmaku_level), stringResource(R.string.settings_danmaku_level_hint),
            (0..5).toList(), filter.level, { context.getString(listOf(R.string.value_off, R.string.filter_very_light, R.string.filter_light, R.string.filter_standard, R.string.filter_strict, R.string.filter_selected)[it]) },
            { commit { s.danmakuFilter = filter.copy(level = it) } }, modifier)
        SettingsItem.DANMAKU_CLOUD -> ToggleRow(stringResource(R.string.settings_danmaku_cloud), stringResource(R.string.settings_danmaku_cloud_hint), filter.cloud,
            { commit { s.danmakuFilter = filter.copy(cloud = it) } }, modifier)
        SettingsItem.DANMAKU_INTERACTION -> ToggleRow(stringResource(R.string.settings_danmaku_interaction), stringResource(R.string.settings_danmaku_interaction_hint), filter.allowInteraction,
            { commit { s.danmakuFilter = filter.copy(allowInteraction = it) } }, modifier)
        SettingsItem.DANMAKU_OUTLINE -> ChoiceRow(stringResource(R.string.settings_danmaku_outline), stringResource(R.string.settings_danmaku_outline_hint), listOf(1.2f,1.6f,2f,2.4f,3f,3.6f,4.2f,5f), s.danmakuOutline,
            { "$it" }, { commit { s.danmakuOutline = it } }, modifier)
        SettingsItem.DANMAKU_OUTLINE_ALPHA -> ChoiceRow(stringResource(R.string.settings_danmaku_outline_alpha), stringResource(R.string.settings_danmaku_outline_alpha_hint), listOf(120,130,140,150,160,165,170,180,190,200,210,220),
            s.danmakuOutlineAlpha, { "$it" }, { commit { s.danmakuOutlineAlpha = it } }, modifier)
        SettingsItem.DANMAKU_TRACK -> ChoiceRow(stringResource(R.string.settings_danmaku_track), stringResource(R.string.settings_danmaku_track_hint), (0..6).map { 1f + it * .2f },
            s.danmakuTrackHeight, { String.format(java.util.Locale.US, "%.1f", it) }, { commit { s.danmakuTrackHeight = it } }, modifier)
        SettingsItem.DANMAKU_LINES -> ChoiceRow(stringResource(R.string.settings_danmaku_lines), stringResource(R.string.settings_danmaku_lines_hint), listOf(0) + (2..20 step 2), s.danmakuMaxLines,
            { if (it == 0) context.getString(R.string.value_unlimited) else "$it" }, { commit { s.danmakuMaxLines = it } }, modifier)
        SettingsItem.DANMAKU_KEYWORDS -> RuleEditor(stringResource(R.string.settings_danmaku_keywords), s.danmakuKeywords, 128, modifier) { commit { s.danmakuKeywords = it } }
        SettingsItem.DANMAKU_REGEX -> RuleEditor(stringResource(R.string.settings_danmaku_regex), s.danmakuRegexes, 32, modifier, regex = true) { commit { s.danmakuRegexes = it } }
        SettingsItem.DANMAKU_USERS -> RuleEditor(stringResource(R.string.settings_danmaku_users), s.danmakuBlockedUsers.toList(), 512, modifier, users = true) {
            commit { s.danmakuBlockedUsers = it.toSet() }
        }
        SettingsItem.SUBTITLE_ON -> ToggleRow(stringResource(R.string.settings_subtitle), stringResource(R.string.settings_subtitle_api_hint), s.subtitleEnabled,
            { commit { s.subtitleEnabled = it } }, modifier)
        SettingsItem.SUBTITLE_LANGUAGE -> ChoiceRow(stringResource(R.string.settings_subtitle_language), subtitleStatus ?: stringResource(R.string.settings_subtitle_language_hint),
            listOf("") + subtitleTracks.map { it.language }.distinct(), s.subtitleLanguage,
            { lan -> if (lan.isEmpty()) context.getString(R.string.value_auto) else subtitleTracks.firstOrNull { it.language == lan }?.label ?: lan },
            { commit { s.subtitleLanguage = it } }, modifier)
        SettingsItem.SUBTITLE_FONT -> ChoiceRow(stringResource(R.string.settings_subtitle_font), null, (0..4).toList(), subtitle.font,
            { context.getString(listOf(R.string.value_tiny, R.string.value_small, R.string.value_medium, R.string.value_large, R.string.value_very_large)[it]) }, { commit { s.subtitleStyle = subtitle.copy(font = it) } }, modifier)
        SettingsItem.SUBTITLE_BACKGROUND -> ChoiceRow(stringResource(R.string.settings_subtitle_background), stringResource(R.string.settings_subtitle_background_hint), (0..100 step 10).toList(), subtitle.background,
            { "$it%" }, { commit { s.subtitleStyle = subtitle.copy(background = it) } }, modifier)
        SettingsItem.DANMAKU_RESET -> SettingRow(stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_danmaku_hint), stringResource(R.string.action_reset), modifier) {
            commit { s.resetDanmakuPage() }
        }
        else -> error("${item.name} 不是弹幕／字幕设置")
    }
}

@Composable
private fun RuleEditor(title: String, rules: List<String>, max: Int, modifier: Modifier,
    regex: Boolean = false, users: Boolean = false, save: (List<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val theme = AppTheme.current
    SettingRow(title, if (users) stringResource(R.string.rules_user_hint) else stringResource(R.string.rules_max_hint, max), stringResource(R.string.rules_count, rules.size), modifier) { open = true }
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
                        require(values.size <= max && values.all { it.length <= 256 }) { context.getString(R.string.rules_limits_error, max) }
                        if (regex) values.forEach { Pattern.compile(it.removeSurrounding("/")) }
                        val normalized = if (users) values.map { top.bilitv.data.danmaku.normalizeDmUser(it) ?: error(context.getString(R.string.rules_user_invalid)) } else values
                        save(normalized); open = false
                    } catch (e: Exception) { error = if (e is java.util.regex.PatternSyntaxException) context.getString(R.string.rules_regex_invalid) else e.message ?: context.getString(R.string.rules_invalid) }
                }, focusedScale = 1f, contentDescription = stringResource(R.string.rules_save_description, title)) { Text(stringResource(R.string.action_save), color = theme.primary, modifier = Modifier.padding(12.dp)) }
            }
            RequestFocusOnAppear(first, true)
        }
    }
}
