package top.bilitv.ui.player

import top.bilitv.ui.components.scrollWithScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import top.bilitv.data.settings.*
import top.bilitv.data.settings.SettingsCatalog.SettingsItem
import top.bilitv.ui.settings.ChoiceRow
import top.bilitv.ui.settings.ToggleRow
import top.bilitv.ui.settings.DanmakuSettingItem
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.components.TvCard

/** Uses the main settings rows and persistence; the video keeps playing behind this panel. */
@Composable
internal fun PlaybackSettingsPanel(vm: PlayerViewModel, first: FocusRequester, modifier: Modifier, onLock: () -> Unit, onClose: () -> Unit) {
    val settings = vm.danmakuSettings
    val theme = AppTheme.current
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm) { vm.loadSubtitleMetadata() }
    Column(modifier.widthIn(max = 420.dp).fillMaxHeight(.9f).background(theme.surface).padding(12.dp)) {
        TvCard(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(first),
            focusedScale = 1f, contentDescription = "关闭播放设置，返回播放") {
            Text("关闭 · 返回播放", color = theme.textPrimary, modifier = Modifier.padding(12.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().scrollWithScrollbar(rememberScrollState()).padding(top = 8.dp)) {
        Text("播放设置", color = theme.primary)
        ToggleRow("临时锁定触屏", "防止误触播放、进度或音量；长按画面上的锁解锁，返回键也可解除",
            false, { if (it) onLock() })
        ChoiceRow("默认清晰度", "只在进入视频时生效，不改变当前画质", QualityOptions.ALL,
            QualityOptions.ALL.firstOrNull { it.id == settings.preferredQuality } ?: QualityOptions.AUTO,
            { it.label }, { settings.preferredQuality = it.id; revision++ })
        ChoiceRow("默认音质", "只在进入视频时生效；接口或设备不支持时自动回退", AudioQuality.entries.toList(),
            settings.preferredAudioQuality, { it.label }, { settings.preferredAudioQuality = it; revision++ })
        if (!vm.live) ChoiceRow("播放完成后", "只循环当前视频；试看片段到期停止", PlaybackTuning.EndAction.entries.toList(),
            vm.endAction, { it.label }, { vm.changeEndAction(it); revision++ })
        listOf(SettingsItem.SUBTITLE_ON, SettingsItem.SUBTITLE_LANGUAGE, SettingsItem.SUBTITLE_FONT,
            SettingsItem.SUBTITLE_BACKGROUND,
            SettingsItem.DANMAKU_ON, SettingsItem.DANMAKU_ALPHA, SettingsItem.DANMAKU_SCALE,
            SettingsItem.DANMAKU_AREA, SettingsItem.DANMAKU_SPEED, SettingsItem.DANMAKU_LEVEL,
            SettingsItem.DANMAKU_INTERACTION).forEach { item ->
            DanmakuSettingItem(item, settings, revision, subtitleTracks = vm.subtitleTracks,
                subtitleStatus = if (vm.live) "直播不提供点播字幕" else vm.subtitleStatus) {
                revision++; vm.reloadDanmakuSettings()
            }
        }
        if (settings.advancedMode) ToggleRow("播放诊断信息", "显示分辨率、编码、缓冲和掉帧",
            settings.showPlaybackStats, { settings.showPlaybackStats = it; revision++; vm.reloadDanmakuSettings() })
        ChoiceRow("画面比例", "立即生效，并保存为默认比例", PlaybackTuning.ASPECTS,
            PlaybackTuning.aspectOf(vm.aspectId), { it.label }, {
                settings.aspectMode = it.id; vm.setAspect(it.id); revision++
            })
        }
    }
}
