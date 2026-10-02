package top.bilitv.ui.search

import android.app.Application
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import top.bilitv.BiliTvApp
import top.bilitv.data.model.FeedItem
import top.bilitv.ui.components.FeedCard
import top.bilitv.ui.components.RequestFocusOnAppear
import top.bilitv.ui.components.focusRing
import top.bilitv.ui.components.observeFocus
import top.bilitv.ui.theme.AppTheme
import top.bilitv.ui.theme.AppType
import top.bilitv.util.AppLog

/**
 * 搜索页。
 *
 * ## 电视上做搜索的真正难点不是"输入"，是"少输入"
 *
 * 遥控器打一个字要按三四下方向键，打"鬼灭之刃"要按几十下。所以这一页的重点是：
 *
 * 1. **热搜词直接摆出来**（`x/web-interface/search/square`，游客态可用）。
 *    大多数时候用户想搜的东西就在里面，按一下就走，一个字都不用打。
 * 2. **搜索框放在最显眼的位置**，但**不抢焦点** ——
 *    一进页面就弹出软键盘、挡住半屏热搜，是帮倒忙。焦点先落在第一个热搜词上。
 * 3. 按住确认键才唤起键盘（[onPreviewKeyEvent]），用户主动要打字时才弹。
 *
 * ## 结果复用首页那张卡片
 *
 * 搜索结果和首页推荐是同一种东西（UGC 视频），所以卡片必须是同一张 ——
 * 两处各写一份的话，以后改卡片就要改两个地方，迟早长歪。
 */
@Composable
fun SearchScreen(onOpen: (String) -> Unit) {
    val vm: SearchViewModel = viewModel()
    val theme = AppTheme.current

    // 进页面就把热搜拉回来。失败也不报错，搜索本身不依赖它。
    LaunchedEffect(Unit) { vm.loadHot() }

    val firstHot = remember { FocusRequester() }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            value = vm.keyword,
            onValueChange = { vm.keyword = it },
            onSubmit = { vm.search() },
            modifier = Modifier.padding(
                start = theme.screenPadding,
                end = theme.screenPadding,
                top = 12.dp,
                bottom = 10.dp,
            ),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                vm.results.isNotEmpty() -> LazyVerticalGrid(
                    columns = GridCells.Fixed(theme.cardColumns),
                    contentPadding = PaddingValues(
                        start = theme.screenPadding,
                        end = theme.screenPadding,
                        bottom = theme.screenPadding,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
                    verticalArrangement = Arrangement.spacedBy(theme.rowGap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    gridItems(vm.results, key = { it.bvid }) { item ->
                        FeedCard(
                            item = item,
                            onClick = { onOpen(item.bvid) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                vm.error != null -> Hint(vm.error!!)

                vm.searched -> Hint("没有找到「${vm.lastKeyword}」相关的视频，换个词试试")

                else -> HotWords(
                    words = vm.hotWords,
                    onPick = { vm.searchWith(it) },
                    firstFocusRequester = firstHot,
                )
            }
        }
    }

    // 焦点先给第一个热搜词 —— 不打字就能用，这比"先弹键盘"友好得多
    RequestFocusOnAppear(firstHot, vm.hotWords.isNotEmpty() && !vm.searched && !vm.loading)
}

/**
 * 搜索框。
 *
 * 「别在进页面时自动弹软键盘」是刻意的：电视上键盘会盖住大半个屏幕，
 * 而用户十有八九是冲着热搜词来的。只有当他**按了确认键**，
 * 才说明他确实要打字 —— 这时再弹。
 */
@Composable
private fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AppTheme.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(8.dp)
    val border by animateColorAsState(
        targetValue = if (focused) theme.focusRing else theme.divider,
        label = "searchBarBorder",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(theme.surface)
            .border(if (focused) theme.focusBorderWidth else 1.dp, border, shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "🔍",
            style = TextStyle(fontSize = AppType.Body3),
            color = theme.textTertiary,
            modifier = Modifier.padding(end = 10.dp),
        )

        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = "搜索视频（按确认键调出键盘）",
                    style = TextStyle(fontSize = AppType.Body3),
                    color = theme.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = AppType.Body3, color = theme.textPrimary),
                cursorBrush = SolidColor(theme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .observeFocus { focused = it }
                    // 用 onPreviewKeyEvent：确认键要先被我们接住，
                    // 不能让它落到 BasicTextField 内部的点击处理上
                    // （那些处理会把按键当成"把光标移到点击处"）。
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyUp && e.key == Key.DirectionCenter) {
                            keyboard?.show()
                            true
                        } else {
                            false
                        }
                    },
            )
        }

        if (value.isNotEmpty()) {
            Text(
                text = "搜索",
                style = TextStyle(fontSize = AppType.Body3, fontWeight = FontWeight.Medium),
                color = theme.primary,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onSubmit)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 热搜词。电视上最重要的一块 —— 不打字就能搜。 */
@Composable
private fun HotWords(
    words: List<String>,
    onPick: (String) -> Unit,
    firstFocusRequester: FocusRequester,
) {
    val theme = AppTheme.current

    if (words.isEmpty()) {
        Hint("输入关键词开始搜索")
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(
            start = theme.screenPadding,
            end = theme.screenPadding,
        ),
    ) {
        Text(
            text = "大家都在搜",
            style = TextStyle(fontSize = AppType.H4, fontWeight = FontWeight.SemiBold),
            color = theme.textPrimary,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        // 不用 LazyRow：热搜只有十来个，普通 Row 就够；
        // 而且它们不该横向滚 —— 一屏全部看到才对，滚动会让人以为就这几个
        Row(
            horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
        ) {
            words.take(6).forEachIndexed { index, word ->
                HotChip(
                    word = word,
                    onClick = { onPick(word) },
                    modifier = Modifier.then(
                        if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier
                    ),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(theme.cardGap),
            modifier = Modifier.padding(top = theme.cardGap),
        ) {
            words.drop(6).take(6).forEach { word ->
                HotChip(word = word, onClick = { onPick(word) })
            }
        }
    }
}

@Composable
private fun HotChip(word: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = AppTheme.current
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            // 常态就有 surface 底（不是透明），所以传 restFill。
            // 焦点会压到相邻 chip，抬到最上层。
            .observeFocus { focused = it }
            .focusRing(
                contentDescription = "搜索 $word",
                restFill = theme.surface,
                elevateOnFocus = true,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(
            text = word,
            style = TextStyle(fontSize = AppType.Body3),
            color = if (focused) theme.textPrimary else theme.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun Hint(text: String) {
    val theme = AppTheme.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = TextStyle(fontSize = AppType.Body2),
            color = theme.textSecondary,
        )
    }
}

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app as BiliTvApp

    var keyword by mutableStateOf("")
    var results by mutableStateOf<List<FeedItem>>(emptyList())
        private set
    var hotWords by mutableStateOf<List<String>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** 搜过至少一次。用来区分"还没搜"和"搜了但没结果" —— 这两种要显示完全不同的东西。 */
    var searched by mutableStateOf(false)
        private set

    var lastKeyword by mutableStateOf("")
        private set

    private var inFlight = false

    fun loadHot() {
        if (hotWords.isNotEmpty()) return
        viewModelScope.launch {
            hotWords = graph.api.hotSearch()
            AppLog.i("Search", "热搜 ${hotWords.size} 条")
        }
    }

    fun search() = submit(keyword)

    /** 点热搜词直接搜，并把搜索框里的字也换掉 —— 不然用户会以为搜的是框里那个词 */
    fun searchWith(word: String) {
        keyword = word
        submit(word)
    }

    private fun submit(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) {
            // 空关键词不请求：既省一次调用，也避免把"搜索框是空的"渲染成一次失败
            error = "先输入要搜的内容，或者直接点下面的热搜词"
            return
        }
        if (inFlight) return
        inFlight = true
        loading = true
        error = null
        searched = true
        lastKeyword = q
        viewModelScope.launch {
            results = graph.api.searchVideo(q)
            // ⚠️ 这里**故意不去猜**"空结果是没搜到还是接口挂了"：
            //    searchVideo 为了界面不崩，失败时也返回空列表，两者在这里无法区分。
            //    所以界面统一显示"没有找到"，而把"到底是什么原因"留在日志里
            //    （BiliApi.searchVideo 会把服务端原话打进 AppLog）。
            //    与其随便挑一个原因猜给用户看，不如给一句不会误导的话。
            error = null
            loading = false
            inFlight = false
            AppLog.i("Search", "「$q」→ ${results.size} 条")
        }
    }
}
