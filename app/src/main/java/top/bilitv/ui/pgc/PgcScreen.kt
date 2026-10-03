package top.bilitv.ui.pgc

import top.bilitv.R
import androidx.compose.ui.res.stringResource

import top.bilitv.ui.components.cappedCover

import top.bilitv.ui.theme.pageBackground

import top.bilitv.ui.components.verticalScrollbar
import top.bilitv.ui.components.scrollWithScrollbar
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

import android.app.Application
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import top.bilitv.ui.theme.gridCells
import top.bilitv.ui.components.LoadFeedback
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.util.AppLog

@Composable
fun PgcScreen(onOpenSeason: (Long) -> Unit) {
    val vm: PgcViewModel = viewModel()
    val theme = AppTheme.current
    var typeName by rememberSaveable { mutableStateOf(PgcType.MOVIE.name) }
    val type = PgcType.entries.firstOrNull { it.name == typeName } ?: PgcType.MOVIE
    val typeLabel = stringResource(type.labelRes)
    LaunchedEffect(type) { vm.show(type) }
    DisposableEffect(vm) { onDispose { vm.stopLoading() } }
    top.bilitv.ui.OnRefreshRequest { if (!vm.loading) vm.reload() }
    val gridState = rememberLazyGridState()
    val heroFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    val returnKey by remember { derivedStateOf {
        gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long || it.key == "rank" || it.key == "picks" }?.key
    } }
    var sideLayout by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by remember(type) { mutableStateOf(false) }
    var rankingOpen by remember(type) { mutableStateOf(false) }
    var resultsOnly by rememberSaveable(type) { mutableStateOf(false) }
    val resultsFocus = remember { FocusRequester() }
    // Banner endpoints give horizontal art; use an existing matching season poster when available.
    val posters = vm.ranking + vm.items
    val hero = vm.banners.map { banner ->
        posters.firstOrNull { it.seasonId == banner.seasonId && it.cover.isNotBlank() }
            ?.let { banner.copy(cover = it.cover) } ?: banner
    }.ifEmpty { vm.ranking.ifEmpty { vm.items }.take(6) }
    LaunchedEffect(type, rankingOpen, resultsOnly) { gridState.scrollToItem(0) }
    Column(Modifier.fillMaxSize().background(theme.pageBackground)) {
        SectionTabBar(PgcType.entries.map { stringResource(it.labelRes) }, type.ordinal,
            onSelect = { if (it == type.ordinal) vm.reload() else typeName = PgcType.entries[it].name },
            contentFocusRequester = if (resultsOnly) resultsFocus else if (hero.isNotEmpty() && !rankingOpen) heroFocus else null,
            modifier = Modifier.padding(horizontal = theme.screenPadding, vertical = 8.dp))
        when {
            vm.loading && vm.items.isEmpty() && vm.ranking.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            vm.items.isEmpty() && vm.ranking.isEmpty() && !resultsOnly -> Column(Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(vm.error ?: stringResource(if (vm.hasMore) R.string.home_empty_page else R.string.empty_content), color = theme.textSecondary)
                CinemaAction(stringResource(if (vm.hasMore && vm.error == null) R.string.action_continue_loading else R.string.action_reload), Modifier.focusRequester(retryFocus)) {
                    if (vm.hasMore && vm.error == null) vm.more() else vm.retry()
                }
            }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val fontScale = LocalDensity.current.fontScale
                val availableWidth = (maxWidth - theme.screenPadding * 2).value
                val availableHeight = (maxHeight - 16.dp).value
                val side = !rankingOpen && !resultsOnly && hero.isNotEmpty() &&
                    useSideBanner(availableWidth, availableHeight, sideLayout, fontScale)
                SideEffect { sideLayout = side }
                val grid: @Composable (Modifier) -> Unit = { gridModifier ->
                    LazyVerticalGrid(theme.gridCells(poster = true), state = gridState, modifier = gridModifier.focusRequester(gridFocus).verticalScrollbar(gridState),
                    contentPadding = PaddingValues(horizontal = if (side) 0.dp else theme.screenPadding, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
                    verticalArrangement = Arrangement.spacedBy(theme.rowGap)) {
                    if (!rankingOpen && !resultsOnly) {
                        if (!side && hero.isNotEmpty()) item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                            RotatingCinemaHero(hero, onOpenSeason, heroFocus)
                        }
                        if (vm.ranking.isNotEmpty()) {
                            item(key = "rank", span = { GridItemSpan(maxLineSpan) }) {
                                Column {
                                    CinemaHeading(stringResource(R.string.cinema_ranking, typeLabel), "TOP ${vm.ranking.size.coerceAtMost(100)}") { rankingOpen = true }
                                    PosterRow(vm.ranking.take(12), onOpenSeason, ranked = true, leftFocus = if (side) heroFocus else null, entryFocus = if (side && returnKey == "rank") cardFocus else null)
                                }
                            }
                        }
                        if (vm.picks.isNotEmpty()) {
                            item(key = "picks", span = { GridItemSpan(maxLineSpan) }) {
                                Column {
                                    CinemaHeading(stringResource(R.string.cinema_picks), stringResource(R.string.cinema_refresh_picks)) { vm.shufflePicks() }
                                    PosterRow(vm.picks, onOpenSeason, leftFocus = if (side) heroFocus else null, entryFocus = if (side && returnKey == "picks") cardFocus else null)
                                }
                            }
                        }
                    }
                    item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            if (resultsOnly) CinemaAction(stringResource(R.string.action_return_channel)) { resultsOnly = false; vm.filter(emptyMap()) }
                            CinemaHeading(if (resultsOnly) stringResource(R.string.cinema_filter_results, typeLabel)
                                else if (rankingOpen) stringResource(R.string.cinema_ranking_top, typeLabel, vm.ranking.size.coerceAtMost(100))
                                else stringResource(R.string.cinema_all, typeLabel),
                                stringResource(if (rankingOpen) R.string.action_return_channel else R.string.action_filter),
                                if (resultsOnly) Modifier.focusRequester(resultsFocus) else Modifier) {
                                if (rankingOpen) rankingOpen = false else filtersOpen = true
                            }
                        }
                    }
                    val displayed = if (rankingOpen) vm.ranking.take(100) else vm.items
                    itemsIndexed(displayed, key = { _, s -> s.seasonId }) { i, season ->
                        val firstColumn = gridState.layoutInfo.visibleItemsInfo.any { it.key == season.seasonId && it.column == 0 }
                        PosterCard(season, Modifier.fillMaxWidth()
                            .then(if (side && returnKey == season.seasonId) Modifier.focusRequester(cardFocus) else Modifier).focusProperties {
                            if (side && firstColumn) left = heroFocus
                        }, if (rankingOpen) i + 1 else null) { onOpenSeason(season.seasonId) }
                    }
                    if (!rankingOpen) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            if (vm.loading || vm.loadingMore || vm.error != null) LoadFeedback(vm.loading || vm.loadingMore, vm.error, vm::retry)
                            else if (vm.hasMore) CinemaAction(stringResource(R.string.action_load_more)) { vm.more() }
                            else if (resultsOnly && vm.items.isEmpty()) CinemaAction(stringResource(R.string.action_reload)) { vm.reload() }
                            else Text(stringResource(R.string.end_of_list), color = theme.textTertiary)
                        }
                    }
                }
                }
                if (side) Row(Modifier.fillMaxSize().padding(horizontal = theme.screenPadding),
                    horizontalArrangement = Arrangement.spacedBy(theme.cardGap)) {
                    RotatingCinemaHero(hero, onOpenSeason, heroFocus,
                        modifier = Modifier.padding(vertical = 8.dp).width(portraitBannerWidth(availableHeight).dp).fillMaxHeight(),
                        portrait = true, rightFocus = if (returnKey != null) cardFocus else gridFocus)
                    grid(Modifier.weight(1f).fillMaxHeight())
                } else grid(Modifier.fillMaxSize())
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
            .scrollWithScrollbar(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.cinema_filters, stringResource(vm.type.labelRes)), color = theme.textPrimary, style = TextStyle(fontSize = AppType.H3))
            if (vm.fields.isEmpty()) Text(stringResource(R.string.cinema_no_filters), color = theme.textSecondary)
            vm.fields.filter { it.values.isNotEmpty() }.forEachIndexed { i, field ->
                val value = field.values.firstOrNull { it.id == selected[field.id] }
                    ?: field.values.firstOrNull { it.id == if (field.id == "order") "2" else "-1" } ?: field.values.first()
                ChoiceRow(field.label, null, field.values, value, { it.label },
                    onSelect = { selected = selected + (field.id to it.id) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
            SettingRow(stringResource(R.string.action_apply_filters), null, "", if (vm.fields.isEmpty()) Modifier.focusRequester(first) else Modifier) { onApplied(); vm.filter(selected); onDismiss() }
            SettingRow(stringResource(R.string.action_reset_filters), null, "") { onApplied(); vm.filter(emptyMap()); onDismiss() }
        }
        RequestFocusOnAppear(first, true)
    }
}

@Composable
private fun RotatingCinemaHero(seasons: List<PgcSeason>, onOpen: (Long) -> Unit, focusRequester: FocusRequester,
    modifier: Modifier = Modifier, portrait: Boolean = false, rightFocus: FocusRequester? = null) {
    val theme = AppTheme.current
    var index by remember(seasons) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(seasons, focused) {
        if (focused || seasons.size < 2) return@LaunchedEffect
        while (true) { delay(8000); index = (index + 1) % seasons.size }
    }
    val season = seasons[index.coerceIn(0, seasons.lastIndex)]
    val currentIndex by rememberUpdatedState(index)
    Box(modifier.then(if (portrait) Modifier else Modifier.fillMaxWidth().height(adaptiveHeroHeight(180.dp, 130.dp, .32f)))
        .focusProperties { if (rightFocus != null) right = rightFocus }
        .pointerInput(seasons) {
            var distance = 0f
            detectHorizontalDragGestures(onDragStart = { distance = 0f }, onHorizontalDrag = { change, amount ->
                change.consume(); distance += amount
            }, onDragEnd = {
                if (kotlin.math.abs(distance) > 40.dp.toPx()) index = (currentIndex + if (distance > 0) seasons.size - 1 else 1) % seasons.size
            })
        }.focusRequester(focusRequester).onFocusChanged { focused = it.hasFocus }
        .onPreviewKeyEvent {
            val previous = if (portrait) Key.DirectionUp else Key.DirectionLeft
            val next = if (portrait) Key.DirectionDown else Key.DirectionRight
            if (it.key == previous || it.key == next) {
                if (it.type == KeyEventType.KeyDown) index = (index + if (it.key == previous) seasons.size - 1 else 1) % seasons.size
                true
            } else false
        }.focusRing(contentDescription = stringResource(if (portrait) R.string.cinema_banner_side else R.string.cinema_banner_horizontal, season.title), scaleOnFocus = 1f,
            onClick = { onOpen(season.seasonId) }).padding(3.dp)) {
        if (theme.animations) Crossfade(season, animationSpec = tween(350), label = "cinemaBanner") { shown -> HeroBackdrop(shown, portrait) }
        else HeroBackdrop(season, portrait)
        Column(Modifier.align(if (portrait) Alignment.BottomStart else Alignment.CenterStart).fillMaxWidth(if (portrait) 1f else .70f)
            .padding(horizontal = if (portrait) 12.dp else 20.dp, vertical = if (portrait) 28.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(season.title, color = theme.textPrimary, style = TextStyle(fontSize = if (portrait) 22.sp else 26.sp, fontWeight = FontWeight.Bold),
                maxLines = if (portrait) 2 else 1, overflow = TextOverflow.Ellipsis)
            Text(season.subtitle.ifBlank { season.indexShow }, color = theme.textSecondary,
                style = TextStyle(fontSize = AppType.Caption), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (portrait) Text("${index + 1}/${seasons.size}", color = theme.textSecondary, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp))
        else Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(seasons.size) { i -> Box(Modifier.size(if (i == index) 8.dp else 6.dp).clip(CircleShape)
                .background(if (i == index) theme.primary else theme.textSecondary.copy(alpha = .4f))) }
        }
    }
}

@Composable
private fun HeroBackdrop(season: PgcSeason, portrait: Boolean) {
    val theme = AppTheme.current
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        AsyncImage(ImageRequest.Builder(context).data((if (portrait) season.cover.ifBlank { season.backdrop } else season.backdrop.ifBlank { season.cover }).fixedScheme())
            .size(if (portrait) 400 else 1280, if (portrait) 600 else 360).build(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        val scrim = if (portrait) Brush.verticalGradient(
            0f to Color.Transparent, .5f to theme.background.copy(alpha = .1f), 1f to theme.background.copy(alpha = .95f))
        else Brush.horizontalGradient(
            0f to theme.background.copy(alpha = .85f), .7f to theme.background.copy(alpha = .15f), 1f to Color.Transparent)
        Box(Modifier.fillMaxSize().background(scrim))
    }
}

@Composable
private fun PosterRow(items: List<PgcSeason>, onOpen: (Long) -> Unit, ranked: Boolean = false, leftFocus: FocusRequester? = null, entryFocus: FocusRequester? = null) {
    val theme = AppTheme.current
    val width = theme.cardMinWidth * .62f
    val rowState = rememberLazyListState()
    LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(theme.cardGap)) {
        itemsIndexed(items, key = { _, s -> s.seasonId }) { i, season ->
            PosterCard(season, Modifier.width(width).then(if (i == rowState.firstVisibleItemIndex && entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier).focusProperties { if (i == 0 && leftFocus != null) left = leftFocus }, if (ranked) i + 1 else null) { onOpen(season.seasonId) }
        }
    }
}

@Composable
private fun PosterCard(season: PgcSeason, modifier: Modifier = Modifier, rank: Int? = null, onClick: () -> Unit) {
    val theme = AppTheme.current
    val context = LocalContext.current
    val settings = (context.applicationContext as? BiliTvApp)?.settings
    val cardFocus = remember { FocusRequester() }
    val openMenu = rememberVideoCardMenu(season.title, onClick, cardFocus)
    val titleHeight = with(LocalDensity.current) { 17.sp.toDp() * 2 + 11.dp }
    val coverLimit = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 160.dp - titleHeight)
        .coerceAtLeast(72.dp)
    Column(modifier.focusRequester(cardFocus).onPreviewKeyEvent { e ->
        if (e.key != Key.Menu || settings?.cardMenuOnMenuKey == false) false
        else { if (e.type == KeyEventType.KeyUp && !e.nativeKeyEvent.isCanceled) openMenu(); true }
    }.focusRing(contentDescription = buildString {
        if (rank != null) append(context.getString(R.string.cinema_rank_number, rank))
        append(season.title)
        if (season.hasScore) append(context.getString(R.string.cinema_rating, season.score))
        if (season.accessBadge.isNotBlank()) append("，${season.accessBadge}")
    }, elevateOnFocus = true, onClick = onClick, onLongClick = openMenu).padding(3.dp)) {
        Box(Modifier.fillMaxWidth().cappedCover(2f / 3f, coverLimit).clip(RoundedCornerShape(theme.cardCorner - 3.dp))) {
            AsyncImage(ImageRequest.Builder(context).data(season.cover.fixedScheme()).size(300, 450).build(),
                contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
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
    private val graph = app as BiliTvApp
    private val api = graph.api
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
    private var failedMore = false
    private var job: Job? = null
    private var generation = 0
    private var loadedType: PgcType? = null
    private data class Channel(val page: PgcIndexPage, val banners: List<PgcSeason>, val rank: List<PgcSeason>, val fields: List<PgcFilterField>)
    private val cache = HashMap<PgcType, Channel>()

    fun show(next: PgcType) {
        if (type == next) {
            if (job?.isActive == true || loadedType == next && error == null) return
            fetch(useCache = selectedFilters.isEmpty(), indexOnly = selectedFilters.isNotEmpty())
            return
        }
        if (type != next) { items = emptyList(); banners = emptyList(); ranking = emptyList(); picks = emptyList() }
        type = next; selectedFilters = emptyMap(); fetch(useCache = true)
    }
    fun stopLoading() {
        generation++
        job?.cancel(); job = null
        loading = false; loadingMore = false
    }
    fun reload() { cache.remove(type); fetch() }
    fun retry() { if (failedMore) more() else reload() }
    fun filter(values: Map<String,String>) {
        selectedFilters = values.filter { (id, value) -> fields.any { it.id == id && it.values.any { v -> v.id == value } } }
        fetch(indexOnly = true)
    }
    fun shufflePicks() { picks = ranking.ifEmpty { items }.shuffled().take(12) }
    private fun apply(channel: Channel) {
        items = channel.page.items.distinctBy { it.seasonId }; banners = channel.banners; ranking = channel.rank; fields = channel.fields
        page = 1; hasMore = channel.page.hasMore
        error = if (items.isEmpty() && !hasMore) graph.getString(R.string.cinema_empty) else null
        shufflePicks(); loading = false
    }
    private suspend fun <T> optional(fallback: T, block: suspend () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { AppLog.w("Pgc", "${type.name}可选数据失败：${e.javaClass.simpleName}"); fallback }
    private fun fetch(useCache: Boolean = false, indexOnly: Boolean = false) {
        stopLoading(); loading = true; error = null; failedMore = false
        loadedType = null
        if (indexOnly) { items = emptyList(); hasMore = false }
        val which = type; val selected = selectedFilters
        if (useCache) cache[which]?.let { apply(it); loadedType = which; return }
        val g = generation
        job = viewModelScope.launch {
            try {
                val channel = coroutineScope {
                    val list = async { api.pgcIndexPage(which, ps = 30, filters = selected) }
                    val rank = async { if (indexOnly) ranking else optional(emptyList()) { api.pgcRank(which) }.distinctBy { it.seasonId } }
                    val banner = async { if (indexOnly) banners else optional(emptyList()) { api.pgcBanner(which) } }
                    val condition = async { if (indexOnly) fields else optional(emptyList()) { api.pgcFilters(which) } }
                    Channel(list.await(), banner.await(), rank.await(), condition.await())
                }
                currentCoroutineContext().ensureActive()
                if (g != generation || which != type || selected != selectedFilters) return@launch
                if (selected.isEmpty()) cache[which] = channel
                apply(channel)
                loadedType = which
                AppLog.i("Pgc", "${which.name} 列表${items.size}／热播${ranking.size}／Banner${banners.size}／筛选${fields.size}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (g == generation) { error = graph.getString(R.string.loading_failed); AppLog.w("Pgc", e.javaClass.simpleName) }
            } finally { if (g == generation) loading = false }
        }
    }
    fun more() {
        if (loading || loadingMore || !hasMore) return
        loadingMore = true; error = null
        val which = type; val selected = selectedFilters
        val g = generation
        job = viewModelScope.launch {
            try {
                val requestedPage = page + 1
                val result = api.pgcIndexPage(which, requestedPage, 30, selected)
                currentCoroutineContext().ensureActive()
                if (g != generation || which != type || selected != selectedFilters) return@launch
                items = (items + result.items).distinctBy { it.seasonId }
                page = requestedPage; hasMore = result.hasMore; failedMore = false
                AppLog.i("Pgc", "${which.name} 第$page 页 +${result.items.size}，累计${items.size}条，还有页=$hasMore")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (g == generation) { failedMore = true; error = graph.getString(R.string.loading_more_failed) } }
            finally { if (g == generation) loadingMore = false }
        }
    }
}
