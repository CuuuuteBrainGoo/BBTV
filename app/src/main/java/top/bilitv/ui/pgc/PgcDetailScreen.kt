package top.bilitv.ui.pgc

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
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
import top.bilitv.util.AppLog

/**
 * 影视 / 番剧的「剧集详情」页。
 *
 * ## 为什么这一页值得单独做（而不是点一下直接播）
 *
 * 影视内容和普通视频不一样：**先选集，再播放**。直接播第 1 集、
 * 想跳集得退回列表再点，是电视上最烦人的一种交互。
 * Netflix / Disney+ / Apple TV 都是"详情页 + 选集"，这里照做。
 *
 * ## ⚠️ 未登录时这一页拿不到数据（这是接口的限制，不是 bug）
 *
 * `pgc/view/web/season` 在**未登录时返回 `code=0` 但 `data=null`**。
 * 所以这一页必须有第三种状态：**"登录后才能看"**，
 * 而且要和"接口挂了"分开说 —— 两种情况用户能做的事完全不同。
 * 判据用 `BiliApi.isLoggedIn()`，不能只看返回值空不空。
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
    val theme = AppTheme.current

    Box(modifier = Modifier.fillMaxSize().background(theme.background)) {
        val detail = vm.detail
        when {
            vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            detail != null -> LazyVerticalGrid(
                columns = GridCells.Fixed(EPISODE_COLUMNS),
                contentPadding = PaddingValues(
                    start = theme.screenPadding,
                    end = theme.screenPadding,
                    bottom = theme.screenPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
                verticalArrangement = Arrangement.spacedBy(theme.rowGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                    SeasonHeader(
                        detail = detail,
                        onPlayFirst = {
                            detail.episodes.firstOrNull()?.let {
                                onPlayEpisode(it.epId, it.cid, detail.playbackTitle(it), it.cover)
                            }
                        },
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "label") {
                    Text(
                        text = "选集 · 共 ${detail.episodes.size} 集",
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
            )
        }

        BackChip(
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(theme.screenPadding),
        )
    }
}

/** 一屏几列剧集。16:9 缩略图，8 列在 960dp 宽下每张约 96dp。 */
private const val EPISODE_COLUMNS = 8

/**
 * 剧集详情的头部：全幅剧照 + 标题 + 评分 + 简介 + 主按钮。
 *
 * 和影视列表页的主视觉同源（同样是两层渐变融进底色），但**文字块更宽**——
 * 详情页的简介需要横向空间，"留白一半给画面"在这里会让简介被切成三条。
 */
@Composable
private fun SeasonHeader(detail: PgcDetail, onPlayFirst: () -> Unit) {
    val theme = AppTheme.current
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(detail.cover.fixedScheme())
                .size(1600, 900)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.3f to Color.Transparent, 1f to theme.background)
            )
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
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
                .fillMaxWidth(0.72f)
                .padding(start = theme.screenPadding + 16.dp, bottom = 22.dp),
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
                    Text("分", style = TextStyle(fontSize = AppType.Small), color = theme.textSecondary)
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
                    text = "播放第 1 集",
                    onClick = onPlayFirst,
                    modifier = Modifier.padding(top = 6.dp),
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
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(theme.cardCorner - 3.dp)),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(episode.cover.fixedScheme())
                    .size(320, 180)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
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
 * 关键在于**把两种原因分开说**：没登录（用户能自己解决）和接口异常（用户解决不了，
 * 只能等我们修）。含混地显示一句"加载失败"，用户唯一能做的就是反复重试。
 */
@Composable
private fun LockedNotice(
    message: String,
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onNeedLogin: () -> Unit,
) {
    val theme = AppTheme.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (isLoggedIn) "暂时拿不到剧集信息" else "番剧与影视需要登录后才能观看",
            style = TextStyle(fontSize = AppType.H2, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
        )
        Text(
            text = message,
            style = TextStyle(fontSize = AppType.Body3, lineHeight = 22.sp),
            color = theme.textSecondary,
            modifier = Modifier.padding(top = 14.dp),
        )
        if (!isLoggedIn) {
            FilledActionButton(
                text = "去登录",
                onClick = onNeedLogin,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        BackChip(onBack = onBack, modifier = Modifier.padding(top = 16.dp))
    }
}

/** 详情页头部高度。比列表页主视觉略高，因为要放下简介。 */
private val HEADER_HEIGHT = 330.dp

class PgcDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp

    var detail by mutableStateOf<PgcDetail?>(null)
        private set
    var loading by mutableStateOf(true)
        private set

    /** 拿不到数据时给用户看的那句话。**已经区分过"没登录"和"接口异常"**。 */
    var message by mutableStateOf("")
        private set

    var loggedIn by mutableStateOf(false)
        private set

    private var loadedId = 0L

    fun load(seasonId: Long) {
        if (loadedId == seasonId && detail != null) return
        loadedId = seasonId
        loading = true
        viewModelScope.launch {
            loggedIn = graph.api.isLoggedIn()
            val d = graph.api.pgcDetail(seasonId)
            detail = d
            message = when {
                d != null -> ""
                !loggedIn -> "这部剧的剧集列表和播放地址都要带账号信息才拿得到。" +
                        "登录之后这一页就会自动有内容，不需要重新装 App。"
                else -> "已经登录了，但接口还是返回空。可能是这部剧有地区限定，" +
                        "或者接口变了 —— 点「日志」看详情。"
            }
            loading = false
            AppLog.i("PgcDetail", "season=$seasonId 结果=${d?.let { "${it.episodes.size} 集" } ?: "空"} 已登录=$loggedIn")
        }
    }
}
