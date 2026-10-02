package top.bilitv.ui.pgc

import android.app.Application
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.bilitv.BiliTvApp
import top.bilitv.data.model.*
import top.bilitv.ui.components.*
import top.bilitv.ui.settings.ChoiceRow
import top.bilitv.ui.settings.SettingRow
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.util.AppLog

@Composable
fun PgcScreen(onOpenSeason: (Long) -> Unit) {
    val vm: PgcViewModel = viewModel()
    val theme = AppTheme.current
    var typeName by rememberSaveable { mutableStateOf(PgcType.MOVIE.name) }
    val type = PgcType.entries.firstOrNull { it.name == typeName } ?: PgcType.MOVIE
    LaunchedEffect(type) { vm.show(type) }
    val gridState = rememberLazyGridState()
    val heroFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    var filtersOpen by remember(type) { mutableStateOf(false) }
    var rankingOpen by remember(type) { mutableStateOf(false) }
    var resultsOnly by rememberSaveable(type) { mutableStateOf(false) }
    val resultsFocus = remember { FocusRequester() }
    val hero = vm.banners.ifEmpty { vm.ranking.ifEmpty { vm.items }.take(6) }
    LaunchedEffect(type, rankingOpen, resultsOnly) { gridState.scrollToItem(0) }
    Column(Modifier.fillMaxSize().background(theme.background)) {
        SectionTabBar(PgcType.entries.map { it.label }, type.ordinal,
            onSelect = { if (it == type.ordinal) vm.reload() else typeName = PgcType.entries[it].name },
            contentFocusRequester = if (resultsOnly) resultsFocus else if (hero.isNotEmpty() && !rankingOpen) heroFocus else null,
            modifier = Modifier.padding(horizontal = theme.screenPadding, vertical = 8.dp))
        when {
            vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            vm.items.isEmpty() && vm.ranking.isEmpty() && !resultsOnly -> Column(Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(vm.error ?: "暂时没有内容", color = theme.textSecondary)
                CinemaAction("重新加载", Modifier.focusRequester(retryFocus)) { vm.reload() }
            }
            else -> LazyVerticalGrid(GridCells.Fixed(6), state = gridState,
                contentPadding = PaddingValues(horizontal = theme.screenPadding, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
                verticalArrangement = Arrangement.spacedBy(theme.rowGap)) {
                if (!rankingOpen && !resultsOnly) {
                    if (hero.isNotEmpty()) item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                        RotatingCinemaHero(hero, onOpenSeason, heroFocus)
                    }
                    if (vm.ranking.isNotEmpty()) {
                        item(key = "rank", span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                CinemaHeading("${type.label}热播榜", "TOP ${vm.ranking.size.coerceAtMost(100)}") { rankingOpen = true }
                                PosterRow(vm.ranking.take(12), onOpenSeason, ranked = true)
                            }
                        }
                    }
                    if (vm.picks.isNotEmpty()) {
                        item(key = "picks", span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                CinemaHeading("为你精选", "刷新精选") { vm.shufflePicks() }
                                PosterRow(vm.picks, onOpenSeason)
                            }
                        }
                    }
                }
                item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        if (resultsOnly) CinemaAction("返回频道") { resultsOnly = false; vm.filter(emptyMap()) }
                        CinemaHeading(if (resultsOnly) "筛选结果 · ${type.label}" else if (rankingOpen) "${type.label}热播榜 TOP ${vm.ranking.size.coerceAtMost(100)}" else "全部${type.label}",
                            if (rankingOpen) "返回频道" else "筛选",
                            if (resultsOnly) Modifier.focusRequester(resultsFocus) else Modifier) {
                            if (rankingOpen) rankingOpen = false else filtersOpen = true
                        }
                    }
                }
                val displayed = if (rankingOpen) vm.ranking.take(100) else vm.items
                itemsIndexed(displayed, key = { _, s -> s.seasonId }) { i, season ->
                    PosterCard(season, Modifier.fillMaxWidth(), if (rankingOpen) i + 1 else null) { onOpenSeason(season.seasonId) }
                }
                if (!rankingOpen) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        vm.error?.let { Text(it, color = theme.textSecondary) }
                        if (vm.hasMore) CinemaAction(if (vm.loadingMore) "正在加载…" else "加载更多") { vm.more() }
                        else if (resultsOnly && vm.items.isEmpty()) CinemaAction("重新加载") { vm.reload() }
                        else Text("已经到底了", color = theme.textTertiary)
                    }
                }
            }
        }
    }
    // 标签有焦点时共享焦点保护会跳过请求，切换／刷新仍需 DOWN 才进入 Banner。
    RequestFocusOnAppear(heroFocus, !vm.loading && hero.isNotEmpty() && !rankingOpen && !resultsOnly)
    if (!vm.loading && vm.items.isEmpty() && vm.ranking.isEmpty() && !resultsOnly) RequestFocusOnAppear(retryFocus, vm.error)
    if (resultsOnly && !vm.loading) RequestFocusOnAppear(resultsFocus, vm.selectedFilters)
    if (filtersOpen) CinemaFilters(vm, onApplied = { resultsOnly = true; rankingOpen = false }) { filtersOpen = false }
}

@Composable
private fun CinemaAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val theme = AppTheme.current
    TvCard(onClick = onClick, modifier = modifier, focusedScale = 1f, contentDescription = text) {
        Text(text, color = theme.textPrimary, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

@Composable
private fun CinemaHeading(title: String, action: String, actionModifier: Modifier = Modifier, onAction: () -> Unit) {
    val theme = AppTheme.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = theme.textPrimary, style = TextStyle(fontSize = AppType.H4, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f))
        CinemaAction(action, actionModifier, onClick = onAction)
    }
}

@Composable
private fun CinemaFilters(vm: PgcViewModel, onApplied: () -> Unit, onDismiss: () -> Unit) {
    val theme = AppTheme.current
    var selected by remember { mutableStateOf(vm.selectedFilters) }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).background(theme.surface, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("${vm.type.label}筛选", color = theme.textPrimary, style = TextStyle(fontSize = AppType.H3))
            if (vm.fields.isEmpty()) Text("未取得该分区的筛选条件，请返回频道重新加载", color = theme.textSecondary)
            vm.fields.filter { it.values.isNotEmpty() }.forEachIndexed { i, field ->
                val value = field.values.firstOrNull { it.id == selected[field.id] }
                    ?: field.values.firstOrNull { it.id == if (field.id == "order") "2" else "-1" } ?: field.values.first()
                ChoiceRow(field.label, null, field.values, value, { it.label },
                    onSelect = { selected = selected + (field.id to it.id) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
            SettingRow("应用筛选", null, "", if (vm.fields.isEmpty()) Modifier.focusRequester(first) else Modifier) { onApplied(); vm.filter(selected); onDismiss() }
            SettingRow("重置筛选", null, "") { onApplied(); vm.filter(emptyMap()); onDismiss() }
        }
        RequestFocusOnAppear(first, true)
    }
}

@Composable
private fun RotatingCinemaHero(seasons: List<PgcSeason>, onOpen: (Long) -> Unit, focusRequester: FocusRequester) {
    val theme = AppTheme.current
    var index by remember(seasons) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(seasons, focused) {
        if (focused || seasons.size < 2) return@LaunchedEffect
        while (true) { delay(8000); index = (index + 1) % seasons.size }
    }
    val season = seasons[index.coerceIn(0, seasons.lastIndex)]
    Box(Modifier.fillMaxWidth().height(adaptiveHeroHeight(180.dp, 130.dp, .32f))
        .focusRequester(focusRequester).onFocusChanged { focused = it.hasFocus }
        .onPreviewKeyEvent {
            if (it.key == Key.DirectionLeft || it.key == Key.DirectionRight) {
                if (it.type == KeyEventType.KeyDown) index = (index + if (it.key == Key.DirectionLeft) seasons.size - 1 else 1) % seasons.size
                true
            } else false
        }.focusRing(contentDescription = "${season.title}，左右切换，OK观看", scaleOnFocus = 1f,
            onClick = { onOpen(season.seasonId) }).padding(3.dp)) {
        Crossfade(season, animationSpec = tween(350), label = "cinemaBanner") { shown -> HeroBackdrop(shown) }
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(.70f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(season.title, color = theme.textPrimary, style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(season.subtitle.ifBlank { season.indexShow }, color = theme.textSecondary,
                style = TextStyle(fontSize = AppType.Caption), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(seasons.size) { i -> Box(Modifier.size(if (i == index) 8.dp else 6.dp).clip(CircleShape)
                .background(if (i == index) theme.primary else theme.textSecondary.copy(alpha = .4f))) }
        }
    }
}

@Composable
private fun HeroBackdrop(season: PgcSeason) {
    val theme = AppTheme.current
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        AsyncImage(ImageRequest.Builder(context).data(season.backdrop.ifBlank { season.cover }.fixedScheme())
            .size(1280, 360).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to theme.background.copy(alpha = .85f), .7f to theme.background.copy(alpha = .15f), 1f to Color.Transparent)))
    }
}

@Composable
private fun PosterRow(items: List<PgcSeason>, onOpen: (Long) -> Unit, ranked: Boolean = false) {
    val theme = AppTheme.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(theme.cardGap), modifier = Modifier.height(244.dp)) {
        itemsIndexed(items, key = { _, s -> s.seasonId }) { i, season ->
            PosterCard(season, Modifier.width(130.dp).fillMaxHeight(), if (ranked) i + 1 else null) { onOpen(season.seasonId) }
        }
    }
}

@Composable
private fun PosterCard(season: PgcSeason, modifier: Modifier = Modifier, rank: Int? = null, onClick: () -> Unit) {
    val theme = AppTheme.current
    val context = LocalContext.current
    Column(modifier.focusRing(contentDescription = buildString {
        if (rank != null) append("第${rank}名，")
        append(season.title)
        if (season.hasScore) append("，${season.score}分")
        if (season.accessBadge.isNotBlank()) append("，${season.accessBadge}")
    }, elevateOnFocus = true, onClick = onClick).padding(3.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(theme.cardCorner - 3.dp))) {
            AsyncImage(ImageRequest.Builder(context).data(season.cover.fixedScheme()).size(300, 450).build(),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            if (season.hasScore) Text(season.score, color = Color.White, style = TextStyle(fontSize = AppType.Small, fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.TopStart).padding(5.dp).background(Color(0xBB000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp))
            if (season.accessBadge.isNotBlank()) Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) { ContentBadge(season.accessBadge) }
            if (rank != null) Text("$rank", color = Color.White, style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.BottomStart).background(Color(0xBB000000)).padding(5.dp))
            if (season.indexShow.isNotBlank()) Text(season.indexShow, color = Color.White, maxLines = 1,
                style = TextStyle(fontSize = AppType.Tiny), modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xBB000000)).padding(5.dp))
        }
        Text(season.title, color = theme.textPrimary, style = TextStyle(fontSize = AppType.Caption, lineHeight = 17.sp),
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
    }
}

class PgcViewModel(app: Application) : AndroidViewModel(app) {
    private val api = (app as BiliTvApp).api
    var type by mutableStateOf(PgcType.MOVIE); private set
    var items by mutableStateOf<List<PgcSeason>>(emptyList()); private set
    var banners by mutableStateOf<List<PgcSeason>>(emptyList()); private set
    var ranking by mutableStateOf<List<PgcSeason>>(emptyList()); private set
    var picks by mutableStateOf<List<PgcSeason>>(emptyList()); private set
    var fields by mutableStateOf<List<PgcFilterField>>(emptyList()); private set
    var selectedFilters by mutableStateOf<Map<String,String>>(emptyMap()); private set
    var loading by mutableStateOf(true); private set
    var loadingMore by mutableStateOf(false); private set
    var hasMore by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    private var page = 1
    private var job: Job? = null
    private data class Channel(val items: List<PgcSeason>, val banners: List<PgcSeason>, val rank: List<PgcSeason>, val fields: List<PgcFilterField>)
    private val cache = HashMap<PgcType, Channel>()

    fun show(next: PgcType) { type = next; selectedFilters = emptyMap(); fetch(useCache = true) }
    fun reload() { cache.remove(type); fetch() }
    fun filter(values: Map<String,String>) {
        selectedFilters = values.filter { (id, value) -> fields.any { it.id == id && it.values.any { v -> v.id == value } } }
        fetch(indexOnly = true)
    }
    fun shufflePicks() { picks = ranking.ifEmpty { items }.shuffled().take(12) }
    private fun apply(channel: Channel) {
        items = channel.items; banners = channel.banners; ranking = channel.rank; fields = channel.fields
        page = 1; hasMore = items.size >= 30; error = if (items.isEmpty()) "列表为空，可调整筛选或重新加载" else null
        shufflePicks(); loading = false
    }
    private suspend fun <T> optional(fallback: T, block: suspend () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { AppLog.w("Pgc", "${type.label}可选数据失败：${e.javaClass.simpleName}"); fallback }
    private fun fetch(useCache: Boolean = false, indexOnly: Boolean = false) {
        job?.cancel(); loadingMore = false; loading = true; error = null
        if (indexOnly) { items = emptyList(); hasMore = false }
        val which = type; val selected = selectedFilters
        if (useCache) cache[which]?.let { apply(it); return }
        job = viewModelScope.launch {
            try {
                val channel = coroutineScope {
                    val list = async { api.pgcIndex(which, ps = 30, filters = selected) }
                    val rank = async { if (indexOnly) ranking else optional(emptyList()) { api.pgcRank(which) }.distinctBy { it.seasonId } }
                    val banner = async { if (indexOnly) banners else optional(emptyList()) { api.pgcBanner(which) } }
                    val condition = async { if (indexOnly) fields else optional(emptyList()) { api.pgcFilters(which) } }
                    Channel(list.await().distinctBy { it.seasonId }, banner.await(), rank.await(), condition.await())
                }
                if (selected.isEmpty()) cache[which] = channel
                apply(channel)
                AppLog.i("Pgc", "${which.label} 列表${items.size}／热播${ranking.size}／Banner${banners.size}／筛选${fields.size}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { loading = false; error = "加载失败，请重试"; AppLog.w("Pgc", e.javaClass.simpleName) }
        }
    }
    fun more() {
        if (loading || loadingMore || !hasMore) return
        loadingMore = true; error = null
        val which = type; val selected = selectedFilters
        job = viewModelScope.launch {
            try {
                val result = api.pgcIndex(which, page + 1, 30, selected)
                if (result.isEmpty()) error = "本页未取得更多内容，可以重试"
                else {
                    items = (items + result).distinctBy { it.seasonId }; page++; hasMore = result.size >= 30
                    AppLog.i("Pgc", "${which.label} 第$page 页 +${result.size}，累计${items.size}条")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "加载更多失败，请重试" }
            finally { loadingMore = false }
        }
    }
}
