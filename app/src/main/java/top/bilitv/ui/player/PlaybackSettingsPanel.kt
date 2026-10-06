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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.bilitv.R
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
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm) { vm.loadSubtitleMetadata() }
    Column(modifier.widthIn(max = 420.dp).fillMaxHeight(.9f).background(theme.surface).padding(12.dp)) {
        TvCard(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(first),
            focusedScale = 1f, contentDescription = stringResource(R.string.player_close_settings_description)) {
            Text(stringResource(R.string.player_close_back), color = theme.textPrimary, modifier = Modifier.padding(12.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().scrollWithScrollbar(rememberScrollState()).padding(top = 8.dp)) {
        Text(stringResource(R.string.setting_playback), color = theme.primary)
        ToggleRow(stringResource(R.string.player_lock_touch), stringResource(R.string.player_lock_touch_hint),
            false, { if (it) onLock() })
        ChoiceRow(stringResource(R.string.settings_default_quality), stringResource(R.string.player_default_quality_hint), QualityOptions.ALL,
            QualityOptions.ALL.firstOrNull { it.id == settings.preferredQuality } ?: QualityOptions.AUTO,
            { qualityOptionLabelRes(it.id)?.let { res -> context.getString(res) } ?: it.label }, { settings.preferredQuality = it.id; revision++ })
        ChoiceRow(stringResource(R.string.settings_audio), stringResource(R.string.player_default_audio_hint), AudioQuality.entries.toList(),
            settings.preferredAudioQuality, { context.getString(it.labelRes()) }, { settings.preferredAudioQuality = it; revision++ })
        if (!vm.live) ChoiceRow(stringResource(R.string.settings_end_action), stringResource(R.string.player_end_action_hint), PlaybackTuning.EndAction.entries.toList(),
            vm.endAction, { context.getString(it.labelRes()) }, { vm.changeEndAction(it); revision++ })
        listOf(SettingsItem.SUBTITLE_ON, SettingsItem.SUBTITLE_LANGUAGE, SettingsItem.SUBTITLE_FONT,
            SettingsItem.SUBTITLE_BACKGROUND,
            SettingsItem.DANMAKU_ON, SettingsItem.DANMAKU_ALPHA, SettingsItem.DANMAKU_SCALE,
            SettingsItem.DANMAKU_AREA, SettingsItem.DANMAKU_SPEED, SettingsItem.DANMAKU_LEVEL,
            SettingsItem.DANMAKU_INTERACTION).forEach { item ->
            DanmakuSettingItem(item, settings, revision, subtitleTracks = vm.subtitleTracks,
                subtitleStatus = if (vm.live) stringResource(R.string.player_live_no_subtitle) else vm.subtitleStatus) {
                revision++; vm.reloadDanmakuSettings()
            }
        }
        if (settings.advancedMode) ToggleRow(stringResource(R.string.settings_stats), stringResource(R.string.player_stats_hint),
            settings.showPlaybackStats, { settings.showPlaybackStats = it; revision++; vm.reloadDanmakuSettings() })
        ChoiceRow(stringResource(R.string.settings_aspect), stringResource(R.string.player_aspect_hint), PlaybackTuning.ASPECTS,
            PlaybackTuning.aspectOf(vm.aspectId), { context.getString(aspectLabelRes(it.id)) }, {
                settings.aspectMode = it.id; vm.setAspect(it.id); revision++
            })
        }
    }
}
