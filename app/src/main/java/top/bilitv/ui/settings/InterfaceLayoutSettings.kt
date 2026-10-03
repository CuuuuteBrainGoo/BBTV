package top.bilitv.ui.settings

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import top.bilitv.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import top.bilitv.data.settings.InterfaceTuning
import top.bilitv.data.settings.SettingsStore

@Composable
internal fun InterfaceLayoutSettings(settings: SettingsStore, first: FocusRequester?, last: FocusRequester?,
    headUp: FocusRequester?, tailDown: FocusRequester?) {
    val context = LocalContext.current
    var font by remember { mutableStateOf(settings.interfaceFontScale) }
    var scrollbars by remember { mutableStateOf(settings.showScrollbars) }
    var sidebar by remember { mutableStateOf(settings.playerSidebarWidth) }
    var buttons by remember { mutableStateOf(settings.playerButtonScale) }
    val back = Modifier.backToCategories()
    ChoiceRow(stringResource(R.string.settings_interface_font), stringResource(R.string.settings_interface_font_hint),
        InterfaceTuning.FONT, font, { "${(it * 100).toInt()}%" }, { font = it; settings.interfaceFontScale = it },
        back.then(if (first != null) Modifier.focusRequester(first) else Modifier).focusProperties { if (headUp != null) up = headUp })
    ToggleRow(stringResource(R.string.settings_scrollbars), stringResource(R.string.settings_scrollbars_hint), scrollbars,
        { scrollbars = it; settings.showScrollbars = it }, back)
    ChoiceRow(stringResource(R.string.settings_sidebar_width), stringResource(R.string.settings_sidebar_width_hint), InterfaceTuning.SIDEBAR, sidebar,
        { when (it) { 0f -> context.getString(R.string.value_auto); .5f -> "1/2"; else -> if (it < .5f) "1/3" else "2/3" } },
        { sidebar = it; settings.playerSidebarWidth = it }, back)
    ChoiceRow(stringResource(R.string.settings_control_size), stringResource(R.string.settings_control_size_hint), InterfaceTuning.BUTTONS, buttons,
        { if (it < 1) context.getString(R.string.value_small) else if (it > 1) context.getString(R.string.value_large) else context.getString(R.string.value_medium) }, { buttons = it; settings.playerButtonScale = it },
        back.then(if (last != null) Modifier.focusRequester(last) else Modifier).focusProperties { if (tailDown != null) down = tailDown })
}

@Composable
internal fun PlaybackDisplaySettings(settings: SettingsStore, modifier: Modifier) {
    var rememberUp by remember { mutableStateOf(settings.rememberUpSpeed) }
    var progress by remember { mutableStateOf(settings.showPlayerProgress) }
    var pause by remember { mutableStateOf(settings.showPauseIcon) }
    var hide by remember { mutableStateOf(settings.hideControlsOnStart) }
    var time by remember { mutableStateOf(settings.showProgressTime) }
    ToggleRow(stringResource(R.string.settings_up_speed), stringResource(R.string.settings_up_speed_hint),
        rememberUp, { rememberUp = it; settings.rememberUpSpeed = it }, modifier)
    ToggleRow(stringResource(R.string.settings_progress), stringResource(R.string.settings_progress_hint), progress,
        { progress = it; settings.showPlayerProgress = it }, modifier)
    ToggleRow(stringResource(R.string.settings_pause_icon), null, pause, { pause = it; settings.showPauseIcon = it }, modifier)
    ToggleRow(stringResource(R.string.settings_hide_controls), stringResource(R.string.settings_hide_controls_hint), hide,
        { hide = it; settings.hideControlsOnStart = it }, modifier)
    ToggleRow(stringResource(R.string.settings_progress_time), stringResource(R.string.settings_progress_time_hint), time,
        { time = it; settings.showProgressTime = it }, modifier)
}

@Composable
internal fun TouchGestureSettings(settings: SettingsStore, modifier: Modifier) {
    var seek by remember { mutableStateOf(settings.touchSeek) }
    var brightness by remember { mutableStateOf(settings.touchBrightness) }
    var volume by remember { mutableStateOf(settings.touchVolume) }
    var boost by remember { mutableStateOf(settings.touchBoost) }
    var doubleTap by remember { mutableStateOf(settings.touchDoubleTap) }
    ToggleRow(stringResource(R.string.settings_touch_seek), stringResource(R.string.settings_touch_seek_hint), seek,
        { seek = it; settings.touchSeek = it }, modifier)
    ToggleRow(stringResource(R.string.settings_touch_brightness), null, brightness, { brightness = it; settings.touchBrightness = it }, modifier)
    ToggleRow(stringResource(R.string.settings_touch_volume), null, volume, { volume = it; settings.touchVolume = it }, modifier)
    ToggleRow(stringResource(R.string.settings_touch_boost), stringResource(R.string.settings_touch_boost_hint), boost, { boost = it; settings.touchBoost = it }, modifier)
    ToggleRow(stringResource(R.string.settings_touch_double), stringResource(R.string.settings_touch_double_hint), doubleTap, { doubleTap = it; settings.touchDoubleTap = it }, modifier)
}
