package top.bilitv.ui.detail

import top.bilitv.ui.components.scrollWithScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.bilitv.R
import top.bilitv.data.settings.uiLocale
import top.bilitv.data.model.VideoDetail
import top.bilitv.ui.components.BackChip
import top.bilitv.ui.components.CinemaHero
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.TvCard
import top.bilitv.ui.components.adaptiveHeroHeight
import top.bilitv.ui.components.formatCount
import top.bilitv.ui.components.formatDuration
import top.bilitv.ui.theme.AppTheme

/**
 * 视频详情。
 *
 * ## 2026-09-29 重做（少爷点名"更难看了"）
 *
 * ### 旧版为什么难看
 *
 * 旧版是"**左图右字**"：左边一张 420dp 的 16:9 缩略图，右边标题 + 一行元信息 +
 * 一个纯文字的「▶ 播放」。毛病全是"网页思维"带来的：**图不够大**
 * （10 尺距离下 420dp 约等于手机上的一张缩略图）；**主操作是个字**
 * （没有按钮形态，用户得先"读"才知道能按）；**字号全挤在一起**
 * （标题 28sp / 元信息 16sp / 简介 16sp，看不出层级）。
 *
 * ### 现在长什么样
 *
 * **直接用影院语言（`CinemaHero`）当页头**，和影视详情页（`PgcDetailScreen`）同一套：
 * 全幅大图 + 两层渐变融进底色 + 大标题（40sp，是元信息 15sp 的两倍多）+ 一行元信息 +
 * 一个**实心主色**的「播放」按钮。结构是"页头 → 分P → 简介"的**纵向流**而不是左右分栏 ——
 * 遥控器只有四个方向，纵向单列少一层"该往哪按"的判断。
 *
 * ### 硬编码数值都去哪了
 *
 * 全走已有 token：高度 → `theme.heroHeight`；间距 → `theme.screenPadding` /
 * `sectionGap` / `cardGap`；字号 → `MaterialTheme.typography`（都在 `Theme.kt` 里）。
 *
 * ## 为什么要自带一个「← 返回」（2026-09-28 补，本轮保留）
 *
 * `docs/11` §5 的 P0：这一页原来**整页只有「播放」一个可聚焦元素**，
 * 没有分P 的视频在屏幕上**找不到任何"返回"入口**。现在返回按钮**压在页头上**
 * （和影视详情页一致），**加载中 / 出错时也在**。
 *
 * @param onBack 返回上一页。由 `Nav.kt` 的栈负责真的 pop。
 */
@Composable
fun DetailScreen(bvid: String, onBack: () -> Unit, onPlay: (Long) -> Unit) {
    val vm: DetailViewModel = viewModel()
    val playFocus = remember { FocusRequester() }
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? top.bilitv.BiliTvApp
    val sections = app?.settings?.detailSections ?: top.bilitv.data.settings.DetailLayout.ALL
    val showMeta = app?.settings?.detailShowMeta != false
    LaunchedEffect(bvid) { vm.load(bvid) }
    DisposableEffect(vm) { onDispose { vm.stopLoading() } }

    val detail = vm.detail
    val theme = AppTheme.current

    /*
     * 「重试」的焦点目标。
     *
     * 出错页如果没焦点，用户看着眼前的「重试」按钮却按不到，
     * 只能糊里糊涂按返回 —— 这是最容易让人火大的一种死法。
     * 所以进出错态时把焦点**主动送到重试上**。
     */
    val retryButton = remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            vm.loading && detail == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            detail == null -> ErrorPanel(
                message = vm.error ?: stringResource(R.string.loading_failed),
                retryButton = retryButton,
                onRetry = { vm.retry() },
                modifier = Modifier.align(Alignment.Center),
            )

            else -> {
                DetailContent(detail = detail, onPlay = onPlay, playFocus = playFocus, sections = sections, showMeta = showMeta)
                if (vm.loading || vm.error != null) top.bilitv.ui.components.LoadFeedback(
                    vm.loading, vm.error, vm::retry,
                    Modifier.align(Alignment.BottomCenter).padding(theme.screenPadding)
                        .then(Modifier.background(theme.surfaceHigh)),
                )
            }
        }

        // 常驻返回。压在页头上 —— BackChip 自带不透明底色，在亮画面上也看得清。
        BackChip(
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(theme.screenPadding),
        )
    }

    /*
     * 默认焦点：数据到位给「播放」，出错给「重试」，加载中**不请求**。
     *
     * 加载中不请求是有意的：那一刻两个 requester 都指不到真实节点，
     * requestFocus() 会抛异常（然后被记成一条无意义的警告日志），
     * 而且此时本来也没有可操作的东西。
     */
    if (detail != null) {
        RequestFocusOnAppear(playFocus, detail.cid)
    } else if (!vm.loading) {
        RequestFocusOnAppear(retryButton, vm.error)
    }
}

/**
 * 出错态：一句话 + 一个「重试」。
 *
 * 不显示「返回」按钮 —— 左上角那个常驻的 BackChip 已经在了，
 * 再加一个只会让焦点链变长。
 *
 * 文案里的 `⟳` 上次改造时已删：那是**拿符号当图标**，
 * `docs/audit/A9` §7.1 把这类（`↻ / ⏸ / ▶ / ←`）判为要清掉的违规项。
 */
@Composable
private fun ErrorPanel(
    message: String,
    retryButton: FocusRequester,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AppTheme.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = theme.textPrimary,
        )
        TvCard(
            onClick = onRetry,
            modifier = Modifier
                .focusRequester(retryButton)
                .padding(top = 24.dp),
            contentDescription = stringResource(R.string.action_retry),
        ) {
            Text(
                text = stringResource(R.string.action_retry),
                style = MaterialTheme.typography.titleMedium,
                color = theme.textPrimary,
                modifier = Modifier.padding(horizontal = CHIP_PADDING_H, vertical = CHIP_PADDING_V),
            )
        }
    }
}

/**
 * 数据到位后的正文：**页头 → 分P → 简介**，一条纵向流。
 *
 * 整个 Column 是 `verticalScroll` 的。分P 用横滑 [LazyRow]。
 * ⚠️ `docs/31` §3.5：简介区现有可聚焦的「展开」按钮（[DescriptionSection]），而本项目记录
 * `verticalScroll` 不为移出视口的焦点自动滚动（`docs/13` §4.1）——保留它的前提是"焦点到
 * 「展开」会跟着滚"，需模拟器实测；不滚就必须换 `LazyColumn`。
 *
 * ⚠️ 这里**不能给 Column 加左右内边距**：页头是**全幅**的，
 * 一旦整体内缩，主视觉左右就会各留一条空边，和影视页的沉浸感就不一样了。
 * 于是左右内边距由下面每一段各自加（`theme.screenPadding`）。
 */
@Composable
private fun DetailContent(detail: VideoDetail, onPlay: (Long) -> Unit, playFocus: FocusRequester,
    sections: List<String>, showMeta: Boolean) {
    val theme = AppTheme.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .scrollWithScrollbar(rememberScrollState())
            .padding(bottom = theme.sectionGap),
    ) {
        CinemaHero(
            imageUrl = detail.cover,
            title = detail.title,
            meta = if (showMeta) detailMetaLine(detail, context) else "",
            actionText = stringResource(R.string.action_play),
            // 有分P 就播第一P，和旧版一致（点页头的大按钮 = 从头看）
            onClick = { onPlay(detail.pages.firstOrNull()?.cid ?: detail.cid) },
            focusRequester = playFocus,
            // ★ 页头比首页主视觉高一档：这一页**必须有主操作**（「播放」），
            //   比首页多一整块按钮。首页那个 160dp 装不下它。
            // ★ 2026-09-30 少爷第 5 条：改成按屏高自适应（电视上仍是 280，更长的屏上自动收）
            height = adaptiveHeroHeight(
                cap = theme.detailHeaderHeight,
                floor = DETAIL_HERO_FLOOR,
                fraction = DETAIL_HERO_FRACTION,
            ),
            // 详情页标题给 2 行 —— 它是一整页的页头，标题本身就是要看的信息
            titleMaxLines = 2,
        )

        for (section in sections) {
        if (section == "PARTS" && detail.pages.size > 1) {
            SectionTitle(
                text = stringResource(R.string.detail_parts_count, detail.pages.size),
                modifier = Modifier.padding(
                    start = theme.screenPadding,
                    end = theme.screenPadding,
                    top = theme.sectionGap,
                    bottom = theme.cardGap,
                ),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
                // 上下留余量：TvCard 的焦点描边（3dp）+ 放大（1.03）会画出内容区，
                // 不给余量的话第一行的描边会被 LazyRow 裁掉。
                contentPadding = PaddingValues(
                    start = theme.screenPadding,
                    end = theme.screenPadding,
                    top = FOCUS_ROOM,
                    bottom = FOCUS_ROOM,
                ),
            ) {
                items(detail.pages, key = { it.cid }) { page ->
                    TvCard(
                        onClick = { onPlay(page.cid) },
                        contentDescription = stringResource(R.string.detail_part_description, page.index, page.title, formatDuration(page.durationSec)),
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = CHIP_PADDING_H,
                                vertical = CHIP_PADDING_V,
                            )
                        ) {
                            Text(
                                text = "P${page.index}  ${page.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = theme.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatDuration(page.durationSec),
                                style = MaterialTheme.typography.bodySmall,
                                color = theme.textTertiary,
                            )
                        }
                    }
                }
            }
        }

        if (section == "DESC" && detail.desc.isNotBlank()) {
            SectionTitle(
                text = stringResource(R.string.detail_description),
                modifier = Modifier.padding(
                    start = theme.screenPadding,
                    end = theme.screenPadding,
                    top = theme.sectionGap,
                    bottom = theme.cardGap,
                ),
            )
            DescriptionSection(
                desc = detail.desc,
                modifier = Modifier.padding(horizontal = theme.screenPadding),
            )
        }
        }
    }
}

/** 段落小标题（「分P（12）」「简介」）。字号走 typography，不在组件里写死。 */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = AppTheme.current.textPrimary,
        modifier = modifier,
    )
}

/**
 * 页头那一行元信息：`UP主 · 368.1万播放 · 1.2万弹幕 · 04:42`。
 *
 * 沿用 `feedMetaLine` 同一条规矩：**取不到的项一个字都不出现**
 * （作者名为空不画、播放量为 0 不画 —— "0 播放"是句假话）。
 * 四项全缺返回空串，`CinemaHero` 据此整行不画。
 */
private fun detailMetaLine(d: VideoDetail, context: android.content.Context): String = buildList {
    if (d.ownerName.isNotBlank()) add(d.ownerName)
    if (d.viewCount > 0L) add(context.getString(R.string.detail_views, formatCount(d.viewCount, context.uiLocale)))
    if (d.danmakuCount > 0L) add(context.getString(R.string.detail_danmaku_count, formatCount(d.danmakuCount, context.uiLocale)))
    if (d.durationSec > 0) add(formatDuration(d.durationSec))
}.joinToString(" · ")

/*
 * 页面级排版常量（不随皮肤变，所以不进 `BiliTheme` —— 和 `FeedCard` / `SectionTabBar` 一个约定）。
 */

/** 分P / 重试 按钮的内边距。 */
private val CHIP_PADDING_H = 20.dp
private val CHIP_PADDING_V = 12.dp

/** 横滑行上下留的余量，给焦点描边 + 放大出界的空间。 */
private val FOCUS_ROOM = 8.dp

/*
 * 详情页页头高度的**自适应参数**（少爷 2026-09-30 第 5 条）。
 *
 * 上限仍是皮肤里的 280dp（电视上就取它，**电视观感不变**）——
 * 280 是 2026-09-30 那次"内容溢出压住「返回」"事故后算出来的数（`docs/29` 决策 034），
 * 不能随手改小。
 *
 * 下限 252 = 那一页文字块的实需高度：
 * ```
 * 62（避让左上角「返回」，15 + 48 + 15 的下沿）
 * + 标题 2 行 72（36sp / 行高 42sp）
 * + 间距 10 + 元信息 20 + 间距 10 + 按钮上距 4 + 按钮 46
 * + 底部内衬 26
 * ≈ 250  → 取 252 留 2dp 余量
 * ```
 * ⛔ 下限**必须** ≥ 这个数：文字块是 `BottomStart` 对齐，高度不够会往顶上溢，
 * 又一次变成"标题压住返回"。宁可手机上这一档小不下去，也不能裁。
 *
 * 系数 0.52 的效果：
 * ```
 * 电视 540dp → max(252, 281) = 281 → min(280, 281) = 280dp  （与改前完全一致）
 * 手机 411dp → max(252, 214) = 252 → 252dp                  （改前 280，占屏 68% → 61%）
 * ```
 */
private val DETAIL_HERO_FLOOR = 252.dp
private const val DETAIL_HERO_FRACTION = 0.52f
