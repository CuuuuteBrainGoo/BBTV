package top.bilitv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.bilitv.R
import top.bilitv.data.settings.PlaybackTuning
import top.bilitv.data.settings.QualityOptions
import top.bilitv.data.settings.SettingsCatalog.SettingsItem
import top.bilitv.data.settings.ThemeSkin
import top.bilitv.data.sponsor.SponsorCategory
import top.bilitv.player.DecoderSelector
import top.bilitv.ui.components.formatBytes
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType

@Composable
fun itemBody(
    item: SettingsItem,
    settings: top.bilitv.data.settings.SettingsStore,
    // ------------------------------------------------------------ 外观
    skin: ThemeSkin,
    onSkinChange: (ThemeSkin) -> Unit,
    homeSections: List<String>,
    onHomeSections: (List<String>) -> Unit,
    railIds: List<String>,
    onRailIds: (List<String>) -> Unit,
    barButtons: List<String>,
    onBarButtons: (List<String>) -> Unit,
    // ------------------------------------------------------------ 广告
    sponsorOn: Boolean,
    onSponsorOn: (Boolean) -> Unit,
    sponsorCats: Set<SponsorCategory>,
    onSponsorCats: (Set<SponsorCategory>) -> Unit,
    filterAds: Boolean,
    onFilterAds: (Boolean) -> Unit,
    // ------------------------------------------------------------ 播放
    quality: Int,
    onQuality: (Int) -> Unit,
    seekSeconds: Int,
    onSeekSeconds: (Int) -> Unit,
    autoLowerQuality: Boolean,
    onAutoLowerQuality: (Boolean) -> Unit,
    preferHevc: Boolean,
    onPreferHevc: (Boolean) -> Unit,
    detailPage: Boolean,
    onDetailPage: (Boolean) -> Unit,
    singleBackExit: Boolean,
    onSingleBackExit: (Boolean) -> Unit,
    // ------------------------------------------------------------ 高级
    advanced: Boolean,
    onAdvanced: (Boolean) -> Unit,
    // ------------------------------------------------------------ 调优 · 解码 / 线路 / 容错
    forceAvc: Boolean,
    onForceAvc: (Boolean) -> Unit,
    skipP2p: Boolean,
    onSkipP2p: (Boolean) -> Unit,
    cdnPref: String,
    onCdnPref: (String) -> Unit,
    decoder: String,
    onDecoder: (String) -> Unit,
    retryNoP2p: Boolean,
    onRetryNoP2p: (Boolean) -> Unit,
    // ------------------------------------------------------------ 调优 · 播放默认值
    speedIndex: Int,
    onSpeedIndex: (Int) -> Unit,
    aspectId: String,
    onAspectId: (String) -> Unit,
    // ------------------------------------------------------------ 存储
    cacheBytes: Long,
    onClearCache: () -> Unit,
    // ------------------------------------------------------------ 关于

    appVersion: String,
    onResetAppearance: () -> Unit,
    onResetPlayback: () -> Unit,
    firstFocus: FocusRequester? = null,
    lastFocus: FocusRequester? = null,
    headUp: FocusRequester? = null,
    tailDown: FocusRequester? = null,
) {
    // 项目区所有行共用的修饰：左键回分类列（锚由 SettingsScreen 通过 CompositionLocal 提供）
    val context = LocalContext.current
    val backLeft = Modifier.backToCategories()

    when (item) {

        // ============================================================ 外观

        SettingsItem.INTERFACE_GENERAL -> InterfaceGeneralSettings(settings,
            firstFocus, lastFocus, headUp, tailDown)

        SettingsItem.CARD_LAYOUT -> CardLayoutSettings(settings,
            firstFocus, lastFocus, headUp, tailDown)

        SettingsItem.INTERFACE_LAYOUT -> InterfaceLayoutSettings(settings, firstFocus, lastFocus, headUp, tailDown)
        SettingsItem.RECOMMEND_SOURCE -> {
            var source by remember { mutableStateOf(settings.recommendSource) }
            ChoiceRow(stringResource(R.string.settings_recommend_source), stringResource(R.string.settings_recommend_source_hint),
                top.bilitv.data.settings.RecommendSource.entries.toList(), source, { context.getString(when (it) { top.bilitv.data.settings.RecommendSource.WEB -> R.string.source_web; top.bilitv.data.settings.RecommendSource.APP -> R.string.source_app_http }) },
                { source = it; settings.recommendSource = it }, backLeft)
        }
        SettingsItem.RECOMMEND_PERSONALIZED -> {
            var enabled by remember { mutableStateOf(settings.personalizedRecommendations) }
            ToggleRow(stringResource(R.string.settings_personalized), stringResource(R.string.settings_personalized_hint),
                enabled, { enabled = it; settings.personalizedRecommendations = it }, backLeft)
        }
        SettingsItem.VIDEO_API_SOURCE -> {
            var source by remember { mutableStateOf(settings.videoApiSource) }
            ChoiceRow(stringResource(R.string.settings_video_api), stringResource(R.string.settings_video_api_hint),
                top.bilitv.data.settings.VideoApiSource.entries.toList(), source, { it.label },
                { source = it; settings.videoApiSource = it }, backLeft)
        }
        SettingsItem.RECOMMEND_BACKTRACK -> {
            var enabled by remember { mutableStateOf(settings.showRecommendBacktrack) }
            ToggleRow(stringResource(R.string.settings_backtrack), stringResource(R.string.settings_backtrack_hint),
                enabled, { enabled = it; settings.showRecommendBacktrack = it }, backLeft)
        }
        SettingsItem.DETAIL_LAYOUT -> {
            var ids by remember { mutableStateOf(settings.detailSections) }
            var meta by remember { mutableStateOf(settings.detailShowMeta) }
            ToggleRow(stringResource(R.string.settings_detail_meta), stringResource(R.string.settings_detail_meta_hint), meta,
                { meta = it; settings.detailShowMeta = it }, backLeft)
            OrderPicker(all = top.bilitv.data.settings.DetailLayout.ALL.map {
                PickItem(it, context.getString(when (it) { "HERO" -> R.string.detail_cover_fixed; "PARTS" -> R.string.detail_parts; else -> R.string.detail_description }), pinned = it == "HERO", locked = it == "HERO")
            }, enabledIds = ids, onChange = { ids = top.bilitv.data.settings.DetailLayout.sections(it); settings.detailSections = ids },
                firstFocus = firstFocus, lastFocus = lastFocus, headUp = headUp, tailDown = tailDown)
        }
        SettingsItem.TOUCH_GESTURES -> TouchGestureSettings(settings, backLeft)
        SettingsItem.REMOTE_KEYS -> RemoteKeySettings(settings, backLeft)
        SettingsItem.PLAYBACK_RESET -> SettingRow(stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_playback_hint), stringResource(R.string.action_reset), backLeft) { onResetPlayback() }
        SettingsItem.PLAYBACK_DISPLAY -> PlaybackDisplaySettings(settings, backLeft)
        SettingsItem.APPEARANCE_RESET -> SettingRow(stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_appearance_hint), stringResource(R.string.action_reset),
            backLeft.then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                .then(if (lastFocus != null) Modifier.focusRequester(lastFocus) else Modifier)) { onResetAppearance() }

        SettingsItem.BACKGROUND_COLOR -> {
            var style by remember { mutableStateOf(settings.backgroundStyle) }
            ChoiceRow(stringResource(R.string.settings_background), stringResource(R.string.settings_background_hint),
                top.bilitv.data.settings.BackgroundStyle.entries.toList(), style, { context.getString(when (it) { top.bilitv.data.settings.BackgroundStyle.DEFAULT -> R.string.background_default; top.bilitv.data.settings.BackgroundStyle.BLACK -> R.string.background_black; top.bilitv.data.settings.BackgroundStyle.GRAY -> R.string.background_gray; top.bilitv.data.settings.BackgroundStyle.BLUE -> R.string.background_blue; top.bilitv.data.settings.BackgroundStyle.WARM -> R.string.background_warm }) },
                { style = it; settings.backgroundStyle = it }, backLeft
                    .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                    .focusProperties { if (headUp != null) up = headUp; if (tailDown != null) down = tailDown })
        }
        SettingsItem.SKIN -> ChoiceRow(
            title = stringResource(R.string.settings_skin),
            desc = stringResource(R.string.settings_skin_hint),
            options = ThemeSkin.entries.toList(),
            selected = skin,
            labelOf = { context.getString(when (it) { ThemeSkin.CINEMA -> R.string.skin_cinema; ThemeSkin.CLASSIC -> R.string.skin_classic; ThemeSkin.PORNHUB -> R.string.skin_pornhub; ThemeSkin.WECHAT -> R.string.skin_wechat; ThemeSkin.ALIPAY -> R.string.skin_alipay }) },
            // 只更新本地 state（让按钮立刻变成选中态）+ 上报。
            // 落盘由 `MainActivity` 做 —— 皮肤状态的拥有者负责持久化，
            // 这里再存一次只是重复，而且以后多一个换肤入口就要多改一处。
            onSelect = onSkinChange,
            modifier = backLeft
                .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                .focusProperties {
                    if (headUp != null) up = headUp
                    if (tailDown != null) down = tailDown
                },
        )

        SettingsItem.HOME_SECTIONS -> PickerBlock(
            title = stringResource(R.string.settings_home_sections),
            note = "",
            // 后面还有「侧栏栏目」和「控制栏按钮」两个选择器 → pinTail = false（留在它们之间能往下走）
        ) { HomeSectionPicker(enabledIds = homeSections, onChange = onHomeSections,
            firstFocus = firstFocus, lastFocus = lastFocus, headUp = headUp, tailDown = tailDown) }

        SettingsItem.RAIL_TABS -> PickerBlock(
            // ★ 2026-09-30 少爷第 8 条：**侧栏也能配**。
            //   和「首页分区」是同一个组件，所以手感必然一致。
            //   唯一差别是「首页」「设置」带固定标记 —— 理由见 `NavTabPicker`。
            title = stringResource(R.string.settings_rail_tabs),

            note = stringResource(R.string.settings_rail_tabs_hint),
            // 后面还有「控制栏按钮」，末尾允许向下离开
        ) { NavTabPicker(enabledIds = railIds, onChange = onRailIds,
            firstFocus = firstFocus, lastFocus = lastFocus, headUp = headUp, tailDown = tailDown) }

        SettingsItem.PLAYER_BUTTONS -> PickerBlock(
            // ★ 2026-09-30 少爷第 8 条的第三处：**控制栏也能配**。
            title = stringResource(R.string.settings_player_buttons),
            note = stringResource(R.string.settings_player_buttons_hint),
        ) {
            // ⛔ 它是同一分类里的**最后一个**选择器 → pinTail = true。
            // 不钉的话光标走到末尾时后面没有已组合的节点，几何搜索会往回跳（真实踩过，见 `OrderPicker.pinTail`）。
            PlayerBarPicker(enabledIds = barButtons, onChange = onBarButtons, pinTail = true,
                firstFocus = firstFocus, lastFocus = lastFocus, headUp = headUp, tailDown = tailDown)
        }

        SettingsItem.DANMAKU_ON,
        SettingsItem.DANMAKU_ALPHA,
        SettingsItem.DANMAKU_SCALE,
        SettingsItem.DANMAKU_AREA,
        SettingsItem.DANMAKU_OVERLAP,
        SettingsItem.DANMAKU_SPEED,
        SettingsItem.DANMAKU_SCROLL,
        SettingsItem.DANMAKU_REVERSE,
        SettingsItem.DANMAKU_TOP,
        SettingsItem.DANMAKU_BOTTOM,
        SettingsItem.DANMAKU_COLOR,
        SettingsItem.DANMAKU_ADVANCED,
        SettingsItem.DANMAKU_MERGE,
        SettingsItem.DANMAKU_REPEAT,
        SettingsItem.DANMAKU_LEVEL,
        SettingsItem.DANMAKU_CLOUD,
        SettingsItem.DANMAKU_INTERACTION,
        SettingsItem.DANMAKU_OUTLINE,
        SettingsItem.DANMAKU_OUTLINE_ALPHA,
        SettingsItem.DANMAKU_TRACK,
        SettingsItem.DANMAKU_LINES,
        SettingsItem.DANMAKU_KEYWORDS,
        SettingsItem.DANMAKU_REGEX,
        SettingsItem.DANMAKU_USERS,
        SettingsItem.SUBTITLE_LANGUAGE,
        SettingsItem.SUBTITLE_FONT,
        SettingsItem.SUBTITLE_BACKGROUND,
        SettingsItem.DANMAKU_RESET -> DanmakuSettingItem(item, settings, modifier = backLeft) {}

        // ============================================================ 广告

        SettingsItem.SPONSOR_ON -> ToggleRow(
            title = stringResource(R.string.settings_sponsor),
            desc = stringResource(R.string.settings_sponsor_hint),
            on = sponsorOn,
            onToggle = onSponsorOn,
            modifier = backLeft,
        )

        SettingsItem.AD_FILTER -> ToggleRow(
            // ★ 这一项以前**没有任何入口**：`SettingsStore.filterUiAds` 一直默认开、
            //   首页也一直在读它，但设置页里从来没有这一行。等于用户永远改不了。
            title = stringResource(R.string.settings_ad_filter),
            desc = stringResource(R.string.settings_ad_filter_hint),
            on = filterAds,
            onToggle = onFilterAds,
            modifier = backLeft,
        )

        SettingsItem.SPONSOR_CATEGORIES -> Column(modifier = backLeft) {
            HintText(stringResource(R.string.settings_sponsor_categories_hint))
            SponsorCategory.entries.forEach { category ->
                ToggleRow(
                    title = stringResource(when (category) { SponsorCategory.SPONSOR -> R.string.sponsor_paid; SponsorCategory.SELF_PROMO -> R.string.sponsor_self; SponsorCategory.EXCLUSIVE_ACCESS -> R.string.sponsor_brand; SponsorCategory.INTRO -> R.string.sponsor_intro; SponsorCategory.OUTRO -> R.string.sponsor_outro; SponsorCategory.INTERACTION -> R.string.sponsor_interaction; SponsorCategory.PREVIEW -> R.string.sponsor_preview; SponsorCategory.FILLER -> R.string.sponsor_filler; SponsorCategory.MUSIC_OFFTOPIC -> R.string.sponsor_music; SponsorCategory.POI_HIGHLIGHT -> R.string.sponsor_highlight; SponsorCategory.OTHER -> R.string.sponsor_other }),
                    desc = null,
                    on = category in sponsorCats,
                    onToggle = { on -> onSponsorCats(if (on) sponsorCats + category else sponsorCats - category) },
                )
            }
        }

        // ============================================================ 播放

        SettingsItem.SUBTITLE_ON -> {
            var on by remember { mutableStateOf(settings.subtitleEnabled) }
            ToggleRow(stringResource(R.string.settings_subtitle), stringResource(R.string.settings_subtitle_hint), on, {
                settings.subtitleEnabled = it; on = it
            }, backLeft)
        }

        SettingsItem.QUALITY -> ChoiceRow(
            title = stringResource(R.string.settings_default_quality),
            desc = stringResource(R.string.settings_default_quality_hint),
            options = QualityOptions.ALL,
            selected = QualityOptions.ALL.firstOrNull { it.id == quality } ?: QualityOptions.AUTO,
            labelOf = { when (it.id) { 0 -> context.getString(R.string.value_auto); 126 -> context.getString(R.string.quality_dolby); 112 -> context.getString(R.string.quality_high_bitrate); else -> it.label } },
            onSelect = { onQuality(it.id) },
            modifier = backLeft,
        )

        SettingsItem.SEEK_SECONDS -> ChoiceRow(stringResource(R.string.settings_seek_step), stringResource(R.string.settings_seek_step_hint),
            PlaybackTuning.SEEK_SECONDS, seekSeconds, { context.getString(R.string.value_seconds, it) }, onSeekSeconds, backLeft)

        SettingsItem.AUDIO_QUALITY -> {
            var audio by remember { mutableStateOf(settings.preferredAudioQuality) }
            ChoiceRow(stringResource(R.string.settings_audio), stringResource(R.string.settings_audio_hint),
                top.bilitv.data.settings.AudioQuality.entries.toList(), audio, { context.getString(when (it) { top.bilitv.data.settings.AudioQuality.AUTO -> R.string.audio_auto; top.bilitv.data.settings.AudioQuality.AAC_192 -> R.string.audio_192; top.bilitv.data.settings.AudioQuality.AAC_132 -> R.string.audio_132; top.bilitv.data.settings.AudioQuality.AAC_64 -> R.string.audio_64; top.bilitv.data.settings.AudioQuality.DOLBY -> R.string.audio_dolby; top.bilitv.data.settings.AudioQuality.HI_RES -> R.string.audio_hires }) },
                { audio = it; settings.preferredAudioQuality = it }, backLeft)
        }

        SettingsItem.PERFORMANCE -> {
            var mode by remember { mutableStateOf(settings.playbackPerformance) }
            ChoiceRow(stringResource(R.string.settings_performance), stringResource(R.string.settings_performance_hint),
                top.bilitv.data.settings.PlaybackPerformance.entries.toList(), mode, { context.getString(when (it) { top.bilitv.data.settings.PlaybackPerformance.HIGH -> R.string.performance_high; top.bilitv.data.settings.PlaybackPerformance.BALANCED -> R.string.performance_balanced; top.bilitv.data.settings.PlaybackPerformance.MEMORY -> R.string.performance_memory }) },
                { mode = it; settings.playbackPerformance = it }, backLeft)
        }

        SettingsItem.AUTO_LOWER_QUALITY -> ToggleRow(
            title = stringResource(R.string.settings_auto_lower),
            desc = stringResource(R.string.settings_auto_lower_hint),
            on = autoLowerQuality,
            onToggle = onAutoLowerQuality,
            modifier = backLeft,
        )

        SettingsItem.PREFER_HEVC -> ToggleRow(
            title = stringResource(R.string.settings_hevc),
            desc = stringResource(R.string.settings_hevc_hint),
            on = preferHevc,
            onToggle = onPreferHevc,
            modifier = backLeft,
        )

        SettingsItem.AUTO_NEXT -> {
            var action by remember { mutableStateOf(settings.playbackEndAction) }
            ChoiceRow(stringResource(R.string.settings_end_action), stringResource(R.string.settings_end_action_hint),
                PlaybackTuning.EndAction.entries.toList(), action, { context.getString(when (it) { PlaybackTuning.EndAction.PAUSE -> R.string.end_pause; PlaybackTuning.EndAction.NEXT -> R.string.end_next; PlaybackTuning.EndAction.LOOP -> R.string.end_loop }) },
                { action = it; settings.playbackEndAction = it }, backLeft)
        }

        SettingsItem.SKIP_OFFICIAL_INTRO_OUTRO -> {
            var enabled by remember { mutableStateOf(settings.skipOfficialIntroOutro) }
            ToggleRow(stringResource(R.string.settings_official_skip), stringResource(R.string.settings_official_skip_hint),
                enabled, { enabled = it; settings.skipOfficialIntroOutro = it }, backLeft)
        }

        SettingsItem.DETAIL_PAGE -> ToggleRow(
            title = stringResource(R.string.settings_detail_page),
            desc = stringResource(R.string.settings_detail_page_hint),
            on = detailPage,
            onToggle = onDetailPage,
            modifier = backLeft,
        )

        SettingsItem.RETURN_DETAILS -> {
            var enabled by remember { mutableStateOf(settings.returnDetailsOnExit) }
            ToggleRow(stringResource(R.string.settings_return_details), stringResource(R.string.settings_return_details_hint),
                enabled, { enabled = it; settings.returnDetailsOnExit = it }, backLeft)
        }
        SettingsItem.RESUME_CHOICE -> {
            var enabled by remember { mutableStateOf(settings.askResume) }
            ToggleRow(stringResource(R.string.settings_ask_resume), stringResource(R.string.settings_ask_resume_hint),
                enabled, { enabled = it; settings.askResume = it }, backLeft)
        }

        SettingsItem.BACK_EXIT -> ToggleRow(
            title = stringResource(R.string.settings_back_exit),
            desc = stringResource(R.string.settings_back_exit_hint),
            on = singleBackExit,
            onToggle = onSingleBackExit,
            modifier = backLeft,
        )

        // ============================================================ 高级

        SettingsItem.ADVANCED_SWITCH -> ToggleRow(
            title = stringResource(R.string.setting_advanced),
            desc = stringResource(R.string.settings_advanced_hint),
            on = advanced,
            onToggle = onAdvanced,
            modifier = backLeft,
        )

        // ============================================================ 调优 · 解码

        SettingsItem.PLAYBACK_STATS -> {
            var enabled by remember { mutableStateOf(settings.showPlaybackStats) }
            ToggleRow(stringResource(R.string.settings_stats), stringResource(R.string.settings_stats_hint),
                enabled, { enabled = it; settings.showPlaybackStats = it }, backLeft)
        }

        SettingsItem.FORCE_AVC -> ToggleRow(
            // 消费者：PlayerScreen.pickSelection → StreamSelector(onlyAvc = forceAvc)
            title = stringResource(R.string.settings_avc),
            desc = stringResource(R.string.settings_avc_hint),
            on = forceAvc,
            onToggle = onForceAvc,
            modifier = backLeft,
        )

        // ============================================================ 调优 · 线路

        SettingsItem.SKIP_P2P -> ToggleRow(
            // 消费者：PlayerScreen.startPlayback → BiliPlayer.play(skipP2p =) → CdnOrder.order
            title = stringResource(R.string.settings_skip_p2p),
            desc = stringResource(R.string.settings_skip_p2p_hint),
            on = skipP2p,
            onToggle = onSkipP2p,
            modifier = backLeft,
        )

        SettingsItem.CDN_PREF -> ChoiceRow(
            // 消费者：BiliPlayer.candidates → PlayTolerance.orderByPreference
            title = stringResource(R.string.settings_cdn),
            desc = stringResource(R.string.settings_cdn_hint),
            options = CDN_PRESETS,
            selected = cdnPref,
            labelOf = { if (it == CDN_NONE) context.getString(R.string.cdn_default) else it },
            onSelect = onCdnPref,
            modifier = backLeft,
        )

        // ============================================================ 调优 · 容错

        SettingsItem.RETRY_NO_P2P -> ToggleRow(
            // 消费者：PlayerScreen.onUnrecoverable → BiliPlayer.retryWithoutP2p
            title = stringResource(R.string.settings_retry_source),
            desc = stringResource(R.string.settings_retry_source_hint),
            on = retryNoP2p,
            onToggle = onRetryNoP2p,
            modifier = backLeft,
        )

        SettingsItem.DECODER -> ChoiceRow(
            // 消费者：DecoderSelector.forStored → ExoPlayer.setMediaCodecSelector
            // ★ 落盘存的是英文 id（auto/software/vendor），界面显示中文标签。
            //   存文案的写法已经吃过一次亏（改个措辞用户的配置就丢了）。
            title = stringResource(R.string.settings_decoder),
            desc = stringResource(R.string.settings_decoder_hint, context.getString(when (DecoderSelector.normalize(decoder)) {
                DecoderSelector.SOFTWARE -> R.string.decoder_software_hint
                DecoderSelector.VENDOR -> R.string.decoder_vendor_hint
                else -> R.string.decoder_auto_hint
            })),
            options = DECODER_IDS,
            selected = decoder,
            labelOf = { context.getString(when (DecoderSelector.normalize(it)) { DecoderSelector.SOFTWARE -> R.string.decoder_software; DecoderSelector.VENDOR -> R.string.decoder_vendor; else -> R.string.value_auto }) },
            onSelect = onDecoder,
            modifier = backLeft,
        )

        // ============================================================ 调优 · 播放默认值

        SettingsItem.SPEED_DEFAULT -> ChoiceRow(
            // 消费者：PlayerViewModel.speedIndex 的初值（来自 settings.playbackSpeedIndex）
            title = stringResource(R.string.settings_speed),
            desc = stringResource(R.string.settings_speed_hint),
            options = PlaybackTuning.SPEEDS.indices.toList(),
            selected = speedIndex,
            labelOf = { PlaybackTuning.speedLabel(it) },
            onSelect = onSpeedIndex,
            modifier = backLeft,
        )

        SettingsItem.ASPECT_DEFAULT -> ChoiceRow(
            // 消费者：PlayerScreen 的 PlayerView.resizeMode（经 resizeModeOf 翻译）
            title = stringResource(R.string.settings_aspect),
            desc = stringResource(R.string.settings_aspect_hint),
            options = PlaybackTuning.ASPECTS,
            selected = PlaybackTuning.aspectOf(aspectId),
            labelOf = { context.getString(when (it.id) { "fit" -> R.string.aspect_fit; "fill" -> R.string.aspect_fill; else -> R.string.aspect_crop }) },
            onSelect = { onAspectId(it.id) },
            modifier = backLeft,
        )

        // ============================================================ 存储

        SettingsItem.IMAGE_CACHE -> ActionRow(
            title = stringResource(R.string.settings_image_cache),
            desc = stringResource(R.string.settings_image_cache_hint, formatBytes(cacheBytes)),
            actionLabel = stringResource(R.string.action_clear),
            onAction = onClearCache,
            modifier = backLeft,
        )

        SettingsItem.RUN_LOG -> {
            var open by remember { mutableStateOf(false) }
            ActionRow(title = stringResource(R.string.settings_logs), desc = stringResource(R.string.settings_logs_hint),
                actionLabel = stringResource(R.string.action_view), onAction = { open = true }, modifier = backLeft)
            if (open) Dialog(onDismissRequest = { open = false }) {
                val logFocus = remember { FocusRequester() }
                top.bilitv.ui.player.LogPanel(onClose = { open = false }, firstFocus = logFocus,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(.85f))
                top.bilitv.ui.components.RequestFocusOnAppear(logFocus, true)
            }
        }

        // ============================================================ 关于

        SettingsItem.ABOUT_VERSION -> InfoRow(
            title = stringResource(R.string.app_name),
            desc = stringResource(R.string.settings_about_hint),
            value = appVersion,
        )
    }
}

internal val CDN_PRESETS = listOf("不干预", "upos-sz-mirrorcos", "upos-hz", "cn-gotcha")

internal const val CDN_NONE = "不干预"

internal val DECODER_IDS = listOf(
    DecoderSelector.AUTO, DecoderSelector.SOFTWARE, DecoderSelector.VENDOR,
)

@Composable
internal fun Modifier.backToCategories(): Modifier {
    val anchor = LocalCategoryAnchor.current ?: return this
    return this.focusProperties { left = anchor }
}

internal val LocalCategoryAnchor = staticCompositionLocalOf<FocusRequester?> { null }

@Composable
internal fun HintText(text: String) {
    val theme = AppTheme.current
    Text(
        text = text,
        style = TextStyle(fontSize = AppType.Small),
        color = theme.textTertiary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun PickerBlock(
    title: String,
    note: String,
    content: @Composable () -> Unit,
) {
    val theme = AppTheme.current
    val anchor = LocalCategoryAnchor.current
    // 胶囊的左右键用于排序；返回键是明确的退出路径，不能把分类左键规则包住胶囊。
    Column(modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
        if (it.key == Key.Back && anchor != null) {
            if (it.type == KeyEventType.KeyDown) anchor.requestFocus()
            true
        } else false
    }) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.textPrimary,
        )
        content()
        Text(
            text = stringResource(R.string.settings_order_hint) +
                if (note.isEmpty()) "" else "\n$note",
            style = TextStyle(fontSize = AppType.Small),
            color = theme.textTertiary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
