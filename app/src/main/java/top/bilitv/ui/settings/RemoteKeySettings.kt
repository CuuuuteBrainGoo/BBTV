package top.bilitv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import top.bilitv.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.bilitv.data.settings.*
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.scrollWithScrollbar
import top.bilitv.ui.theme.AppTheme

@Composable
internal fun RemoteKeySettings(settings: SettingsStore, modifier: Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf(false) }
    var addedKey by remember { mutableStateOf<Int?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val theme = AppTheme.current
    SettingRow(stringResource(R.string.settings_remote), stringResource(R.string.settings_remote_hint), stringResource(R.string.action_configure), modifier) { open = true }
    if (open) Dialog(onDismissRequest = { open = false }) {
        val first = remember { FocusRequester() }
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).background(theme.surface)
            .scrollWithScrollbar(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.settings_remote), color = theme.primary)
            Text(stringResource(R.string.settings_remote_hold_hint), color = theme.textSecondary)
            val keys = remember(revision, addedKey) { (PlayerKeyBindings.BUILT_IN + settings.configuredPlayerKeys + listOfNotNull(addedKey)).distinct() }
            keys.forEachIndexed { i, code ->
                if (code == 19 || code == 20) ToggleRow(stringResource(R.string.settings_remote_master, context.keyLabel(code)), stringResource(R.string.settings_remote_disabled_hint),
                    if (code == 19) settings.playerUpEnabled else settings.playerDownEnabled, {
                        if (code == 19) settings.playerUpEnabled = it else settings.playerDownEnabled = it
                        revision++
                    })
                val fallback = when (code) {
                    23 -> RemoteAction.PLAY; 82 -> RemoteAction.CONTROLS
                    19 -> PlayerKeyBindings.side(if (settings.playerUpEnabled) settings.playerUpAction else null)
                    20 -> PlayerKeyBindings.side(if (settings.playerDownEnabled) settings.playerDownAction else null)
                    else -> RemoteAction.NONE
                }
                val short = settings.playerKeyAction(code, false) ?: fallback
                if (PlayerKeyBindings.editable(code, false)) ChoiceRow(stringResource(R.string.settings_remote_short, context.keyLabel(code)), null,
                    PlayerKeyBindings.SHORT_ACTIONS, short, { context.actionLabel(it) }, {
                        settings.setPlayerKeyAction(code, false, it); revision++
                    }, if (i == 0) Modifier.focusRequester(first) else Modifier)
                else SettingRow(stringResource(R.string.settings_remote_short, context.keyLabel(code)), stringResource(R.string.settings_remote_fixed), context.actionLabel(short),
                    if (i == 0) Modifier.focusRequester(first) else Modifier) {}
                ChoiceRow(stringResource(R.string.settings_remote_long, context.keyLabel(code)), null, RemoteAction.entries.toList(),
                    settings.playerKeyAction(code, true) ?: RemoteAction.NONE, { context.actionLabel(it) }, {
                        settings.setPlayerKeyAction(code, true, it); revision++
                    })
                if (code !in PlayerKeyBindings.BUILT_IN) SettingRow(stringResource(R.string.settings_remote_delete, context.keyLabel(code)), null, stringResource(R.string.action_delete)) {
                    settings.setPlayerKeyAction(code, false, null); settings.setPlayerKeyAction(code, true, null)
                    if (addedKey == code) addedKey = null
                    revision++
                }
            }
            SettingRow(stringResource(R.string.settings_remote_left_right), stringResource(R.string.settings_remote_seek_hint), stringResource(R.string.settings_remote_seek_fixed)) {}
            SettingRow(stringResource(R.string.settings_remote_add), stringResource(R.string.settings_remote_add_hint), stringResource(R.string.action_capture)) { capture = true }
            SettingRow(stringResource(R.string.settings_remote_reset), stringResource(R.string.settings_remote_reset_hint), stringResource(R.string.action_reset)) {
                settings.configuredPlayerKeys.forEach { code ->
                    if (PlayerKeyBindings.editable(code, false)) settings.setPlayerKeyAction(code, false, null)
                    settings.setPlayerKeyAction(code, true, null)
                }
                addedKey = null; revision++
            }
            SettingRow(stringResource(R.string.action_done), null, stringResource(R.string.action_close)) { open = false }
        }
        RequestFocusOnAppear(first, true)
    }
    if (capture) Dialog(onDismissRequest = { capture = false }) {
        var message by remember { mutableStateOf(context.getString(R.string.settings_remote_capture_hint)) }
        val focus = remember { FocusRequester() }
        Column(Modifier.fillMaxWidth().background(theme.surface).padding(20.dp).onPreviewKeyEvent { e ->
            val code = PlayerKeyBindings.canonical(e.nativeKeyEvent.keyCode)
            if (e.key == Key.Back) false
            else {
                if (e.type == KeyEventType.KeyUp) {
                    if (PlayerKeyBindings.editable(code, false) && code !in PlayerKeyBindings.BUILT_IN) {
                        addedKey = code; revision++; capture = false
                    } else message = context.getString(R.string.settings_remote_capture_invalid)
                }
                true
            }
        }) {
            Text(message, color = theme.textPrimary)
            SettingRow(stringResource(R.string.settings_remote_cancel_capture), null, stringResource(R.string.action_cancel), Modifier.focusRequester(focus)) { capture = false }
        }
        RequestFocusOnAppear(focus, true)
    }
}

private fun Context.keyLabel(code: Int): String = when (PlayerKeyBindings.canonical(code)) {
    23 -> getString(R.string.key_ok)
    19 -> getString(R.string.key_up)
    20 -> getString(R.string.key_down)
    21 -> getString(R.string.key_left)
    22 -> getString(R.string.key_right)
    82 -> getString(R.string.key_menu)
    else -> getString(R.string.key_number, code)
}

private fun Context.actionLabel(action: RemoteAction): String = getString(when (action) {
    RemoteAction.NONE -> R.string.remote_none
    RemoteAction.PLAY -> R.string.remote_play
    RemoteAction.CONTROLS -> R.string.remote_controls
    RemoteAction.SETTINGS -> R.string.remote_settings
    RemoteAction.CATALOGUE -> R.string.remote_catalogue
    RemoteAction.RECOMMEND -> R.string.remote_recommend
    RemoteAction.UP_LIST -> R.string.remote_up_list
    RemoteAction.DANMAKU -> R.string.remote_danmaku
    RemoteAction.SUBTITLE -> R.string.remote_subtitle
    RemoteAction.SPEED -> R.string.remote_speed
    RemoteAction.QUALITY -> R.string.remote_quality
    RemoteAction.NEXT -> R.string.remote_next
    RemoteAction.LIKE -> R.string.remote_like
    RemoteAction.COIN -> R.string.remote_coin
    RemoteAction.FAVORITE -> R.string.remote_favorite
    RemoteAction.TRIPLE -> R.string.remote_triple
    RemoteAction.OPEN_UP -> R.string.remote_open_up
    RemoteAction.BOOST -> R.string.remote_boost
    RemoteAction.PREVIOUS -> R.string.remote_previous
    RemoteAction.REFRESH -> R.string.remote_refresh
    RemoteAction.COMMENTS -> R.string.remote_comments
})
