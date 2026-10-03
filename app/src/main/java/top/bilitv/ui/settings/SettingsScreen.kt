package top.bilitv.ui.settings

import top.bilitv.ui.components.scrollWithScrollbar
import android.content.Context
import top.bilitv.R
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import kotlinx.coroutines.delay
import top.bilitv.BiliTvApp
import top.bilitv.data.settings.SettingsCatalog
import top.bilitv.data.settings.SettingsCatalog.SettingsCategory
import top.bilitv.data.settings.SettingsCatalog.SettingsItem
import top.bilitv.data.settings.ThemeSkin
import top.bilitv.player.DecoderSelector
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.focusRing
import top.bilitv.ui.components.LocalNavigationFocus
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.util.AppLog

internal class SettingsScroll(val state: ScrollState) {
    var viewport by mutableStateOf(Rect.Zero)
}
internal val LocalSettingsScroll = compositionLocalOf<SettingsScroll?> { null }

/** BT 的三栏结构：全局侧栏 / 设置分类 / 当前分类的项目。 */
@Composable
fun SettingsScreen(
    onSkinChange: (ThemeSkin) -> Unit,
    railTabs: List<String> = emptyList(),
    onRailTabsChange: (List<String>) -> Unit = {},
    railFocus: FocusRequester,
    entryFocus: FocusRequester,
) {
    val context = LocalContext.current
    val app = context.applicationContext as BiliTvApp
    val settings = app.settings
    val theme = AppTheme.current
    val navigation = LocalNavigationFocus.current
    var skin by remember { mutableStateOf(settings.themeSkin) }
    var sponsorOn by remember { mutableStateOf(settings.sponsorEnabled) }
    var sponsorCats by remember { mutableStateOf(settings.sponsorCategories) }
    var filterAds by remember { mutableStateOf(settings.filterUiAds) }
    var preferHevc by remember { mutableStateOf(settings.preferHevc) }
    var homeSections by remember { mutableStateOf(settings.homeSections) }
    var railIds by remember { mutableStateOf(railTabs) }
    var quality by remember { mutableStateOf(settings.preferredQuality) }
    var seekSeconds by remember { mutableStateOf(settings.seekSeconds) }
    var autoLowerQuality by remember { mutableStateOf(settings.autoLowerQuality) }
    var detailPage by remember { mutableStateOf(settings.detailPageEnabled) }
    var singleBackExit by remember { mutableStateOf(settings.singleBackExit) }
    var advanced by remember { mutableStateOf(settings.advancedMode) }
    var forceAvc by remember { mutableStateOf(settings.forceAvc) }
    var skipP2p by remember { mutableStateOf(settings.skipP2p) }
    var cdnPref by remember { mutableStateOf(settings.cdnPreference.ifEmpty { CDN_NONE }) }
    var decoder by remember { mutableStateOf(DecoderSelector.normalize(settings.decoderName)) }
    var retryNoP2p by remember { mutableStateOf(settings.autoRetryWithoutP2p) }
    var speedIndex by remember { mutableStateOf(settings.playbackSpeedIndex) }
    var aspectId by remember { mutableStateOf(settings.aspectMode) }
    var barButtons by remember { mutableStateOf(settings.playerButtons) }
    var cacheBytes by remember { mutableStateOf(imageCacheBytes(context)) }

    val appVersion: String = remember {
        runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            val name = info.versionName ?: "?"
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
            "$name ($code)"
        }.getOrElse { context.getString(R.string.version_unknown) }
    }

    var resetRevision by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }

    val categories = SettingsCatalog.visibleCategories(advanced)
    var selectedName by rememberSaveable { mutableStateOf(SettingsCategory.DEFAULT.name) }
    val cat = categories.firstOrNull { it.name == selectedName } ?: SettingsCategory.DEFAULT
    val requesters = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val categoryAnchor = requesters.getValue(cat)
    val firstItem = remember { FocusRequester() }
    val appearanceItems = SettingsCatalog.itemsIn(SettingsCategory.APPEARANCE, true)
    val starts = remember { appearanceItems.associateWith { FocusRequester() } }
    val ends = remember {
        appearanceItems.associateWith { if (it in setOf(SettingsItem.SKIN, SettingsItem.BACKGROUND_COLOR, SettingsItem.APPEARANCE_RESET)) starts.getValue(it) else FocusRequester() }
    }
    var focusOnEntry by remember { mutableStateOf(true) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val categoryWidth = (maxWidth * .25f).coerceIn(112.dp.coerceAtMost(maxWidth), 194.dp.coerceAtMost(maxWidth))
        val contentPadding = if (maxWidth < 600.dp) 8.dp else 24.dp
        CompositionLocalProvider(LocalCategoryAnchor provides categoryAnchor) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(categoryWidth).fillMaxHeight()
                        .scrollWithScrollbar(rememberScrollState()).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    categories.forEachIndexed { index, category ->
                        val label = androidx.compose.ui.res.stringResource(category.labelRes)
                        val self = requesters.getValue(category)
                        var focused by remember { mutableStateOf(false) }
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 50.dp)
                                .focusRequester(self)
                                .then(if (category == cat) Modifier.focusRequester(entryFocus) else Modifier)
                                .onFocusChanged {
                                    focused = it.isFocused
                                    if (it.isFocused) {
                                        if (navigation?.confirmTabs == false) selectedName = category.name
                                        focusOnEntry = false
                                    }
                                }
                                .focusProperties {
                                    up = requesters.getValue(categories.getOrElse(index - 1) { category })
                                    down = requesters.getValue(categories.getOrElse(index + 1) { category })
                                    left = railFocus
                                    right = if (category != cat || category == SettingsCategory.ABOUT) self else firstItem
                                }
                                .focusRing(
                                    contentDescription = label,
                                    restFill = if (category == cat) theme.navSelectedFill else Color.Transparent,
                                    focusedFill = theme.surfaceHigh,
                                    scaleOnFocus = 1f,
                                    onClick = { selectedName = category.name },
                                ).padding(horizontal = 12.dp, vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(label, style = TextStyle(fontSize = AppType.Body1),
                                color = if (focused) theme.textPrimary else
                                    if (category == cat) theme.navSelectedText else theme.textSecondary)
                        }
                    }
                }
                Box(Modifier.fillMaxHeight().width(1.dp).background(theme.divider))
                // 每类只有少量设置；全部组合，避免焦点指向 LazyColumn 尚未创建的项。
                key(cat, resetRevision) {
                    val scrollState = rememberScrollState()
                    val scroll = remember(scrollState) { SettingsScroll(scrollState) }
                    CompositionLocalProvider(LocalSettingsScroll provides scroll) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .onGloballyPositioned { scroll.viewport = it.boundsInRoot() }
                            .scrollWithScrollbar(scrollState)
                            .focusRequester(firstItem)
                            .padding(horizontal = contentPadding, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (cat == SettingsCategory.DANMAKU) DanmakuSettingsPage(settings, Modifier.backToCategories())
                        else SettingsCatalog.itemsIn(cat, advanced).forEach { item ->
                            val index = appearanceItems.indexOf(item)
                            itemBody(
                                item = item,
                                settings = settings,
                                skin = skin,
                                onSkinChange = { skin = it; onSkinChange(it) },
                                sponsorOn = sponsorOn,
                                onSponsorOn = { sponsorOn = it; settings.sponsorEnabled = it },
                                sponsorCats = sponsorCats,
                                onSponsorCats = { sponsorCats = it; settings.sponsorCategories = it },
                                filterAds = filterAds,
                                onFilterAds = { filterAds = it; settings.filterUiAds = it },
                                preferHevc = preferHevc,
                                onPreferHevc = { preferHevc = it; settings.preferHevc = it },
                                quality = quality,
                                onQuality = { quality = it; settings.preferredQuality = it },
                                seekSeconds = seekSeconds,
                                onSeekSeconds = { seekSeconds = it; settings.seekSeconds = it },
                                autoLowerQuality = autoLowerQuality,
                                onAutoLowerQuality = { autoLowerQuality = it; settings.autoLowerQuality = it },
                                detailPage = detailPage,
                                onDetailPage = { detailPage = it; settings.detailPageEnabled = it },
                                singleBackExit = singleBackExit,
                                onSingleBackExit = { singleBackExit = it; settings.singleBackExit = it },
                                advanced = advanced,
                                onAdvanced = { advanced = it; settings.advancedMode = it },
                                forceAvc = forceAvc,
                                onForceAvc = { forceAvc = it; settings.forceAvc = it },
                                skipP2p = skipP2p,
                                onSkipP2p = { skipP2p = it; settings.skipP2p = it },
                                cdnPref = cdnPref,
                                onCdnPref = {
                                    cdnPref = it
                                    settings.cdnPreference = if (it == CDN_NONE) "" else it
                                },
                                decoder = decoder,
                                onDecoder = { decoder = it; settings.decoderName = it },
                                retryNoP2p = retryNoP2p,
                                onRetryNoP2p = { retryNoP2p = it; settings.autoRetryWithoutP2p = it },
                                speedIndex = speedIndex,
                                onSpeedIndex = { speedIndex = it; settings.playbackSpeedIndex = it },
                                aspectId = aspectId,
                                onAspectId = { aspectId = it; settings.aspectMode = it },
                                homeSections = homeSections,
                                onHomeSections = { homeSections = it; settings.homeSections = it },
                                railIds = railIds,
                                onRailIds = { railIds = it; onRailTabsChange(it) },
                                barButtons = barButtons,
                                onBarButtons = { barButtons = it; settings.playerButtons = it },
                                cacheBytes = cacheBytes,
                                onClearCache = {
                                    val r = clearImageCache(context)
                                    cacheBytes = imageCacheBytes(context)
                                    notice = r
                                },
                                onResetPlayback = {
                                    settings.resetPlaybackPage()
                                    quality = settings.preferredQuality; seekSeconds = settings.seekSeconds
                                    autoLowerQuality = settings.autoLowerQuality; preferHevc = settings.preferHevc
                                    detailPage = settings.detailPageEnabled; singleBackExit = settings.singleBackExit
                                    speedIndex = settings.playbackSpeedIndex; aspectId = settings.aspectMode
                                    resetRevision++; notice = context.getString(R.string.settings_playback_reset_done)
                                },
                                onResetAppearance = {
                                    settings.resetAppearancePage()
                                    skin = settings.themeSkin; onSkinChange(skin)
                                    homeSections = settings.homeSections
                                    railIds = settings.navTabs; onRailTabsChange(railIds)
                                    barButtons = settings.playerButtons
                                    resetRevision++; notice = context.getString(R.string.settings_appearance_reset_done)
                                },
                                appVersion = appVersion,
                                firstFocus = starts[item], lastFocus = ends[item],
                                headUp = if (index == 0) categoryAnchor else appearanceItems.getOrNull(index - 1)?.let { ends[it] },
                                tailDown = if (index >= 0) appearanceItems.getOrNull(index + 1)?.let { starts[it] } else null,
                            )
                        }
                    }
                    }
                }
            }
        }
        notice?.let {
            Text(it, color = theme.textPrimary,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    .background(theme.surfaceHigh, RoundedCornerShape(10.dp)).padding(14.dp))
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) { delay(4_000); notice = null }
    }
    RequestFocusOnAppear(categoryAnchor, if (resetRevision > 0) resetRevision else focusOnEntry)
}

@OptIn(ExperimentalCoilApi::class)
private fun imageCacheBytes(context: Context): Long = runCatching {
    val loader = Coil.imageLoader(context)
    (loader.diskCache?.size ?: 0L) + (loader.memoryCache?.size ?: 0).toLong()
}.getOrElse { 0L }

@OptIn(ExperimentalCoilApi::class)
private fun clearImageCache(context: Context): String = runCatching {
    val loader = Coil.imageLoader(context)
    loader.memoryCache?.clear()
    loader.diskCache?.clear()
    context.getString(R.string.settings_cache_cleared)
}.getOrElse {
    AppLog.e("Settings", "清理图片缓存失败", it)
    context.getString(R.string.settings_cache_clear_failed)
}
