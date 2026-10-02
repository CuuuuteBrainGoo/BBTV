package top.bilitv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import top.bilitv.ui.theme.AppTheme

/*
 * ════════════════════════════════════════════════════════════════════════════
 * 影院主视觉（Hero）—— 首页 / 详情页共用的"大图 + 大字 + 主操作"
 * ════════════════════════════════════════════════════════════════════════════
 *
 * ## 它从哪来（为什么不是"新发明一种风格"）
 *
 * `docs/audit/A9` §7.2 的策略是"**不发明新风格，把 `PgcScreen` 已验证的影院语言
 * 搬给其他页面**"。这套语言在影视页已经跑了一轮，长这样：
 *
 * - 全幅大图，**叠两层渐变**（纵向融进页面底色 + 横向左侧压暗），中间不能有硬边
 * - 一个 40sp 的大标题（是首页卡片标题的两倍多，字号阶梯就是这么拉开的）
 * - 一行元信息 + 一个**实心主色**的主操作按钮
 *
 * 首页原来没有这一层，一进去就是 4 列灰扑扑的网格，少爷嫌"灰扑扑"就是嫌这个。
 *
 * ## ⚠️ 和 `PgcScreen` 里的那份是什么关系（诚实交代）
 *
 * `docs/audit/A9` §7.2 原文写的是"`Hero.kt` 已经写好了，未接上" ——
 * **这句和代码对不上**：仓库里**没有** `ui/components/Hero.kt`，
 * 那份实现是 `PgcScreen.kt` 里的私有 `CinemaHero`，只能在影视页用。
 *
 * 所以这里的做法是：**照那份已验证的实现，抽一个可复用的版本**，
 * 给首页和详情页用。**没有去改 `PgcScreen` / `PgcDetailScreen`** ——
 * 那两页是已验证模板，本轮不动，避免把好的改坏。
 *
 * 代价是暂时存在**两份 Hero**（影视页一份私有、这里一份公用）。
 * 建议后续让影视页也换成这一份（需要给它补 `badge` / `score` 两个参数），
 * 但那是**独立的一次改动**，不该混在"接首页"里做。
 *
 * ## 尺寸边界
 *
 * - 高度：读皮肤 `theme.heroHeight`（不写死在这里，见 `Theme.kt` 的字段说明）
 * - 字号：页面级的排版常量，和 `FeedCard` / `SectionTabBar` 一个约定
 *   （文件私有 + 带注释）。它们**不随皮肤变**，所以不进 `BiliTheme`。
 *
 * ## 为什么没有"小字标签"（kicker）
 *
 * 设计原型 `design/proto/A-cinema.html` 在标题上方放了一行小字
 * （原型里写的是"今日精选 · 编辑推荐"）。**这一版没做**，两个原因：
 * 1. 首页的**子标签栏就在主视觉正上方 40dp**，再放一行"推荐 / 热门"会和它撞词 —
 *    同一屏里同一个词出现两遍，看着像 bug 而不是设计。
 * 2. 影视页那套已验证的影院语言里**没有这一行**（它有的是角标 + 评分）。
 *
 * "主色常驻"这件事已经由**实心主色的「播放」按钮**做到了，不缺这一行。
 * 等 `docs/31` 定了小字到底写什么（且不与子标签撞词）再加也不迟。
 */

/**
 * 影院主视觉。
 *
 * @param imageUrl 大图地址（会在内部补 https 协议）。**留空则只画渐变底** ——
 *   图片没到时不留一块黑，视觉上是"渐变还在、图还没来"。
 * @param title 大标题。最多两行，超出省略。
 * @param meta 元信息行（已由调用方拼好，如 `UP主 · 12.3万播放 · 04:42`）。
 *   传空串则整行不画。
 * @param actionText 主操作按钮文案。
 * @param onClick 主操作。遥控器确定键 / 点击都触发。
 * @param focusRequester 非空时挂到主操作按钮上。
 *
 *   ⚠️ **只在"这一页没有别的可聚焦元素"时才传**（详情页）：
 *   首页主视觉**故意不接** —— 首页的默认焦点要落在第一张卡片上，
 *   那套"记住上次焦点在哪张卡"的逻辑不能被主视觉抢走。
 */
@Composable
fun CinemaHero(
    imageUrl: String?,
    title: String,
    meta: String,
    modifier: Modifier = Modifier,
    /**
     * 非空才画那颗**实心主色操作按钮**。
     *
     * 2026-09-29 少爷在真机截图上手绘标注：「**左边查看详情按钮不要了**」。
     * 所以首页**不传**这个参数；详情页仍旧传「播放」（那一页的主操作必须在页头上，
     * 否则用户进来得先找半天）。
     */
    actionText: String? = null,
    /** 点整块主视觉。只有需要兜底焦点（页面上没有别的焦点目标）时才会被用到。 */
    onClick: (() -> Unit)? = null,
    /**
     * 非空 = **整块主视觉成为可聚焦 + 可点目标**。
     *
     * 只给"这一页除了主视觉没有别的焦点目标"的兜底场景用
     * （`docs/99` §D 记过：整页没有可聚焦元素 = 遥控器死键）。
     * 常态下传 null —— 主视觉就是一块**纯展示**的图，不占焦点停留点。
     */
    focusRequester: FocusRequester? = null,
    /** 覆盖高度。null = 用皮肤 `theme.heroHeight`（首页用；详情页页头另有更高的一档）。 */
    height: Dp? = null,
    /**
     * 标题最多显示几行。**默认 1 行**。
     *
     * 首页要 1 行：少爷要"头部变小 + 标题靠下"，两条在矮头部里会打架 ——
     * 160dp 里塞 2 行标题就占满了，标题会顶到头部上沿，"靠下"不成立。
     * 收成 1 行后上面空出 60dp 以上的图，标题才真的落在下半部分。
     *
     * 详情页要 2 行：那是一整页的页头，**标题本身就是要看的信息**，
     * 它下面还有「播放」按钮占着位置，不存在"占满"的问题。
     */
    titleMaxLines: Int = 1,
) {
    val theme = AppTheme.current
    val context = LocalContext.current
    val heroHeight = height ?: theme.heroHeight

    /*
     * 焦点该挂在谁身上？两种形态：
     *
     * - **有操作按钮**（详情页）：挂**按钮**。用户要落的是"播放"那个动作，
     *   挂整块的话焦点框会圈住整张图，反而看不出能按什么。
     * - **没按钮**（首页常态）：整块就是纯展示，**不参与焦点**。
     *   只有一种例外 —— 这一页**没有任何别的焦点目标**时（首页一张卡都没有），
     *   才把整块主视觉做成兜底焦点，否则就是"遥控器死键"（`docs/99` §D）。
     *
     * ⚠️ 别把两种形态同时挂上：那会形成嵌套焦点目标（外框 + 内按钮），
     * 方向键的落点在两个框之间跳，手感会很怪。
     */
    val buttonGetsFocus = actionText != null && focusRequester != null
    val heroGetsFocus = actionText == null && focusRequester != null && onClick != null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight)
            .background(theme.background)
            .then(
                if (heroGetsFocus) {
                    Modifier
                        .focusRequester(focusRequester!!)
                        .focusRing(onClick = onClick!!)
                } else {
                    Modifier
                },
            ),
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl.fixedScheme())
                    // 主视觉铺满一屏宽（约 870dp = 1740px @2x）。给到 1280 是权衡：
                    // 再高就是白烧内存 —— 低内存电视上主视觉是全页最重的一张图。
                    // UGC 封面本身分辨率有限（列表接口给的 `pic`），
                    // 放大后的轻微发虚被渐变遮罩压着，远看不明显。
                    .size(HERO_IMAGE_W, HERO_IMAGE_H)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        /*
         * ★ 2026-09-29：两层渐变（纵向 + 横向）换成**一层"从左下往右上"的对角透明过渡**。
         *
         * 少爷在截图上手绘标注：「**从左下做个透明过渡**」。
         *
         * 为什么要换成对角、而不是继续用那两层：
         * - 文字块是**左下角**的（`Alignment.BottomStart`），
         *   两层正交渐变的"暗角"是**左上**，压不住右下方向来的亮画面；
         * - 对角渐变的暗端正好盖住文字所在的左下角，亮端朝右上放图，
         *   一句话：**该暗的地方暗、该亮的地方亮**，一层就够。
         *
         * 暗端直接用**页面底色**（不是黑色）：这样主视觉和页面之间没有硬边，
         * 换肤时也跟着变，不会出现"界面变浅了、主视觉底下还是黑的"。
         */
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val scrim = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to theme.background,
                            HERO_SCRIM_MID_STOP to theme.background.copy(alpha = HERO_SCRIM_MID_ALPHA),
                            1f to Color.Transparent,
                        ),
                        start = Offset(0f, size.height),                        // 左下
                        end = Offset(size.width * HERO_SCRIM_END_X, 0f),        // 右上
                    )
                    onDrawBehind { drawRect(scrim) }
                },
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(HERO_TEXT_WIDTH_FRACTION)
                .padding(
                    start = theme.screenPadding + HERO_TEXT_INSET,
                    // 没有按钮时底下留少一点 —— 少爷嫌这块占地方，别再往下拖
                    bottom = if (actionText != null) HERO_BOTTOM_PADDING else HERO_BOTTOM_PADDING_COMPACT,
                ),
            verticalArrangement = Arrangement.spacedBy(HERO_ITEM_GAP),
        ) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = HERO_TITLE_SIZE,
                    fontWeight = FontWeight.Bold,
                    lineHeight = HERO_TITLE_LINE_HEIGHT,
                ),
                color = theme.textPrimary,
                // 行数由调用方定（见 `titleMaxLines` 的说明）：
                // 首页 1 行（让标题能"靠下"）、详情页 2 行（标题是主要信息）。
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )

            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = TextStyle(fontSize = HERO_META_SIZE),
                    color = theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (actionText != null && onClick != null) {
                FilledActionButton(
                    text = actionText,
                    onClick = onClick,
                    // 焦点请求器要挂在"它后面最近的焦点目标"上 —— 也就是 focusRing 里的 clickable；
                    // `FilledActionButton` 内部就是 `modifier.focusRing(...)`，
                    // 所以这里传进去正好落在它前面。
                    modifier = Modifier
                        .padding(top = HERO_ACTION_TOP_PADDING)
                        .then(if (buttonGetsFocus) Modifier.focusRequester(focusRequester!!) else Modifier),
                )
            }
        }
    }
}

/** 文字块最多占多宽。超过一半就开始压住画面主体了（照 `PgcScreen` 的实测值）。 */
private const val HERO_TEXT_WIDTH_FRACTION = 0.58f

/**
 * 遮罩（scrim）的参数。2026-09-29 由**两层正交渐变**换成**一层"左下→右上"对角渐变**。
 *
 * 少爷在真机截图上手绘标注：「**从左下做个透明过渡**」。
 *
 * 为什么对角比两层正交更对：文字块在**左下角**，两层渐变的暗角在**左上**，
 * 压不住从右下方向来的亮画面。对角渐变的暗端正好盖住文字所在的左下角、
 * 亮端朝右上放图 —— 该暗的暗、该亮的亮，一层就够。
 *
 * 暗端用**页面底色**而不是黑色：主视觉与页面之间不留硬边，换肤时跟着一起变。
 */
private const val HERO_SCRIM_MID_STOP = 0.45f

/** 对角渐变中段的不透明度。 */
private const val HERO_SCRIM_MID_ALPHA = 0.72f

/**
 * 对角渐变在**水平方向**伸到多宽（相对整个主视觉宽度）才完全透明。
 *
 * 0.85 = 过了 85% 宽度就是干净的原图。不取 1.0 是为了让右上角彻底透出来，
 * 取太小则左侧压暗区不够、白字压不住。
 */
private const val HERO_SCRIM_END_X = 0.85f

/** 文字块相对内容区左边界再往右推一点，别贴着屏幕边。 */
private val HERO_TEXT_INSET = 16.dp

/** 有操作按钮时的底部内衬。 */
private val HERO_BOTTOM_PADDING = 26.dp

/** 没有操作按钮时的底部内衬 —— 少爷嫌这块占地方，别再往下拖。 */
private val HERO_BOTTOM_PADDING_COMPACT = 16.dp

private val HERO_ITEM_GAP = 10.dp
private val HERO_ACTION_TOP_PADDING = 4.dp

/**
 * 字号阶梯（页面级排版常量，不随皮肤变）。
 *
 * 40sp 是首页卡片标题（18sp）的两倍多 —— **层级感就是靠这个差距拉出来的**，
 * 这是 `docs/audit/A9` §12.1 "拉开字号差"那条的具体落点。
 * 行高必须跟着字号一起给（`docs/99` §E：Compose 的行高是独立的，只改 fontSize 会让字叠）。
 *
 * ## ⚠️ 标题**不给**字距（2026-09-29 删掉了 `(-0.01f).em`）
 *
 * 原来这里有个 `HERO_TITLE_TRACKING = (-0.01f).em`，套在 `text = title` 上。
 * 但 `title` 是 B 站视频标题，**绝大多数是中文** —— 中文用负字距会把字挤在一起，
 * 中英混排时更明显。`docs/31` §2.7 的硬约束：**负字距只允许写在"确定是 Latin"
 * 的具体 `Text` 上，不允许进任何共享 token**（中文标题一律 0）。
 * 所以这里既不给 `letterSpacing`，也不留那个常量 —— 留着就是等人再套回去。
 */
private val HERO_TITLE_SIZE = 36.sp
private val HERO_TITLE_LINE_HEIGHT = 42.sp
private val HERO_META_SIZE = 15.sp

/** 主视觉图片解码尺寸（像素）。见 `CinemaHero` 里的取舍说明。 */
private const val HERO_IMAGE_W = 1280
private const val HERO_IMAGE_H = 720

/**
 * 主视觉高度 —— **按屏幕高度取比例，再夹在 [floor] 与 [cap] 之间**。
 *
 * ## 为什么不能只写一个固定 dp（少爷 2026-09-30 第 5 条）
 *
 * 原话：「影视里的 Banner 在手机上看起来太大了……这个能不能优化一下，**自适应**小一些，
 * 或者整体做小一些，不要占据太多视觉空间，**包括电视端**。」
 *
 * 他的判断是对的，而且根因很具体：**固定 dp 在"更长的屏"上占屏比例会暴涨**。
 * 同一块 300dp 的 Banner：
 *
 * | 屏幕 | 高度 | 占屏高 |
 * |---|---|---|
 * | 电视 16:9 | 540dp | 56% |
 * | 手机 21:9 横屏 | **411dp** | **73%** ← 他看到的 |
 *
 * ## 三个参数各自管什么（**缺一个都会出事**）
 *
 * - [fraction]：**主角**。屏高 × 比例 = 想要的视觉体量，这是"自适应"那一半。
 * - [cap]：上限。电视那种高屏上别越过某个绝对值，否则"自适应"会把它放大回去。
 * - [floor]：**下限 = 内容实需**。⛔ 这个最容易漏，而漏了就是 P0 级外观事故：
 *   页头文字块是 `BottomStart` 对齐的，**高度不够时它会往顶上溢**，
 *   表现为标题压住左上角的「返回」、按钮文字被挤糊（详情页真踩过，见 `docs/29` 决策 034）。
 *   所以下限必须 ≥ "文字块内容高度 + 底部内衬"，宁可这一档小不下去，也不能裁。
 *
 * 只写 `screenH × fraction` 而不夹下限的话，411dp 的屏会算出 189dp，
 * 而内容需要 224dp —— **必裁**。这就是为什么这个函数必须有三个参数。
 */
@Composable
fun adaptiveHeroHeight(cap: Dp, floor: Dp, fraction: Float): Dp {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    return minOf(cap, maxOf(floor, screenHeight * fraction))
}
