package top.bilitv.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import top.bilitv.data.settings.SettingsStore
import top.bilitv.data.settings.StartupFocus
import top.bilitv.data.settings.CardSize
import top.bilitv.ui.NavTab
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.R

/** 与外壳共用原生偏好，开关立即生效；启动选项下次冷启动生效。 */
@Composable
internal fun InterfaceGeneralSettings(settings: SettingsStore, first: FocusRequester?, last: FocusRequester?,
    headUp: FocusRequester?, tailDown: FocusRequester?) {
    var animations by remember { mutableStateOf(settings.interfaceAnimations) }
    var autoRefresh by remember { mutableStateOf(settings.autoRefresh) }
    var rightToCards by remember { mutableStateOf(settings.rightToCards) }
    var cardMenu by remember { mutableStateOf(settings.cardMenuOnMenuKey) }
    var lowMemory by remember { mutableStateOf(settings.lowMemoryMode) }
    val pages = NavTab.parse(settings.navTabs).map { it.name } + "FAV"
    var page by remember { mutableStateOf(settings.startupPage.takeIf { it in pages } ?: "HOME") }
    var focus by remember { mutableStateOf(settings.startupFocus) }
    val back = Modifier.backToCategories()
    val context = LocalContext.current
    Text(stringResource(R.string.interface_general), color = AppTheme.current.primary, style = TextStyle(fontSize = AppType.H3))
    ToggleRow(stringResource(R.string.interface_low_memory), stringResource(R.string.interface_low_memory_hint),
        lowMemory, { lowMemory = it; settings.lowMemoryMode = it },
        back.then(if (first != null) Modifier.focusRequester(first) else Modifier)
            .focusProperties { if (headUp != null) up = headUp })
    ToggleRow(stringResource(R.string.interface_auto_refresh), stringResource(R.string.interface_auto_refresh_hint),
        autoRefresh, { autoRefresh = it; settings.autoRefresh = it }, back)
    ToggleRow(stringResource(R.string.interface_right_to_cards), stringResource(R.string.interface_right_to_cards_hint),
        rightToCards, { rightToCards = it; settings.rightToCards = it }, back)
    ToggleRow(stringResource(R.string.interface_animations), stringResource(R.string.interface_animations_hint), animations,
        { animations = it; settings.interfaceAnimations = it }, back)
    ChoiceRow(stringResource(R.string.interface_menu_key), stringResource(R.string.interface_menu_key_hint),
        listOf(true, false), cardMenu, { context.getString(if (it) R.string.interface_menu_card else R.string.interface_menu_refresh) },
        { cardMenu = it; settings.cardMenuOnMenuKey = it }, back)
    ChoiceRow(stringResource(R.string.interface_startup_page), stringResource(R.string.interface_startup_page_hint),
        pages, page, { id -> context.getString(if (id == "FAV") R.string.nav_favorites else NavTab.entries.first { it.name == id }.labelRes) },
        { page = it; settings.startupPage = it }, back)
    ChoiceRow(stringResource(R.string.interface_startup_focus), stringResource(R.string.interface_startup_focus_hint),
        StartupFocus.entries.toList(), focus, { context.getString(it.labelRes) },
        { focus = it; settings.startupFocus = it },
        back.then(if (last != null) Modifier.focusRequester(last) else Modifier)
            .focusProperties { if (tailDown != null) down = tailDown })
}

@Composable
internal fun CardLayoutSettings(settings: SettingsStore, first: FocusRequester?, last: FocusRequester?,
    headUp: FocusRequester?, tailDown: FocusRequester?) {
    var size by remember { mutableStateOf(settings.cardSize) }
    var videos by remember { mutableStateOf(settings.cardColumns) }
    var collections by remember { mutableStateOf(settings.collectionColumns) }
    val back = Modifier.backToCategories()
    val context = LocalContext.current
    Text(stringResource(R.string.interface_card_layout), color = AppTheme.current.primary, style = TextStyle(fontSize = AppType.H3))
    ChoiceRow(stringResource(R.string.interface_card_size), stringResource(R.string.interface_card_size_hint), CardSize.entries.toList(),
        size, { context.getString(it.labelRes) }, { size = it; settings.cardSize = it },
        back.then(if (first != null) Modifier.focusRequester(first) else Modifier)
            .focusProperties { if (headUp != null) up = headUp })
    ChoiceRow(stringResource(R.string.interface_video_columns), stringResource(R.string.interface_video_columns_hint),
        CardSize.COLUMNS, videos, { if (it == 0) context.getString(R.string.value_auto) else context.getString(R.string.value_columns, it) },
        { videos = it; settings.cardColumns = it }, back)
    ChoiceRow(stringResource(R.string.interface_poster_columns), stringResource(R.string.interface_poster_columns_hint),
        CardSize.COLUMNS, collections, { if (it == 0) context.getString(R.string.value_auto) else context.getString(R.string.value_columns, it) },
        { collections = it; settings.collectionColumns = it },
        back.then(if (last != null) Modifier.focusRequester(last) else Modifier)
            .focusProperties { if (tailDown != null) down = tailDown })
}
