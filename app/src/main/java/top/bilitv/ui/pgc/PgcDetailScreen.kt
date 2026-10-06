package top.bilitv.ui.pgc

import top.bilitv.R
import androidx.compose.ui.res.stringResource

import top.bilitv.ui.theme.pageBackground

import top.bilitv.ui.components.verticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import top.bilitv.ui.components.scrollWithScrollbar
import top.bilitv.ui.components.LoadFeedback
import top.bilitv.ui.theme.CardGridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import top.bilitv.ui.components.cappedCover
import top.bilitv.ui.components.adaptiveHeroHeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import top.bilitv.BiliTvApp
import top.bilitv.data.model.PgcDetail
import top.bilitv.data.model.PgcEpisode
import top.bilitv.ui.components.ContentBadge
import top.bilitv.ui.components.BackChip
import top.bilitv.ui.components.FilledActionButton
import top.bilitv.ui.components.formatDuration
import top.bilitv.ui.components.fixedScheme
import top.bilitv.ui.components.focusRing
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType

/**
 * 影视 / 番剧的「剧集详情」页。
 *
 * ## 为什么这一页值得单独做（而不是点一下直接播）
 *
 * 影视内容和普通视频不一样：**先选集，再播放**。直接播第 1 集、
 * 想跳集得退回列表再点，是电视上最烦人的一种交互。
 * Netflix / Disney+ / Apple TV 都是"详情页 + 选集"，这里照做。
 *
 * 空详情不代表已经确认权限：游客、地区限制或暂时接口故障都可能造成空响应。
 * 提供重试和可选登录入口，播放权限仍由取流接口确认。
 */
@Composable
fun PgcDetailScreen(
    seasonId: Long,
    onBack: () -> Unit,
    /**
     * 点某一集去播放。
     *
     * [cover] 一起传出去，是因为**播放页自己是拿不到 PGC 封面的** ——
     * 番剧/影视的详情接口要登录，播放页只有 `ep_id` 和 `cid`。
     * 它唯一的去处是观看记录列表，不传的话历史里会出现一行灰块。
     */
    onPlayEpisode: (epId: Long, cid: Long, title: String, cover: String) -> Unit,
    onNeedLogin: () -> Unit,
) {
    val vm: PgcDetailViewModel = viewModel()
    LaunchedEffect(seasonId) { vm.load(seasonId) }
    DisposableEffect(vm) { onDispose { vm.stopLoading() } }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val theme = AppTheme.current
    // 进入页面后要把焦点主动送到「播放第一集」；剧集为空时没有播放键，
    // 改送到常驻的返回按钮（与 DetailScreen 成功分支同源）。
    val playFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxSize().background(theme.pageBackground)) {
        val detail = vm.detail
        when {
            vm.loading && detail == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            detail != null -> LazyVerticalGrid(
                state = gridState,
                columns = CardGridCells(100.dp),
                contentPadding = PaddingValues(
                    start = theme.screenPadding,
                    end = theme.screenPadding,
                    bottom = theme.screenPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
                verticalArrangement = Arrangement.spacedBy(theme.rowGap),
                modifier = Modifier.fillMaxSize().verticalScrollbar(gridState),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                    SeasonHeader(
                        detail = detail,
                        playFocus = playFocus,
                        onPlayFirst = {
                            detail.episodes.firstOrNull()?.let {
                                onPlayEpisode(it.epId, it.cid, detail.playbackTitle(it), it.cover)
                            }
                        },
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "label") {
                    Text(
                        text = stringResource(R.string.cinema_episode_count, detail.episodes.size),
                        style = TextStyle(fontSize = AppType.H4, fontWeight = FontWeight.SemiBold),
                        color = theme.textPrimary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                }

                gridItems(detail.episodes, key = { it.epId }) { ep ->
                    EpisodeCard(
                        episode = ep,
                        onClick = { onPlayEpisode(ep.epId, ep.cid, detail.playbackTitle(ep), ep.cover) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            else -> LockedNotice(
                message = vm.message,
                isLoggedIn = vm.loggedIn,
                onBack = onBack,
                onNeedLogin = onNeedLogin,
                onRetry = vm::retry,
            )
        }

        // 只有数据到位才请求；key 取数据到位后才稳定的值，普通重组/选集不会重复抢焦点。
        if (detail != null) {
            if (detail.episodes.isNotEmpty()) {
                top.bilitv.ui.components.RequestFocusOnAppear(playFocus, detail.episodes.first().epId)
            } else {
                // 空剧集时播放键不渲染，绝不对未 attach 的 playFocus 发请求。
                top.bilitv.ui.components.RequestFocusOnAppear(backFocus, detail.seasonId)
            }
        }

        if (detail != null && (vm.loading || vm.message.isNotEmpty())) LoadFeedback(
            vm.loading, vm.message.takeIf { it.isNotEmpty() }, vm::retry,
            Modifier.align(Alignment.BottomCenter).padding(theme.screenPadding).background(theme.surfaceHigh),
        )

        BackChip(
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(theme.screenPadding).focusRequester(backFocus),
        )
    }
}

/**
 * 剧集详情的头部：全幅剧照 + 标题 + 评分 + 简介 + 主按钮。
 *
 * 和影视列表页的主视觉同源（同样是两层渐变融进底色），但**文字块更宽**——
 * 详情页的简介需要横向空间，"留白一半给画面"在这里会让简介被切成三条。
 */
@Composable
private fun SeasonHeader(detail: PgcDetail, playFocus: FocusRequester, onPlayFirst: () -> Unit) {
    val theme = AppTheme.current
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = adaptiveHeroHeight(330.dp, 200.dp, .55f)),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(detail.cover.fixedScheme())
                .size(1600, 900)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            modifier = Modifier.matchParentSize().background(
                Brush.verticalGradient(0.3f to Color.Transparent, 1f to theme.background)
            )
        )
        Box(
            modifier = Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to theme.background.copy(alpha = 0.9f),
                    0.7f to theme.background.copy(alpha = 0.3f),
                    1f to Color.Transparent,
                )
            )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(if (LocalConfiguration.current.screenWidthDp >= 640) 0.72f else 1f)
                .padding(start = theme.screenPadding + 16.dp, end = 20.dp, top = 72.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (detail.score.isNotBlank() && detail.score != "0") {
                    Text(
                        text = detail.score,
                        style = TextStyle(fontSize = AppType.H4, fontWeight = FontWeight.Bold),
                        color = theme.primary,
                    )
                    Text(stringResource(R.string.cinema_score_unit), style = TextStyle(fontSize = AppType.Small), color = theme.textSecondary)
                }
                if (detail.subtitle.isNotBlank()) {
                    Text(
                        text = detail.subtitle,
                        style = TextStyle(fontSize = AppType.Caption),
                        color = theme.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = detail.title,
                style = TextStyle(fontSize = AppType.Mega, fontWeight = FontWeight.Bold, lineHeight = 42.sp),
                color = theme.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (detail.evaluate.isNotBlank()) {
                Text(
                    text = detail.evaluate,
                    style = TextStyle(fontSize = AppType.Meta, lineHeight = 20.sp),
                    color = theme.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (detail.episodes.isNotEmpty()) {
                FilledActionButton(
                    text = stringResource(R.string.cinema_play_first),
                    onClick = onPlayFirst,
                    // focusRequester 必须排在 FilledActionButton 内部的 focusRing 之前，
                    // 否则会静默挂不上（FocusRing.kt 硬约束 1）。
                    modifier = Modifier.padding(top = 6.dp).then(Modifier.focusRequester(playFocus)),
                )
            }
        }
    }
}

/** 一张剧集卡：16:9 缩略图 + 集号角标 + 集名 */
@Composable
private fun EpisodeCard(
    episode: PgcEpisode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AppTheme.current
    val context = LocalContext.current
    val textHeight = with(LocalDensity.current) { 19.sp.toDp() * 2 + 12.dp }
    val coverLimit = (LocalConfiguration.current.screenHeightDp.dp - 128.dp - textHeight).coerceAtLeast(72.dp)

    Column(
        modifier = modifier
            .focusRing(
                contentDescription = episode.displayName +
                        if (episode.durationSec > 0) "，${formatDuration(episode.durationSec)}" else "",
                elevateOnFocus = true,
                onClick = onClick,
            )
            .padding(3.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .cappedCover(16f / 9f, coverLimit)
                .clip(RoundedCornerShape(theme.cardCorner - 3.dp)),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(episode.cover.fixedScheme())
                    .size(320, 180)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
            if (episode.durationSec > 0) {
                Text(
                    text = formatDuration(episode.durationSec),
                    style = TextStyle(fontSize = AppType.Micro),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (episode.badge.isNotBlank()) ContentBadge(episode.badge, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(
            text = episode.displayName,
            style = TextStyle(fontSize = AppType.Small, lineHeight = 16.sp),
            color = theme.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )
    }
}

/**
 * 「拿不到数据」时的说明页。
 *
 * 空详情不能推断权限；提供真实重试与游客可选登录，保持文字与按钮可滚动到达。
 */
@Composable
private fun LockedNotice(
    message: String,
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onNeedLogin: () -> Unit,
    onRetry: () -> Unit,
) {
    val theme = AppTheme.current
    Column(
        modifier = Modifier.fillMaxSize().scrollWithScrollbar(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.cinema_detail_unavailable),
            style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        Text(
            text = message,
            style = TextStyle(fontSize = AppType.Body3, lineHeight = 22.sp),
            color = theme.textSecondary,
            modifier = Modifier.padding(top = 14.dp),
        )
        val retryFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        FilledActionButton(stringResource(R.string.action_retry), onRetry, Modifier.padding(top = 20.dp).focusRequester(retryFocus))
        top.bilitv.ui.components.RequestFocusOnAppear(retryFocus, message)
        if (!isLoggedIn) {
            FilledActionButton(
                text = stringResource(R.string.action_sign_in),
                onClick = onNeedLogin,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        BackChip(onBack = onBack, modifier = Modifier.padding(top = 16.dp))
    }
}
