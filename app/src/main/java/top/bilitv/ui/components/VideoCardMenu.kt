package top.bilitv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import top.bilitv.ui.settings.SettingRow
import top.bilitv.ui.theme.AppTheme

/** Called only for ordinary video/poster cards; management mode keeps its original action. */
@Composable
internal fun rememberVideoCardMenu(title: String, onOpen: () -> Unit, card: FocusRequester): () -> Unit {
    var open by remember { mutableStateOf(false) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val theme = AppTheme.current
    fun close(restore: Boolean = true) {
        open = false
        if (restore && !view.isInTouchMode) scope.launch {
            withFrameNanos { }; runCatching { card.requestFocus() }
        }
    }
    if (open) Dialog(onDismissRequest = { close() }) {
        val first = remember { FocusRequester() }
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).background(theme.surface)
            .onPreviewKeyEvent { e ->
                if (e.key != Key.Menu) false else {
                    if (e.type == KeyEventType.KeyUp) close()
                    true
                }
            }.scrollWithScrollbar(rememberScrollState()).padding(16.dp)) {
            Text(title, color = theme.textPrimary)
            SettingRow("打开视频", null, "打开", Modifier.focusRequester(first)) { close(false); onOpen() }
            SettingRow("关闭菜单", null, "返回") { close() }
        }
        RequestFocusOnAppear(first, true)
    }
    return { open = true }
}
