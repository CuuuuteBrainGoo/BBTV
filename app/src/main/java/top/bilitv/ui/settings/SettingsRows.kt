package top.bilitv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.components.focusRing
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType

/** BT 项目行：左标题及说明、右侧值，聚焦时整行填主题色。 */
@Composable
internal fun SettingRow(
    title: String,
    desc: String?,
    value: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val theme = AppTheme.current
    var focused by remember { mutableStateOf(false) }
    val textColor = if (focused) theme.onPrimary else theme.textPrimary
    Row(
        modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .focusRing(
                contentDescription = "$title，$value",
                focusedFill = theme.primary,
                scaleOnFocus = 1f,
                onClick = onClick,
            ).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = textColor, style = TextStyle(fontSize = AppType.Body2))
            if (desc != null) Text(desc,
                color = if (focused) theme.onPrimary else theme.textSecondary,
                style = TextStyle(fontSize = AppType.Small),
                modifier = Modifier.padding(top = 3.dp))
        }
        if (trailing != null) trailing()
        else Text(value, color = if (focused) theme.onPrimary else theme.primary,
            style = TextStyle(fontSize = AppType.Meta), modifier = Modifier.padding(start = 20.dp))
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    desc: String?,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AppTheme.current
    SettingRow(title, desc, if (on) "开" else "关", modifier.semantics {
        role = Role.Switch; stateDescription = if (on) "开" else "关"
    }, trailing = {
        Switch(checked = on, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedTrackColor = theme.primary, checkedThumbColor = theme.onPrimary,
            checkedBorderColor = theme.onPrimary,
            uncheckedTrackColor = theme.textTertiary, uncheckedThumbColor = theme.surface,
            uncheckedBorderColor = theme.textTertiary))
    }) { onToggle(!on) }
}

/** 数值行保留明确的减/加按钮；左边第一个按钮按左回当前分类。 */
@Composable
internal fun StepperRow(
    title: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AppTheme.current
    val minusFocus = remember { FocusRequester() }
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = theme.textPrimary, style = TextStyle(fontSize = AppType.Body2),
            modifier = Modifier.weight(1f))
        TvCard(onClick = onMinus, modifier = modifier.focusRequester(minusFocus),
            focusedScale = 1f, contentDescription = "$title 减小") {
            Text("−", color = theme.textPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
        Text(value, color = theme.primary, modifier = Modifier.padding(horizontal = 20.dp))
        TvCard(onClick = onPlus, modifier = Modifier.focusProperties { left = minusFocus },
            focusedScale = 1f, contentDescription = "$title 增大") {
            Text("+", color = theme.textPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
    }
}

/** 清理动作仍只让右侧按钮可点，保留原来的操作范围。 */
@Composable
internal fun ActionRow(
    title: String,
    desc: String?,
    value: String = "",
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AppTheme.current
    Row(modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = theme.textPrimary, style = TextStyle(fontSize = AppType.Body2))
            if (desc != null) Text(desc, color = theme.textSecondary, style = TextStyle(fontSize = AppType.Small))
        }
        Text(value, color = theme.primary, modifier = Modifier.padding(end = 12.dp))
        TvCard(onClick = onAction, focusedScale = 1f, contentDescription = "$actionLabel$title") {
            Text(actionLabel, color = theme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
    }
}

/** 选项在原生弹窗里逐行选，避免窄屏上横排选项挤出项目区。 */
@Composable
internal fun <T> ChoiceRow(
    title: String,
    desc: String?,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AppTheme.current
    var open by remember { mutableStateOf(false) }
    val selectedFocus = remember { FocusRequester() }
    SettingRow(title, desc, labelOf(selected), modifier) { open = true }
    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp)
                .background(theme.surface, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(title, color = theme.textPrimary, style = TextStyle(fontSize = AppType.H3),
                    modifier = Modifier.padding(bottom = 10.dp))
                (if (selected in options) options else options + selected).forEach { option ->
                    SettingRow(labelOf(option), null, if (option == selected) "当前" else "",
                        modifier = if (option == selected) Modifier.focusRequester(selectedFocus) else Modifier,
                    ) { onSelect(option); open = false }
                }
            }
            RequestFocusOnAppear(selectedFocus, true)
        }
    }
}

@Composable
internal fun InfoRow(title: String, desc: String?, value: String) {
    val theme = AppTheme.current
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Text(title, color = theme.textPrimary, style = TextStyle(fontSize = AppType.H3))
        Text(value, color = theme.primary, modifier = Modifier.padding(vertical = 8.dp))
        if (desc != null) Text(desc, color = theme.textSecondary, style = TextStyle(fontSize = AppType.Small))
    }
}
