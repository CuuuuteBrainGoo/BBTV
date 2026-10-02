package top.bilitv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    autoNext: Boolean,
    onAutoNext: (Boolean) -> Unit,
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
    onClearLog: () -> Unit,
    // ------------------------------------------------------------ 关于

    appVersion: String,
    firstFocus: FocusRequester? = null,
    lastFocus: FocusRequester? = null,
    headUp: FocusRequester? = null,
    tailDown: FocusRequester? = null,
) {
    // 项目区所有行共用的修饰：左键回分类列（锚由 SettingsScreen 通过 CompositionLocal 提供）
    val backLeft = Modifier.backToCategories()

    when (item) {

        // ============================================================ 外观

        SettingsItem.INTERFACE_GENERAL -> InterfaceGeneralSettings(settings,
            firstFocus, lastFocus, headUp, tailDown)

        SettingsItem.SKIN -> ChoiceRow(
            title = "皮肤",
            desc = "换了立刻生效，不用重启",
            options = ThemeSkin.entries.toList(),
            selected = skin,
            labelOf = { it.label },
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
            title = "首页分区",
            note = "",
            // 后面还有「侧栏栏目」和「控制栏按钮」两个选择器 → pinTail = false（留在它们之间能往下走）
        ) { HomeSectionPicker(enabledIds = homeSections, onChange = onHomeSections,
            firstFocus = firstFocus, lastFocus = lastFocus, headUp = headUp, tailDown = tailDown) }

        SettingsItem.RAIL_TABS -> PickerBlock(
            // ★ 2026-09-30 少爷第 8 条：**侧栏也能配**。
            //   和「首页分区」是同一个组件，所以手感必然一致。
            //   唯一差别是「首页」「设置」带固定标记 —— 理由见 `NavTabPicker`。
            title = "侧栏栏目",

            note = "首页和设置固定位置；搜索和我的可排序；这四项不可隐藏",
            // 后面还有「控制栏按钮」，末尾允许向下离开
        ) { NavTabPicker(enabledIds = railIds, onChange = onRailIds,
            firstFocus = firstFocus, lastFocus = lastFocus, headUp = headUp, tailDown = tailDown) }

        SettingsItem.PLAYER_BUTTONS -> PickerBlock(
            // ★ 2026-09-30 少爷第 8 条的第三处：**控制栏也能配**。
            title = "控制栏按钮",
            note = "改完下次进播放页生效；播放/暂停不可隐藏；点赞长按 OK 1.5 秒三连",
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
        SettingsItem.SUBTITLE_COLOR,
        SettingsItem.SUBTITLE_POSITION,
        SettingsItem.SUBTITLE_BACKGROUND,
        SettingsItem.SUBTITLE_FADE,
        SettingsItem.DANMAKU_RESET -> DanmakuSettingItem(item, settings, modifier = backLeft) {}

        // ============================================================ 广告

        SettingsItem.SPONSOR_ON -> ToggleRow(
            title = "自动跳过",
            desc = "按 BilibiliSponsorBlock 的社区标记跳过恰饭、片头片尾等片段",
            on = sponsorOn,
            onToggle = onSponsorOn,
            modifier = backLeft,
        )

        SettingsItem.AD_FILTER -> ToggleRow(
            // ★ 这一项以前**没有任何入口**：`SettingsStore.filterUiAds` 一直默认开、
            //   首页也一直在读它，但设置页里从来没有这一行。等于用户永远改不了。
            title = "过滤界面里的广告卡",
            desc = "推荐流里那种没有有效视频的推广卡直接不显示",
            on = filterAds,
            onToggle = onFilterAds,
            modifier = backLeft,
        )

        SettingsItem.SPONSOR_CATEGORIES -> Column(modifier = backLeft) {
            HintText("跳过类别：取消勾选后，该类片段不再自动跳过")
            SponsorCategory.entries.forEach { category ->
                ToggleRow(
                    title = category.label,
                    desc = null,
                    on = category in sponsorCats,
                    onToggle = { on -> onSponsorCats(if (on) sponsorCats + category else sponsorCats - category) },
                )
            }
        }

        // ============================================================ 播放

        SettingsItem.SUBTITLE_ON -> {
            var on by remember { mutableStateOf(settings.subtitleEnabled) }
            ToggleRow("字幕开关", "默认关闭；与播放器字幕按钮联动，不影响视频内嵌文字", on, {
                settings.subtitleEnabled = it; on = it
            }, backLeft)
        }

        SettingsItem.QUALITY -> ChoiceRow(
            title = "默认清晰度",
            desc = "「自动」= 在这台设备能解的范围里取最高档。选不到指定档位时自动就近回退",
            options = QualityOptions.ALL,
            selected = QualityOptions.ALL.firstOrNull { it.id == quality } ?: QualityOptions.AUTO,
            labelOf = { it.label },
            onSelect = { onQuality(it.id) },
            modifier = backLeft,
        )

        SettingsItem.SEEK_SECONDS -> ChoiceRow("快进快退步长", "单击步长；长按逐步加速，松手跳到预览位置",
            PlaybackTuning.SEEK_SECONDS, seekSeconds, { "$it 秒" }, onSeekSeconds, backLeft)

        SettingsItem.AUTO_LOWER_QUALITY -> ToggleRow(
            title = "卡顿时自动降低画质",
            desc = "持续掉帧时逐级降低分辨率，保留播放进度；关闭后保持所选画质",
            on = autoLowerQuality,
            onToggle = onAutoLowerQuality,
            modifier = backLeft,
        )

        SettingsItem.PREFER_HEVC -> ToggleRow(
            title = "同清晰度优先 HEVC",
            desc = "同清晰度下优先选 HEVC，电视盒子普遍硬解更省电、更少掉帧",
            on = preferHevc,
            onToggle = onPreferHevc,
            modifier = backLeft,
        )

        SettingsItem.AUTO_NEXT -> ToggleRow(
            title = "自动连播下一集 / 下一P",
            desc = "看完自动接着播下一集。最后一集不会回绕重播；「直播」不参与连播",
            on = autoNext,
            onToggle = onAutoNext,
            modifier = backLeft,
        )

        SettingsItem.DETAIL_PAGE -> ToggleRow(
            title = "视频详情页",
            desc = "开：点视频卡先进介绍页（封面 / 简介 / 选集）；关（默认）：直接进播放。" +
                    "番剧和影视不受影响，仍需选集",
            on = detailPage,
            onToggle = onDetailPage,
            modifier = backLeft,
        )

        SettingsItem.BACK_EXIT -> ToggleRow(
            title = "单击返回键退出",
            desc = "开：播放页按一次返回就退出；关（默认）：第一次按只出提示，再按一次才退。" +
                    "无论开关如何，控制栏开着时按返回都只收起控制栏",
            on = singleBackExit,
            onToggle = onSingleBackExit,
            modifier = backLeft,
        )

        SettingsItem.PLAYER_UP_KEY, SettingsItem.PLAYER_DOWN_KEY -> {
            val up = item == SettingsItem.PLAYER_UP_KEY
            var enabled by remember { mutableStateOf(if (up) settings.playerUpEnabled else settings.playerDownEnabled) }
            var action by remember { mutableStateOf(if (up) settings.playerUpAction else settings.playerDownAction) }
            val keyName = if (up) "上键" else "下键"
            Column {
                ToggleRow("播放时${keyName}侧栏", "控制条隐藏时生效；返回或关闭按钮回到播放", enabled, {
                    enabled = it
                    if (up) settings.playerUpEnabled = it else settings.playerDownEnabled = it
                }, backLeft)
                ChoiceRow("${keyName}内容", null, PlaybackTuning.SideAction.entries.toList(), action, { it.label }, {
                    action = it
                    if (up) settings.playerUpAction = it else settings.playerDownAction = it
                }, backLeft)
            }
        }

        // ============================================================ 高级

        SettingsItem.ADVANCED_SWITCH -> ToggleRow(
            title = "高级模式",
            desc = "打开后可调跳过类别、播放默认值，并显示「解码与线路」「存储与日志」",
            on = advanced,
            onToggle = onAdvanced,
            modifier = backLeft,
        )

        // ============================================================ 调优 · 解码

        SettingsItem.FORCE_AVC -> ToggleRow(
            // 消费者：PlayerScreen.pickSelection → StreamSelector(onlyAvc = forceAvc)
            title = "只用 AVC（兼容模式）",
            desc = "完全不用 HEVC。黑屏 / 有声音没画面的机器打开这个能直接绕过，" +
                    "代价是同清晰度码率更高一点",
            on = forceAvc,
            onToggle = onForceAvc,
            modifier = backLeft,
        )

        // ============================================================ 调优 · 线路

        SettingsItem.SKIP_P2P -> ToggleRow(
            // 消费者：PlayerScreen.startPlayback → BiliPlayer.play(skipP2p =) → CdnOrder.order
            title = "跳过 P2P 加速节点",
            desc = "P2P 节点在部分宽带 / 路由器下连不通，会白等一次超时。" +
                    "只在起播总要卡一下的时候再开",
            on = skipP2p,
            onToggle = onSkipP2p,
            modifier = backLeft,
        )

        SettingsItem.CDN_PREF -> ChoiceRow(
            // 消费者：BiliPlayer.candidates → PlayTolerance.orderByPreference
            title = "指定 CDN",
            desc = "把匹配到的镜像排到最前。「只是优先，不是只用」 —— " +
                    "匹配不上时保持原样，不会让能播的视频变成不能播",
            options = CDN_PRESETS,
            selected = cdnPref,
            labelOf = { it },
            onSelect = onCdnPref,
            modifier = backLeft,
        )

        // ============================================================ 调优 · 容错

        SettingsItem.RETRY_NO_P2P -> ToggleRow(
            // 消费者：PlayerScreen.onUnrecoverable → BiliPlayer.retryWithoutP2p
            title = "卡住时自动换线路再试",
            desc = "播不出来时把 P2P 节点全部去掉、原地重开一次。" +
                    "它和上面那条「跳过 P2P」不是一回事：那个是「一开始就不用」，这个是「失败之后才用」",
            on = retryNoP2p,
            onToggle = onRetryNoP2p,
            modifier = backLeft,
        )

        SettingsItem.DECODER -> ChoiceRow(
            // 消费者：DecoderSelector.forStored → ExoPlayer.setMediaCodecSelector
            // ★ 落盘存的是英文 id（auto/software/vendor），界面显示中文标签。
            //   存文案的写法已经吃过一次亏（改个措辞用户的配置就丢了）。
            title = "解码器",
            desc = "让系统自己挑。只有「就某个编码起播崩、别的都正常」时才需要指定 —— " +
                    "这里只给三档、不给手打解码器名，打错名字会让所有视频都放不了。当前：" +
                    DecoderSelector.describe(decoder),
            options = DECODER_IDS,
            selected = decoder,
            labelOf = { DecoderSelector.label(it) },
            onSelect = onDecoder,
            modifier = backLeft,
        )

        // ============================================================ 调优 · 播放默认值

        SettingsItem.SPEED_DEFAULT -> ChoiceRow(
            // 消费者：PlayerViewModel.speedIndex 的初值（来自 settings.playbackSpeedIndex）
            title = "默认倍速",
            desc = "进播放页时的起始倍速。播放页那颗「倍速」按钮可以临时改，退出不保留",
            options = PlaybackTuning.SPEEDS.indices.toList(),
            selected = speedIndex,
            labelOf = { PlaybackTuning.speedLabel(it) },
            onSelect = onSpeedIndex,
            modifier = backLeft,
        )

        SettingsItem.ASPECT_DEFAULT -> ChoiceRow(
            // 消费者：PlayerScreen 的 PlayerView.resizeMode（经 resizeModeOf 翻译）
            title = "默认画面比例",
            desc = "「适应」留黑边但不变形；「拉伸」铺满但会变形（看老 4:3 片源用）；" +
                    "「裁切」去黑边但会切掉画面边缘",
            options = PlaybackTuning.ASPECTS,
            selected = PlaybackTuning.aspectOf(aspectId),
            labelOf = { it.label },
            onSelect = { onAspectId(it.id) },
            modifier = backLeft,
        )

        // ============================================================ 存储

        SettingsItem.IMAGE_CACHE -> ActionRow(
            title = "图片缓存",
            desc = "封面缩略图，删掉只是下次要重新下。当前占用 " + formatBytes(cacheBytes),
            actionLabel = "清理",
            onAction = onClearCache,
            modifier = backLeft,
        )

        SettingsItem.RUN_LOG -> ActionRow(
            title = "运行日志",
            desc = "排查问题时给的那份日志。清掉不影响使用",
            actionLabel = "清空",
            onAction = onClearLog,
            modifier = backLeft,
        )

        // ============================================================ 关于

        SettingsItem.ABOUT_VERSION -> InfoRow(
            title = stringResource(R.string.app_name),
            desc = "第三方 B 站电视客户端。仅供自用",
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
            text = "↑↓ 逐个移动 · ←→ 调顺序 · OK 显示/隐藏 · 返回键回分类" +
                if (note.isEmpty()) "" else "\n$note",
            style = TextStyle(fontSize = AppType.Small),
            color = theme.textTertiary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
