package top.bilitv.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import top.bilitv.data.settings.SettingsStore
import top.bilitv.data.settings.StartupFocus
import top.bilitv.ui.NavTab
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType

/** 与外壳共用原生偏好，开关立即生效；启动选项下次冷启动生效。 */
@Composable
internal fun InterfaceGeneralSettings(settings: SettingsStore, first: FocusRequester?, last: FocusRequester?,
    headUp: FocusRequester?, tailDown: FocusRequester?) {
    var lowMemory by remember { mutableStateOf(settings.lowMemoryMode) }
    val pages = NavTab.parse(settings.navTabs).map { it.name } + "FAV"
    var page by remember { mutableStateOf(settings.startupPage.takeIf { it in pages } ?: "HOME") }
    var focus by remember { mutableStateOf(settings.startupFocus) }
    var clock by remember { mutableStateOf(settings.showClock) }
    val back = Modifier.backToCategories()
    Text("通用", color = AppTheme.current.primary, style = TextStyle(fontSize = AppType.H3))
    ToggleRow("省内存模式", "开启：按 OK 才切换标签；关闭：焦点移动即切换。焦点都保留在标签上。",
        lowMemory, { lowMemory = it; settings.lowMemoryMode = it },
        back.then(if (first != null) Modifier.focusRequester(first) else Modifier)
            .focusProperties { if (headUp != null) up = headUp })
    ChoiceRow("默认启动页面", "下次启动生效；页面被隐藏时回到首页",
        pages, page, { id -> if (id == "FAV") "收藏" else NavTab.entries.first { it.name == id }.label },
        { page = it; settings.startupPage = it }, back)
    ChoiceRow("启动焦点位置", "没有分区标签时回到侧栏；收藏是独立页面，使用其内容焦点",
        StartupFocus.entries.toList(), focus, { it.label },
        { focus = it; settings.startupFocus = it }, back)
    ToggleRow("显示时间", "一级页面右上角显示；播放页不显示", clock,
        { clock = it; settings.showClock = it },
        back.then(if (last != null) Modifier.focusRequester(last) else Modifier)
            .focusProperties { if (tailDown != null) down = tailDown })
}
